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
package sjws_nettask.swarm;

/**
 * S2c PR 8 (N2): scrape view of the stream counters the §13 exit gate needs. Constructed only on the
 * credit-on path so {@link StreamBudget} / {@link StreamCredit} stay unloaded when credit is off
 * ({@code ClassLoadTest}). Served at {@code GET /swarm/v1/stream-stats} and as a {@code /metrics}
 * appendix when the swarm module attaches one.
 */
final class StreamStats {

    private StreamStats() {
    }

    /**
     * Prometheus text for the credit-on counters. Names are stable for the gate; "credit stall" is
     * reported as {@code sjws_swarm_stream_credit_refusals} (session-scope 429s) until inceptor names
     * a distinct stall counter (see /workspace/s2c-pr8/questions.md Q3).
     */
    static String prometheus(StreamCredit credit) {
        var b = credit.budget;
        var sb = new StringBuilder(512);
        gauge(sb, "sjws_swarm_stream_credit_enabled", 1);
        gauge(sb, "sjws_swarm_stream_budget_bytes", b.budgetBytes());
        gauge(sb, "sjws_swarm_stream_charged_bytes", b.charged());
        gauge(sb, "sjws_swarm_stream_peak_charged_bytes", b.peakCharged());
        gauge(sb, "sjws_swarm_stream_open_sessions", b.openSessions());
        gauge(sb, "sjws_swarm_stream_node_refusals", b.nodeRefusals());
        gauge(sb, "sjws_swarm_stream_session_admit_refusals", b.sessionAdmitRefusals());
        gauge(sb, "sjws_swarm_stream_budget_underflows", b.budgetUnderflows());
        gauge(sb, "sjws_swarm_stream_pressure_trim_chunks", b.pressureTrimChunks());
        gauge(sb, "sjws_swarm_stream_pressure_trim_bytes", b.pressureTrimBytes());
        gauge(sb, "sjws_swarm_stream_gap_tickets", b.gapTicketsOutstanding());
        gauge(sb, "sjws_swarm_stream_last_tickets", b.lastTicketsOutstanding());
        gauge(sb, "sjws_swarm_stream_credit_refusals", credit.refusals());
        gauge(sb, "sjws_swarm_stream_credit_unknown", credit.unknown());
        gauge(sb, "sjws_swarm_stream_reserve_underflows", credit.reserveUnderflows());
        return sb.toString();
    }

    /** Credit-off scrape: no budget/credit classes touched. */
    static String prometheusCreditOff(long sinkDrops) {
        var sb = new StringBuilder(128);
        gauge(sb, "sjws_swarm_stream_credit_enabled", 0);
        gauge(sb, "sjws_swarm_stream_sink_drops", sinkDrops);
        return sb.toString();
    }

    private static void gauge(StringBuilder sb, String name, long value) {
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
