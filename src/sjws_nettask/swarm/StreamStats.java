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
 * S2c PR 8 (N2): Prometheus appendix for the stream-gate counters. Built only on the credit-on path so
 * {@link StreamBudget} / {@link StreamCredit} stay unloaded when credit is off ({@code ClassLoadTest}).
 * Attached to {@code /metrics} only; names follow sjws Observability's review (counters get {@code _total},
 * outstanding tickets are gauges, flag-off emits nothing).
 */
final class StreamStats {

    private StreamStats() {
    }

    /**
     * Credit-on scrape. Metric names are under {@code sjws_swarm_stream_*} so Observability can rename
     * them in one place.
     */
    static String prometheus(StreamCredit credit, long sinkDrops) {
        var b = credit.budget;
        var sb = new StringBuilder(1024);
        gauge(sb, "sjws_swarm_stream_credit_enabled", "1 when stream credit is on", 1);
        gauge(sb, "sjws_swarm_stream_budget_bytes", "configured node stream-memory budget in bytes", b.budgetBytes());
        gauge(sb, "sjws_swarm_stream_charged_bytes", "bytes currently charged to the node stream budget", b.charged());
        gauge(sb, "sjws_swarm_stream_peak_charged_bytes", "since start", b.peakCharged());
        gauge(sb, "sjws_swarm_stream_open_sessions", "sessions holding a LAST ticket", b.openSessions());
        gauge(sb, "sjws_swarm_stream_gap_tickets_outstanding", "gap tickets currently outstanding",
                b.gapTicketsOutstanding());
        gauge(sb, "sjws_swarm_stream_last_tickets_outstanding", "LAST tickets currently outstanding",
                b.lastTicketsOutstanding());
        counter(sb, "sjws_swarm_stream_node_refusals_total", "non-LAST chunks answered 429 with scope=node",
                b.nodeRefusals());
        counter(sb, "sjws_swarm_stream_session_admit_refusals_total",
                "new sessions answered 503 at the admit watermark", b.sessionAdmitRefusals());
        counter(sb, "sjws_swarm_stream_budget_underflows_total",
                "releases that would have taken charged below 0 (bug counter)", b.budgetUnderflows());
        counter(sb, "sjws_swarm_stream_pressure_trim_chunks_total", "chunks trimmed under node pressure",
                b.pressureTrimChunks());
        counter(sb, "sjws_swarm_stream_pressure_trim_bytes_total", "bytes trimmed under node pressure",
                b.pressureTrimBytes());
        counter(sb, "sjws_swarm_stream_credit_refusals_total", "session-scope 429s (credit window stalls)",
                credit.refusals());
        counter(sb, "sjws_swarm_stream_credit_unknown_total",
                "non-LAST chunks answered 204 because the session is not on this node", credit.unknown());
        counter(sb, "sjws_swarm_stream_reserve_underflows_total",
                "releases that would have taken a reservation below 0 (bug counter)", credit.reserveUnderflows());
        counter(sb, "sjws_swarm_stream_sink_drops_total", "chunks dropped at the sink (overflow / full)", sinkDrops);
        return sb.toString();
    }

    /** Credit-off: emit nothing (AGENTS.md efficiency rule; Observability review). */
    static String prometheusCreditOff() {
        return "";
    }

    private static void gauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }
}
