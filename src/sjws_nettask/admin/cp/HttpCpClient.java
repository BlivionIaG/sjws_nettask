/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.admin.cp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import sjws_nettask.admin.cp.CpClient.ConfigBundle;
import sjws_nettask.admin.cp.CpClient.Section;
import sjws_nettask.tls.TlsMaterial;

/**
 * {@link CpClient} over the control-plane admin API ({@code /cp/v1}, PR #76) on one endpoint,
 * authenticated by the operator's client certificate (mTLS). Wrap several in
 * {@link FailoverCpClient} for CP failover.
 * <p>
 * Every call the console makes has a server route (#76, #96). Mutations carry the caller's
 * {@code Idempotency-Key}, which the CP replays, so a write retried on another endpoint after a
 * failover is applied once. What the API still lacks is listed in {@link #MISSING}.
 */
public final class HttpCpClient implements CpClient {

    /** Endpoints the console needs that the admin API does not have yet. */
    public static final List<String> MISSING = List.of(
            "structured edits of routes/filters/certs/topics (the API takes a whole document; only access.rules is"
                    + " editable)",
            "per-node rollout acks and failures in GET /cp/v1/rollout (it reports the stage index and selectors)");

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String endpoint;
    private final URI base;
    private final HttpClient http;
    private final ReentrantLock lock = new ReentrantLock();
    private String headYaml = "";
    private long headVersion;
    private Rollout lastRollout;

    /** @param endpoint {@code host:port} of a control node's admin listener */
    public HttpCpClient(String endpoint, SSLContext tls) {
        this.endpoint = endpoint;
        this.base = URI.create("https://" + endpoint);
        this.http = HttpClient.newBuilder().sslContext(tls).connectTimeout(TIMEOUT).build();
    }

    /**
     * mTLS context from PEM files: operator certificate + key, and the CA bundle (or pinned
     * certificates) the control plane's certificate must chain to.
     */
    public static SSLContext tls(Path certPem, Path keyPem, String keyPassword, Path trustPem)
            throws IOException, GeneralSecurityException {
        var material = TlsMaterial.fromPem(certPem, keyPem, keyPassword);
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(trustPem)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("ca" + i++, cert);
            }
            if (i == 0) {
                throw new GeneralSecurityException("no certificate in " + trustPem);
            }
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(material.keyManagerFactory().getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    @Override
    public String endpoint() {
        return this.endpoint;
    }

    Map<String, Object> send(String method, String path, String body) throws CpException {
        return send(method, path, body, null);
    }

    Map<String, Object> send(String method, String path, String body, String idempotencyKey) throws CpException {
        var builder = HttpRequest.newBuilder(this.base.resolve(path)).timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (idempotencyKey != null) {
            builder = builder.header("Idempotency-Key", idempotencyKey);
        }
        builder = body == null ? builder.method(method, HttpRequest.BodyPublishers.noBody())
                : builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response;
        try {
            response = this.http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CpException(CpException.Kind.UNAVAILABLE, this.endpoint + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CpException(CpException.Kind.UNAVAILABLE, this.endpoint + ": interrupted");
        }
        Map<String, Object> json;
        try {
            json = MiniJson.object(response.body());
        } catch (IllegalArgumentException e) {
            throw new CpException(CpException.Kind.UNAVAILABLE, this.endpoint + " sent a non-JSON reply (HTTP "
                    + response.statusCode() + ")");
        }
        int status = response.statusCode();
        if (status == 200) {
            return json;
        }
        var message = String.valueOf(json.getOrDefault("error", "HTTP " + status));
        throw new CpException(switch (status) {
            case 400 -> CpException.Kind.INVALID;
            case 401, 403 -> CpException.Kind.FORBIDDEN;
            case 404 -> CpException.Kind.NOT_FOUND;
            case 409 -> CpException.Kind.CONFLICT;
            default -> CpException.Kind.UNAVAILABLE; // 503 "leader not ready", "log": try another node
        }, message);
    }

    private static long number(Object value) {
        return value instanceof Long l ? l : -1;
    }

    @Override
    public ClusterView cluster() throws CpException {
        var health = send("GET", "/cp/v1/health", null);
        var nodes = send("GET", "/cp/v1/nodes", null);
        long head = head();
        var views = new ArrayList<NodeView>();
        if (nodes.get("nodes") instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> n) {
                    var role = String.valueOf(n.get("role")).toUpperCase(java.util.Locale.ROOT);
                    var labels = new java.util.LinkedHashMap<String, String>();
                    if (n.get("labels") instanceof Map<?, ?> l) {
                        l.forEach((k, v) -> labels.put(String.valueOf(k), String.valueOf(v)));
                    }
                    var health1 = Boolean.TRUE.equals(n.get("revoked")) ? "REVOKED"
                            : n.get("health") == null ? "?" : String.valueOf(n.get("health"));
                    views.add(new NodeView(String.valueOf(n.get("id")), role, labels, health1,
                            number(n.get("appliedVersion")), number(n.get("lag"))));
                }
            }
        }
        var leader = health.get("leader");
        return new ClusterView(leader == null ? "-" : leader.toString(), number(health.get("term")), head, views);
    }

    @Override
    public long head() throws CpException {
        var config = send("GET", "/cp/v1/config", null);
        this.lock.lock();
        try {
            if (config.get("version") instanceof Long v) {
                this.headVersion = v;
                this.headYaml = String.valueOf(config.get("yaml"));
            } else {
                this.headVersion = 0;
                this.headYaml = "";
            }
            return this.headVersion;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public ConfigBundle config(long version) throws CpException {
        var reply = send("GET", "/cp/v1/config/" + version, null);
        return CanonicalYaml.sections(number(reply.get("version")), String.valueOf(reply.get("yaml")));
    }

    private String yaml() {
        this.lock.lock();
        try {
            return this.headYaml;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Read-only sections are checked here (the edit could not be sent); the document itself goes to
     * {@code POST /cp/v1/config:validate}, the CP's dry run of propose.
     */
    @Override
    public Validation validate(ConfigBundle draft) throws CpException {
        var errors = new ArrayList<String>(readOnlyChanges(draft));
        var warnings = new ArrayList<String>();
        if (errors.isEmpty()) {
            var reply = send("POST", "/cp/v1/config:validate",
                    CanonicalYaml.withRules(yaml(), draft.entries(Section.ACCESS)));
            if (reply.get("errors") instanceof List<?> list) {
                list.forEach(e -> errors.add(String.valueOf(e)));
            }
        }
        var dup = FakeCpClient.check(new ConfigBundle(0, Map.of(Section.ACCESS, draft.entries(Section.ACCESS),
                Section.ROUTES, List.of("x -> y"))));
        warnings.addAll(dup.warnings());
        return new Validation(errors, warnings);
    }

    private List<String> readOnlyChanges(ConfigBundle draft) throws CpException {
        head();
        var current = CanonicalYaml.sections(this.headVersion, yaml());
        var out = new ArrayList<String>();
        for (var s : Section.values()) {
            if (s != CanonicalYaml.EDITABLE && !current.entries(s).equals(draft.entries(s))) {
                out.add(s.name().toLowerCase(java.util.Locale.ROOT)
                        + ": read-only over the admin API (only access rules can be edited)");
            }
        }
        return out;
    }

    @Override
    public long stage(ConfigBundle draft, long baseVersion, String idempotencyKey) throws CpException {
        if (head() != baseVersion) {
            throw new CpException(CpException.Kind.CONFLICT,
                    "head is version " + this.headVersion + ", draft is based on " + baseVersion);
        }
        var readOnly = readOnlyChanges(draft);
        if (!readOnly.isEmpty()) {
            throw new CpException(CpException.Kind.INVALID, String.join("; ", readOnly));
        }
        var reply = send("POST", "/cp/v1/config", CanonicalYaml.withRules(yaml(), draft.entries(Section.ACCESS)),
                idempotencyKey);
        return number(reply.get("version"));
    }

    /**
     * Selector {@code *}: activate the staged proposal everywhere ({@code POST /activate}).
     * Otherwise: open a rollout whose first stage is the selector ({@code POST /rollout}).
     */
    @Override
    public Rollout rollout(long version, String selector, String idempotencyKey) throws CpException {
        LabelSelector sel;
        try {
            sel = LabelSelector.parse(selector);
        } catch (IllegalArgumentException e) {
            throw new CpException(CpException.Kind.INVALID, e.getMessage());
        }
        Rollout r;
        if (sel.terms().isEmpty()) {
            var reply = send("POST", "/cp/v1/activate", null, idempotencyKey);
            r = new Rollout("v" + number(reply.get("version")), number(reply.get("version")), "*",
                    Rollout.State.DONE, List.of(), List.of());
        } else {
            send("POST", "/cp/v1/rollout", "{\"stages\":[" + jsonString(sel.toString()) + "]}", idempotencyKey);
            r = new Rollout("v" + version + "@" + sel, version, sel.toString(), Rollout.State.RUNNING, List.of(),
                    List.of());
        }
        this.lock.lock();
        try {
            this.lastRollout = r;
        } finally {
            this.lock.unlock();
        }
        return r;
    }

    /**
     * {@code GET /cp/v1/rollout}: the stage index and selectors. A rollout this client activated is
     * DONE; otherwise the stages up to the open one are reported as reached.
     */
    @Override
    public Rollout rolloutStatus(String id) throws CpException {
        var reply = send("GET", "/cp/v1/rollout", null);
        Rollout last;
        this.lock.lock();
        try {
            last = this.lastRollout;
        } finally {
            this.lock.unlock();
        }
        if (last != null && last.id().equals(id) && last.state() == Rollout.State.DONE) {
            return last;
        }
        var selectors = new ArrayList<String>();
        if (reply.get("selectors") instanceof List<?> list) {
            list.forEach(x -> selectors.add(String.valueOf(x)));
        }
        if (selectors.isEmpty()) {
            throw new CpException(CpException.Kind.NOT_FOUND, "no rollout is open");
        }
        int reached = (int) Math.min(selectors.size(), Math.max(0, number(reply.get("stage"))) + 1);
        return new Rollout(id, last == null ? 0 : last.version(), selectors.get(reached - 1), Rollout.State.RUNNING,
                selectors, selectors.subList(0, reached));
    }

    @Override
    public long rollback(long toVersion, String idempotencyKey) throws CpException {
        var reply = send("POST", "/cp/v1/rollback", "{\"toVersion\":" + toVersion + "}", idempotencyKey);
        return number(reply.get("version"));
    }

    @Override
    public List<AuditEntry> audit(long afterSeq, int limit) throws CpException {
        var reply = send("GET", "/cp/v1/audit?after=" + Math.max(0, afterSeq) + "&limit=" + Math.max(1, Math.min(100,
                limit)), null);
        var out = new ArrayList<AuditEntry>();
        if (reply.get("entries") instanceof List<?> list) {
            for (var item : list) {
                if (item instanceof Map<?, ?> e) {
                    java.time.Instant time;
                    try {
                        time = java.time.Instant.parse(String.valueOf(e.get("ts")));
                    } catch (java.time.format.DateTimeParseException ex) {
                        time = java.time.Instant.EPOCH;
                    }
                    var detail = e.get("target") + " " + e.get("outcome")
                            + (e.get("version") instanceof Long v && v > 0 ? " v" + v : "");
                    out.add(new AuditEntry(number(e.get("seq")), time, String.valueOf(e.get("actor")),
                            String.valueOf(e.get("action")), detail));
                }
            }
        }
        return out;
    }

    static String jsonString(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
