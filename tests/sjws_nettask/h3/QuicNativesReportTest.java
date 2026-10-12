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
package sjws_nettask.h3;

import org.junit.jupiter.api.Test;

/**
 * Not tagged "h3", so it always runs: says loudly in the build log whether the QUIC tests ran.
 * Without natives Surefire would otherwise just show the h3 classes as skipped.
 */
class QuicNativesReportTest {

    @Test
    void report() {
        var platform = System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        if (Http3Server.isAvailable()) {
            System.out.println("QUIC natives loaded on " + platform + ": HTTP/3 tests run");
        } else {
            System.err.println("WARNING: QUIC natives unavailable on " + platform + " ("
                    + Http3Server.unavailabilityCause() + "): every HTTP/3 test is SKIPPED. If this platform "
                    + "has natives, set -Dh3.gate.line=0 -Dh3.gate.branch=0 -Dtls.gate.line=0 -Dtls.gate.branch=0 "
                    + "or fix -Dio.netty.native.workdir (noexec /tmp).");
        }
    }
}
