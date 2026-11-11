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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import sjws_nettask.observe.ClusterStatus;

/**
 * Line-oriented admin console: each command (a letter + Enter) redraws the screen. Plain ANSI
 * escapes, no terminal raw mode and no dependency, so it works over SSH, in tmux and in tests.
 */
public final class AdminTui {

    static final String CLEAR = "\u001b[H\u001b[2J";
    static final String BOLD = "\u001b[1m";
    static final String GREEN = "\u001b[32m";
    static final String RED = "\u001b[31m";
    static final String YELLOW = "\u001b[33m";
    static final String RESET = "\u001b[0m";
    static final int LOG_LINES = 15;
    static final int FILE_TAIL_BYTES = 256 * 1024;
    static final int QUEUE_PAGE = 20;

    enum View { STATUS, CONFIG, LOGS, QUEUES }

    private final ServerControl control;
    private final LogBuffer log;
    private final Path logFile;
    private final boolean ansi;
    private View view = View.STATUS;
    private String message = "";
    private String dlqTitle = "";
    private java.util.List<QueueView.Entry> dlqPage = java.util.List.of();

    /** @param logFile extra file shown in the logs view (e.g. redirected server output), or null */
    public AdminTui(ServerControl control, LogBuffer log, Path logFile, boolean ansi) {
        this.control = control;
        this.log = log;
        this.logFile = logFile;
        this.ansi = ansi;
    }

    /** Reads commands until {@code q} or end of input. */
    public void run(BufferedReader in, PrintStream out) throws IOException {
        out.print(render());
        out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            if (!handle(line)) {
                break;
            }
            out.print(render());
            out.flush();
        }
    }

    /** @return false when the admin asked to quit */
    boolean handle(String line) {
        var cmd = line.trim().toLowerCase(Locale.ROOT);
        this.message = "";
        try {
            if (cmd.startsWith("d ")) {
                deadLetters(line.trim().substring(2).trim());
                return true;
            }
            switch (cmd) {
                case "", "u" -> this.message = "refreshed";
                case "1", "v" -> this.view = View.STATUS;
                case "2", "c" -> this.view = View.CONFIG;
                case "3", "l" -> this.view = View.LOGS;
                case "4", "m" -> this.view = View.QUEUES;
                case "s" -> act("start", this.control::start);
                case "x" -> act("stop", this.control::stop);
                case "r" -> act("reload", this.control::reload);
                case "q" -> {
                    return false;
                }
                default -> this.message = "unknown command '" + line.trim() + "' (h for help)";
            }
            if (cmd.equals("h") || cmd.equals("?")) {
                this.message = "commands: s start, x stop, r reload, v/c/l/m status/config/logs/queues,"
                        + " d <topic> <group> [from] dead letters, Enter refresh, q quit";
            }
        } catch (ServerControl.ControlException e) {
            this.message = color(RED, "error: " + printable(String.valueOf(e.getMessage())));
        }
        return true;
    }

    private interface Action {
        void run() throws ServerControl.ControlException;
    }

    private void act(String name, Action action) throws ServerControl.ControlException {
        if (!this.control.controllable()) {
            this.message = name + " is not available for this server";
            return;
        }
        action.run();
        this.message = name + ": ok";
    }

    String render() {
        var status = this.control.status();
        var sb = new StringBuilder();
        if (this.ansi) {
            sb.append(CLEAR);
        }
        sb.append(color(BOLD, "sjws admin")).append("  [")
                .append(tab(View.STATUS, "v status")).append(" | ")
                .append(tab(View.CONFIG, "c config")).append(" | ")
                .append(tab(View.LOGS, "l logs")).append(" | ")
                .append(tab(View.QUEUES, "m queues")).append("]\n");
        sb.append("State: ").append(stateText(status.state()));
        if (status.state() == ServerStatus.State.RUNNING) {
            sb.append("  up ").append(formatUptime(status.uptime()));
        }
        sb.append('\n');
        switch (this.view) {
            case STATUS -> renderStatus(sb, status);
            case CONFIG -> status.config().forEach((k, v) -> sb.append(String.format("  %-22s %s%n", k, v)));
            case LOGS -> renderLogs(sb);
            case QUEUES -> renderQueues(sb);
        }
        sb.append('\n');
        if (!this.message.isEmpty()) {
            sb.append(this.message).append('\n');
        }
        sb.append("[s]tart [x] stop [r]eload [h]elp [q]uit > ");
        return sb.toString();
    }

    private void renderStatus(StringBuilder sb, ServerStatus status) {
        sb.append("  ").append(status.summary()).append('\n');
        sb.append("  HTTP/1.1 tcp port : ").append(port(status.tcpPort())).append('\n');
        sb.append("  HTTP/3 udp port   : ").append(port(status.udpPort())).append('\n');
        sb.append("  Connections       : ").append(status.connections() < 0 ? "-" : status.connections()).append('\n');
        var root = status.config().get("root");
        if (root != null) {
            sb.append("  Document root     : ").append(root).append('\n');
        }
        var cluster = this.control.clusterStatus();
        if (cluster != null) {
            renderCluster(sb, cluster);
        }
        sb.append("Recent events:\n");
        this.log.tail(5).forEach(l -> sb.append("  ").append(l).append('\n'));
    }

    private void renderCluster(StringBuilder sb, ClusterStatus cluster) {
        sb.append(String.format(Locale.ROOT, "Cluster desired %d: %.1f req/s  p99 %s  errors %d  drifted %d%n",
                cluster.desiredVersion(), cluster.requestsPerSecond(), latency(cluster.p99Nanos()), cluster.errors(),
                cluster.driftedNodes()));
        for (var node : cluster.nodes()) {
            sb.append("  node ").append(node.nodeId()).append(" applied ").append(node.appliedVersion());
            if (node.drifted()) {
                sb.append(" DRIFT");
            }
            if (!node.alive()) {
                sb.append(" down");
            }
            if (node.draining()) {
                sb.append(" draining");
            }
            if (!node.ready()) {
                sb.append(" not-ready");
            }
            sb.append(String.format(Locale.ROOT, "  %.1f req/s  errors %d  p99 %s%n", node.requestsPerSecond(),
                    node.errors(), latency(node.p99Nanos())));
        }
        for (var route : cluster.routes()) {
            sb.append(String.format(Locale.ROOT, "  route %s  requests %d  errors %d  p99 %s%n", route.name(),
                    route.requests(), route.errors(), latency(route.p99Nanos())));
        }
        for (var key : cluster.apiKeys()) {
            sb.append(String.format(Locale.ROOT, "  api-key %s  requests %d  errors %d  p99 %s%n", key.name(),
                    key.requests(), key.errors(), latency(key.p99Nanos())));
        }
    }

    private void deadLetters(String args) throws ServerControl.ControlException {
        var parts = args.split("\\s+");
        if (parts.length < 2 || parts.length > 3) {
            this.message = "usage: d <topic> <group> [fromOffset]";
            return;
        }
        long from = 0;
        if (parts.length == 3) {
            try {
                from = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                this.message = "fromOffset must be a number";
                return;
            }
        }
        this.dlqPage = this.control.deadLetters(parts[0], parts[1], from, QUEUE_PAGE);
        this.dlqTitle = printable(parts[0]) + " / " + printable(parts[1]) + " from " + from;
        this.view = View.QUEUES;
        this.message = this.dlqPage.size() == QUEUE_PAGE
                ? "next page: d " + printable(parts[0]) + " " + printable(parts[1]) + " "
                        + (this.dlqPage.get(QUEUE_PAGE - 1).offset() + 1)
                : "dead letters: " + this.dlqPage.size();
    }

    private void renderQueues(StringBuilder sb) {
        var queues = this.control.queues();
        if (queues.isEmpty()) {
            sb.append("  messaging is not enabled or has no consumer groups\n");
        } else {
            renderQueueTable(sb, queues);
        }
        if (!this.dlqTitle.isEmpty()) {
            sb.append("Dead letters ").append(this.dlqTitle).append(" (payloads not shown):\n");
            for (var e : this.dlqPage) {
                sb.append(String.format(Locale.ROOT, "  @%d attempts %d size %d consumer %s reason %s%n", e.offset(),
                        e.attempts(), e.size(), e.consumer() == null || e.consumer().isBlank() ? "-" : printable(e.consumer()),
                        e.reason() == null || e.reason().isBlank() ? "-" : printable(e.reason())));
            }
        }
    }

    private static void renderQueueTable(StringBuilder sb, java.util.List<QueueView> queues) {
        sb.append(String.format(Locale.ROOT, "  %-20s %-16s %9s %9s %9s %10s%n", "topic", "group", "pending",
                "in-flight", "dlq", "oldest"));
        for (var q : queues) {
            sb.append(String.format(Locale.ROOT, "  %-20s %-16s %9d %9d %9d %10s%s%n", printable(q.topic()),
                    printable(q.group()),
                    q.pending(), q.inFlight(), q.deadLettered(),
                    q.oldestPendingAgeMs() < 0 ? "-" : formatUptime(Duration.ofMillis(q.oldestPendingAgeMs())),
                    q.truncated() ? " (lists truncated)" : ""));
        }
    }

    /** Names and reasons come from clients: control characters become '?', and long values are cut. */
    /**
     * C0 and C1 controls, bidi formatting (U+061C, U+200E/F, U+202A-E, U+2066-9) and the line and
     * paragraph separators U+2028/9: anything that could recolour, split or reorder the screen.
     */
    static boolean unsafe(char c) {
        return c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '\u061c' || c == '\u200e' || c == '\u200f'
                || (c >= '\u2028' && c <= '\u202e') || (c >= '\u2066' && c <= '\u2069');
    }

    static String printable(String s) {
        var sb = new StringBuilder(Math.min(s.length(), 120));
        for (int i = 0; i < s.length() && sb.length() < 120; i++) {
            char c = s.charAt(i);
            sb.append(unsafe(c) ? '?' : c);
        }
        if (s.length() > 120) {
            sb.append("...");
        }
        return sb.toString();
    }

    private static String latency(long nanos) {
        if (nanos <= 0) {
            return "-";
        }
        if (nanos == Long.MAX_VALUE) {
            return "+Inf";
        }
        return String.format(Locale.ROOT, "%.3f s", nanos / 1_000_000_000.0);
    }

    private void renderLogs(StringBuilder sb) {
        sb.append("Admin events:\n");
        this.log.tail(LOG_LINES).forEach(l -> sb.append("  ").append(l).append('\n'));
        if (this.logFile != null) {
            sb.append("Tail of ").append(this.logFile).append(":\n");
            try {
                LogBuffer.tailFile(this.logFile, LOG_LINES, FILE_TAIL_BYTES)
                        .forEach(l -> sb.append("  ").append(l).append('\n'));
            } catch (IOException e) {
                sb.append("  ").append(color(YELLOW, "cannot read: " + e.getMessage())).append('\n');
            }
        }
    }

    private String tab(View v, String label) {
        return this.view == v ? color(BOLD, label.toUpperCase(Locale.ROOT)) : label;
    }

    private String stateText(ServerStatus.State state) {
        return switch (state) {
            case RUNNING -> color(GREEN, "RUNNING");
            case STOPPED -> color(YELLOW, "STOPPED");
            case FAILED -> color(RED, "FAILED");
        };
    }

    private String color(String code, String text) {
        return this.ansi ? code + text + RESET : text;
    }

    private static String port(int p) {
        return p < 0 ? "-" : Integer.toString(p);
    }

    static String formatUptime(Duration d) {
        long s = d.toSeconds();
        return s >= 86_400 ? String.format("%dd %02d:%02d:%02d", s / 86_400, s / 3600 % 24, s / 60 % 60, s % 60)
                : String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    View view() {
        return this.view;
    }

    String message() {
        return this.message;
    }
}
