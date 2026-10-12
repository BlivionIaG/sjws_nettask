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

import java.util.ArrayList;
import java.util.List;

/**
 * Path policy stored for the rights-management workstream. It maps onto
 * {@code new AccessRules(deny, rules, allowDotfiles, requireOwner, requireWorldReadable)} once
 * that class is on the classpath: {@code deny} is {@code --deny} / {@code WEB_SERVER_DENY}, and
 * each {@code rules} entry is {@code allow:PATTERN}, {@code deny:PATTERN} or {@code hide:PATTERN}
 * ({@code --access-rule} / {@code WEB_SERVER_ACCESS_RULES}). This record does not enforce anything.
 * <p>
 * Dotfiles are hidden by the file handler today. {@link #withAcmeChallenge()} adds the literal
 * allow rule that workstream needs for {@code /.well-known}, and the ACME handler still answers
 * that prefix before the file handler runs.
 *
 * @param requireOwner user name or numeric uid, or null when the check is off
 */
public record AccessSettings(List<String> deny, List<String> rules, boolean allowDotfiles, String requireOwner,
        boolean requireWorldReadable) {

    /** Literal allow rule for the HTTP-01 prefix. A wildcard allow does not match a dot component. */
    public static final String ACME_CHALLENGE_RULE = "allow:/.well-known/acme-challenge";

    /** Dotfiles hidden, no rules, no owner or mode check. */
    public static final AccessSettings DEFAULT = new AccessSettings(List.of(), List.of(), false, null, false);

    public AccessSettings {
        deny = List.copyOf(deny);
        rules = List.copyOf(rules);
    }

    public boolean isDefault() {
        return this.deny.isEmpty() && this.rules.isEmpty() && !this.allowDotfiles && this.requireOwner == null
                && !this.requireWorldReadable;
    }

    /** Appends {@link #ACME_CHALLENGE_RULE} when it is not already present. */
    public AccessSettings withAcmeChallenge() {
        if (this.rules.contains(ACME_CHALLENGE_RULE)) {
            return this;
        }
        var copy = new ArrayList<String>(this.rules.size() + 1);
        copy.addAll(this.rules);
        copy.add(ACME_CHALLENGE_RULE);
        return new AccessSettings(this.deny, copy, this.allowDotfiles, this.requireOwner, this.requireWorldReadable);
    }
}
