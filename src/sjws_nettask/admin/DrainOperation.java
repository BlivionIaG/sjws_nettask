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
package sjws_nettask.admin;

import java.time.Instant;

/**
 * A drain started from the admin console (#381). {@code id} names the operation in the log and on
 * screen; a second drain request while one runs returns the same operation.
 *
 * @param finishedAt null while {@link State#RUNNING}
 * @param error      null unless {@link State#FAILED}
 */
public record DrainOperation(String id, State state, Instant startedAt, Instant finishedAt, String error) {

    public enum State { RUNNING, DONE, FAILED }

    DrainOperation finish(State end, Instant at, String why) {
        return new DrainOperation(this.id, end, this.startedAt, at, why);
    }
}
