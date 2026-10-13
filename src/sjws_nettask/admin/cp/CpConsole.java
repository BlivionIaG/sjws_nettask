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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import sjws_nettask.admin.cp.CpClient.ConfigBundle;
import sjws_nettask.admin.cp.CpClient.Section;

/**
 * Control-plane console: view model plus a line-oriented ANSI renderer, the same pattern as
 * {@code AdminTui} (a command + Enter redraws). The draft is edited locally; nothing reaches the
 * control plane until {@code apply}, which validates, stages against the draft's base version
 * and rolls out to a label selector.
 */
public final class CpConsole {

    static final String CLEAR = "\u001b[H\u001b[2J";
    static final String BOLD = "\u001b[1m";
    static final String GREEN = "\u001b[32m";
    static final String RED = "\u001b[31m";
    static final String YELLOW = "\u001b[33m";
    static final String RESET = "\u001b[0m";
    static final int AUDIT_LINES = 20;

    enum View { NODES, CONFIG, DIFF, VALIDATE, AUDIT }

    private final CpClient client;
    private final boolean ansi;
    private final Supplier<String> keys;
    private View view = View.NODES;
    private Section section = Section.ROUTES;
    private ConfigBundle base;
    private ConfigBundle draft;
    private CpClient.Validation validation;
    private CpClient.Rollout lastRollout;
    private String message = "";

    public CpConsole(CpClient client, boolean ansi) {
        this(client, ansi, () -> UUID.randomUUID().toString());
    }

    CpConsole(CpClient client, boolean ansi, Supplier<String> idempotencyKeys) {
        this.client = client;
        this.ansi = ansi;
        this.keys = idempotencyKeys;
    }

    // ---------- view model ----------

    /** Loads the head version as base and draft (discarding an unsaved draft). */
    public void reset() throws CpException {
        this.base = this.client.config(this.client.head());
        this.draft = this.base.withVersion(0);
        this.validation = null;
    }

    public boolean dirty() {
        return this.draft != null && !this.draft.sections().equals(this.base.sections());
    }

    public void add(Section s, String entry) {
        var list = new ArrayList<>(this.draft.entries(s));
        list.add(entry.trim());
        edited(s, list);
    }

    /** @param index 1-based, as displayed */
    public void remove(Section s, int index) {
        var list = new ArrayList<>(this.draft.entries(s));
        if (index < 1 || index > list.size()) {
            throw new IllegalArgumentException(s.name().toLowerCase(Locale.ROOT) + " has no entry " + index);
        }
        list.remove(index - 1);
        edited(s, list);
    }

    /** Moves entry {@code index} one place up (ordered sections: routes, filters). */
    public void moveUp(Section s, int index) {
        var list = new ArrayList<>(this.draft.entries(s));
        if (index < 2 || index > list.size()) {
            throw new IllegalArgumentException("cannot move entry " + index + " up");
        }
        list.add(index - 2, list.remove(index - 1));
        edited(s, list);
    }

    private void edited(Section s, List<String> list) {
        this.draft = this.draft.with(s, list);
        this.validation = null;
    }

    public List<String> diff() {
        return ConfigDiff.diff(this.base, this.draft);
    }

    public CpClient.Validation validate() throws CpException {
        this.validation = this.client.validate(this.draft);
        return this.validation;
    }

    /**
     * Validate, stage on the base version, roll out to {@code selector}. On CONFLICT the draft
     * is kept: {@code reset} and re-apply the edits.
     */
    public CpClient.Rollout apply(String selector) throws CpException {
        LabelSelector.parse(selector); // fail before any remote write
        if (!dirty()) {
            throw new IllegalStateException("nothing to apply");
        }
        if (!validate().ok()) {
            throw new IllegalStateException("validation failed: " + this.validation.errors().size() + " error(s)");
        }
        long version = this.client.stage(this.draft, this.base.version(), this.keys.get());
        try {
            this.lastRollout = this.client.rollout(version, selector, this.keys.get());
        } catch (CpException e) {
            reset(); // the version exists now: base the console on it so 'p' can roll it out
            throw new CpException(e.kind(), "version " + version + " staged but not rolled out ("
                    + e.getMessage() + "); p <selector> to roll it out", e.leaderHint());
        }
        reset();
        return this.lastRollout;
    }

    /** Widens a finished canary rollout of the base version to another selector. */
    public CpClient.Rollout promote(String selector) throws CpException {
        LabelSelector.parse(selector);
        this.lastRollout = this.client.rollout(this.base.version(), selector, this.keys.get());
        return this.lastRollout;
    }

    public long rollback(long toVersion) throws CpException {
        long g = this.client.rollback(toVersion, this.keys.get());
        reset();
        return g;
    }

    // ---------- commands ----------

    /** @return false on quit */
    boolean handle(String line) {
        var parts = line.trim().split("\\s+", 3);
        var cmd = parts[0].toLowerCase(Locale.ROOT);
        this.message = "";
        try {
            if (this.draft == null && !cmd.equals("q")) {
                reset();
            }
            switch (cmd) {
                case "", "f" -> {
                    refresh();
                }
                case "n" -> this.view = View.NODES;
                case "e" -> {
                    this.section = parts.length > 1 ? section(parts[1]) : this.section;
                    this.view = View.CONFIG;
                }
                case "+" -> {
                    need(parts, 3, "+ <section> <entry>");
                    add(section(parts[1]), parts[2]);
                    this.section = section(parts[1]);
                    this.view = View.CONFIG;
                }
                case "-", "^" -> {
                    need(parts, 3, cmd + " <section> <n>");
                    var s = section(parts[1]);
                    int n = number(parts[2]);
                    if (cmd.equals("-")) {
                        remove(s, n);
                    } else {
                        moveUp(s, n);
                    }
                    this.section = s;
                    this.view = View.CONFIG;
                }
                case "d" -> this.view = View.DIFF;
                case "v" -> {
                    validate();
                    this.view = View.VALIDATE;
                }
                case "a" -> {
                    need(parts, 2, "a <selector|*>");
                    var r = apply(parts[1]);
                    this.message = color(GREEN, "rollout " + r.id() + " of version " + r.version() + " to "
                            + r.selector() + ": " + r.state() + " " + r.applied().size() + "/" + r.targets().size());
                    this.view = View.NODES;
                }
                case "p" -> {
                    need(parts, 2, "p <selector|*>");
                    var r = promote(parts[1]);
                    this.message = "rollout " + r.id() + " to " + r.selector() + ": " + r.state();
                    this.view = View.NODES;
                }
                case "b" -> {
                    need(parts, 2, "b <version>");
                    this.message = color(YELLOW, "rolled back: version " + rollback(number(parts[1]))
                            + " = content of " + parts[1]);
                    this.view = View.NODES;
                }
                case "x" -> {
                    reset();
                    this.message = "draft discarded";
                }
                case "l" -> this.view = View.AUDIT;
                case "h", "?" -> this.message = "n nodes | e <sec> edit | + <sec> <entry> | - <sec> <n> | ^ <sec> <n> up"
                        + " | d diff | v validate | a <selector> apply | p <selector> promote | b <version> rollback"
                        + " | x discard | l audit | f refresh | q quit;  sections: routes filters access certs topics";
                case "q" -> {
                    return false;
                }
                default -> this.message = "unknown command '" + parts[0] + "' (h for help)";
            }
        } catch (CpException e) {
            this.message = color(RED, e.getMessage()
                    + (e.kind() == CpException.Kind.CONFLICT ? " (x to discard and reload, then redo the edits)" : ""));
        } catch (IllegalArgumentException | IllegalStateException e) {
            this.message = color(RED, e.getMessage());
        }
        return true;
    }

    private void refresh() throws CpException {
        if (this.lastRollout != null) {
            this.lastRollout = this.client.rolloutStatus(this.lastRollout.id());
        }
        if (!dirty()) {
            reset();
        }
        this.message = "refreshed";
    }

    private static void need(String[] parts, int n, String usage) {
        if (parts.length < n) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + s);
        }
    }

    static Section section(String name) {
        for (var s : Section.values()) {
            if (s.name().equalsIgnoreCase(name) || (name.length() >= 2
                    && s.name().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT)))) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown section '" + name + "'");
    }

    public void run(BufferedReader in, PrintStream out) throws IOException {
        out.print(render());
        out.flush();
        String line;
        while ((line = in.readLine()) != null && handle(line)) {
            out.print(render());
            out.flush();
        }
    }

    // ---------- rendering ----------

    String render() {
        var sb = new StringBuilder();
        if (this.ansi) {
            sb.append(CLEAR);
        }
        sb.append(color(BOLD, "sjws control plane")).append("  via ").append(this.client.endpoint());
        if (this.base != null) {
            sb.append("  head version ").append(this.base.version());
        }
        if (dirty()) {
            sb.append(color(YELLOW, "  draft: " + diff().size() + " change(s)"));
        }
        sb.append('\n');
        try {
            switch (this.view) {
                case NODES -> renderNodes(sb);
                case CONFIG -> renderSection(sb);
                case DIFF -> renderDiff(sb);
                case VALIDATE -> renderValidation(sb);
                case AUDIT -> renderAudit(sb);
            }
        } catch (CpException e) {
            sb.append(color(RED, "  " + e.getMessage())).append('\n');
        }
        sb.append('\n');
        if (!this.message.isEmpty()) {
            sb.append(this.message).append('\n');
        }
        sb.append("[n]odes [e]dit [d]iff [v]alidate [a]pply [b] rollback [l] audit [h]elp [q]uit > ");
        return sb.toString();
    }

    private void renderNodes(StringBuilder sb) throws CpException {
        var c = this.client.cluster();
        sb.append("leader ").append(c.leader()).append("  term ").append(c.term()).append("  head version ")
                .append(c.headVersion()).append('\n');
        sb.append(String.format("  %-8s %-9s %-6s %-5s %-4s %s%n", "NODE", "ROLE", "HEALTH", "VER", "LAG", "LABELS"));
        for (var n : c.nodes()) {
            var labels = new java.util.TreeMap<>(n.labels()).toString();
            var cell = String.format("%-6s", n.health());
            var health = n.health().equals("UP") || n.health().equals("READY") ? color(GREEN, cell) : n.health().equals("?") ? cell : color(RED, cell);
            sb.append(String.format("  %-8s %-9s ", n.id(), n.role())).append(health)
                    .append(String.format(" %-5s %-4s %s%n", n.appliedVersion() < 0 ? "?" : n.appliedVersion(),
                            n.lag() < 0 ? "?" : n.lag() == 0 ? "0" : color(YELLOW, Long.toString(n.lag())), labels));
        }
        if (this.lastRollout != null) {
            var r = this.lastRollout;
            sb.append("last rollout ").append(r.id()).append(": version ").append(r.version()).append(" to ")
                    .append(r.selector()).append(' ').append(r.state()).append(' ').append(r.applied().size())
                    .append('/').append(r.targets().size()).append('\n');
        }
    }

    private void renderSection(StringBuilder sb) {
        sb.append("editing ").append(color(BOLD, this.section.name().toLowerCase(Locale.ROOT)))
                .append(ConfigDiff.ordered(this.section) ? " (ordered: first match wins)" : "").append('\n');
        var entries = this.draft.entries(this.section);
        if (entries.isEmpty()) {
            sb.append("  (none)\n");
        }
        for (int i = 0; i < entries.size(); i++) {
            var mark = this.base.entries(this.section).contains(entries.get(i)) ? " " : color(GREEN, "+");
            sb.append(String.format("%s %2d  %s%n", mark, i + 1, entries.get(i)));
        }
    }

    private void renderDiff(StringBuilder sb) {
        var d = diff();
        sb.append("diff version ").append(this.base.version()).append(" -> draft\n");
        if (d.isEmpty()) {
            sb.append("  (no changes)\n");
        }
        for (var line : d) {
            var code = line.startsWith("+") ? GREEN : line.startsWith("-") ? RED : YELLOW;
            sb.append("  ").append(color(code, line)).append('\n');
        }
    }

    private void renderValidation(StringBuilder sb) {
        var v = this.validation;
        if (v == null) {
            sb.append("  draft changed since the last validation: v to validate\n");
            return;
        }
        sb.append(v.ok() ? color(GREEN, "valid") : color(RED, "invalid")).append(": ").append(v.errors().size())
                .append(" error(s), ").append(v.warnings().size()).append(" warning(s)\n");
        v.errors().forEach(e -> sb.append("  ").append(color(RED, "E " + e)).append('\n'));
        v.warnings().forEach(w -> sb.append("  ").append(color(YELLOW, "W " + w)).append('\n'));
    }

    private void renderAudit(StringBuilder sb) throws CpException {
        var all = this.client.audit(0, 100);
        sb.append("audit log (last ").append(AUDIT_LINES).append(")\n");
        for (var a : all.subList(Math.max(0, all.size() - AUDIT_LINES), all.size())) {
            sb.append(String.format("  %4d %s %-8s %-12s %s%n", a.seq(), a.time(), a.actor(), a.action(), a.detail()));
        }
        if (this.client instanceof FailoverCpClient f && !f.events().isEmpty()) {
            sb.append("endpoint switches:\n");
            f.events().forEach(e -> sb.append("  ").append(e).append('\n'));
        }
    }

    private String color(String code, String text) {
        return this.ansi ? code + text + RESET : text;
    }

    View view() {
        return this.view;
    }

    String message() {
        return this.message;
    }

    ConfigBundle draft() {
        return this.draft;
    }
}
