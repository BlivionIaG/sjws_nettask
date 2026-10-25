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
package sjws_nettask.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Refused: writing the batch to the log failed and the batch was rolled back completely, so nothing of it is in the
 * log and it is safe to retry (#254). The storage error is the cause. If the rollback fails as well, the batch fails
 * with {@link OutcomeUnknownException} instead and the group fails closed.
 */
public final class BatchRolledBackException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    BatchRolledBackException(GroupId groupId, long first, RuntimeException failed) {
        super(groupId.value() + ": log write failed at index " + first + " or later; the batch was rolled back and"
                + " nothing of it is in the log", ioCause(failed));
        if (failed instanceof UncheckedIOException) {
            addSuppressed(failed); // keeps the original stack; its IOException is the cause
        }
    }

    private static IOException ioCause(RuntimeException failed) {
        return failed instanceof UncheckedIOException io ? io.getCause() : new IOException("log write failed", failed);
    }
}
