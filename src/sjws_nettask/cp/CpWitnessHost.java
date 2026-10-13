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
package sjws_nettask.cp;

import java.io.IOException;
import sjws_nettask.service.CpReloadable;

/**
 * A witness CONTROL-role process: joins {@code cp-config} as a vote-only voter. No key ring, no store, no
 * watch or admin listener (control-plane-hosting.md §6.1, §8.7). SIGHUP has nothing to reload.
 */
final class CpWitnessHost implements CpReloadable {

    private final CpWitness witness;

    private CpWitnessHost(CpWitness witness) {
        this.witness = witness;
    }

    static CpReloadable start(CpConfig config, CpHostDeps deps) throws IOException {
        var witness = CpWitness.open(config.keys().stateDir(), CpLaunch.nodeId(config.self()), CpLaunch.voters(config),
                deps.clock(), deps.transport(config));
        witness.start();
        return new CpWitnessHost(witness);
    }

    CpWitness witness() {
        return witness;
    }

    @Override
    public String reload() {
        return null;
    }

    @Override
    public void close() {
        witness.close();
    }
}
