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
package sjws_nettask.config;

import sjws_nettask.acme.AcmeChallengeHandler;
import sjws_nettask.acme.ChallengeBoard;
import java.util.ArrayList;
import sjws_nettask.core.AccessRules;
import sjws_nettask.core.FileCache;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;

/**
 * Immutable configuration for the request path: one prebuilt handler, swapped as a whole.
 * With {@code routing.enabled} the handler is a {@code Router} built from {@link #document()}; the
 * middleware list is stored there. Access rules are the merge of the command line / environment
 * ({@code server.access()}) and the YAML {@code access:} section, see {@link #effectiveAccess}. When ACME is on the main listener the challenge handler runs first, so
 * {@code /.well-known/acme-challenge/} is answered before the file handler's dotfile check.
 *
 * @param files the static-file handler (also what {@code SjwsServer.handler()} returns)
 * @param handler what a request runs: {@code files} or a router over it, possibly behind the ACME challenge wrapper
 * @param generation increases by one on every successful reload
 * @param epoch control-plane epoch, {@code 0} for a local snapshot
 */
public record ConfigSnapshot(ConfigDocument document, StaticFileHandler files, Handler handler, int generation,
        long epoch) implements AppliedConfig {

    public ConfigSnapshot {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch");
        }
    }

    public static ConfigSnapshot compile(ConfigDocument document, ChallengeBoard board, int generation) {
        return compile(document, board, generation, 0L);
    }

    public static ConfigSnapshot compile(ConfigDocument document, ChallengeBoard board, int generation, long epoch) {
        var server = document.server();
        var files = new StaticFileHandler(server.root(), server.allowUnlistableDirs(),
                new FileCache(server.cacheBytes()), effectiveAccess(server.access(), document.access()));
        Handler handler = files;
        if (document.routing().enabled()) {
            handler = RoutingCompiler.compile(document.routing(), files);
        }
        var acme = document.acme();
        if (acme.enabled() && acme.onMainListener(server.port())) {
            handler = new AcmeChallengeHandler(board, handler); // challenges are answered before any route
        }
        return new ConfigSnapshot(document, files, handler, generation, epoch);
    }

    /** Cluster version. {@code 0} when the document has no {@code version} key. */
    @Override
    public long version() {
        return document.bundleVersion();
    }

    /** SHA-256 of {@link ConfigCodec#text}, not the raw file bytes. */
    @Override
    public String canonicalHash() {
        return document.canonicalHash();
    }

    public ConfigSnapshot withEpoch(long epoch) {
        return new ConfigSnapshot(document, files, handler, generation, epoch);
    }

    /**
     * Merges command-line / environment access settings with the YAML {@code access:} section.
     * Safe by default: nothing configured in either place is dropped.
     * <ul>
     * <li>deny lists: union (CLI patterns first, then YAML)</li>
     * <li>rules: CLI rules first, then YAML rules; first match wins, so a CLI rule overrides a YAML one</li>
     * <li>allow_dotfiles: on if either source turns it on (an explicit opt-in)</li>
     * <li>require_world_readable: on if either source turns it on (restrictive)</li>
     * <li>require_owner: the CLI value when set, otherwise the YAML value</li>
     * </ul>
     * When the YAML section is default the CLI policy object is returned as is (no extra cost).
     *
     * @throws IllegalArgumentException for a malformed YAML rule or an unknown owner
     */
    public static AccessRules effectiveAccess(AccessRules cli, AccessSettings yaml) {
        if (yaml == null || yaml.isDefault()) {
            return cli;
        }
        var deny = new ArrayList<String>(cli.denyList().size() + yaml.deny().size());
        for (var r : cli.denyList()) {
            deny.add(r.pattern());
        }
        deny.addAll(yaml.deny());
        var rules = new ArrayList<AccessRules.Rule>(cli.rules());
        for (var spec : yaml.rules()) {
            rules.add(AccessRules.Rule.parse(spec));
        }
        String owner = cli.requireOwner() != null ? cli.requireOwner() : yaml.requireOwner();
        return new AccessRules(deny, rules, cli.allowDotfiles() || yaml.allowDotfiles(), owner,
                cli.requireWorldReadable() || yaml.requireWorldReadable());
    }
}
