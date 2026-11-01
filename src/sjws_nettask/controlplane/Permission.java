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
package sjws_nettask.controlplane;

/** Control-plane actions an operator can be allowed to take. */
public enum Permission {
    /** status, config, nodes, audit */
    READ,
    /** create, change or delete routes and access policies (staged, not live) */
    EDIT_ROUTES,
    /** push a config version to nodes */
    ROLLOUT,
    /** re-issue an earlier config version */
    ROLLBACK,
    /** drain or undrain a data-plane node */
    NODE_DRAIN,
    /**
     * Explicitly release stalled per-append STRICT overrides so the group can commit without the
     * missing data copy (#208). Weakens durability for those entries; must be audited.
     */
    RELEASE_STALLED_STRICT,
    /** add, change or revoke operators and their credentials */
    MANAGE_OPERATORS
}
