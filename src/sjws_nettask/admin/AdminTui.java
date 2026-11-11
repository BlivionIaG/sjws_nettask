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
import java.util.concurrent.TimeoutException;
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

    enum View { STATUS, CONFIG, LOGS, METRICS, CLUSTER }

    /** How long the Cluster view waits for a cluster status before showing it as unavailable. */
    static final Duration CLUSTER_TIMEOUT = Duration.ofSeconds(2);

    private final ServerControl control;
    private final LogBuffer log;
    private final Path logFile;
    private final boolean ansi;
    private View view = View.STATUS;
    private String message = "";
    private String selectedNode;
    private Duration clusterTimeout = CLUSTER_TIMEOUT;

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

    /** Test hook: a shorter wait for the cluster status. */
    void clusterTimeout(Duration timeout) {
        this.clusterTimeout = timeout;
    }

    /** @return false when the admin asked to quit */
    boolean handle(String line) {
        var cmd = line.trim().toLowerCase(Locale.ROOT);
        this.message = "";
        if (cmd.equals("node") || cmd.startsWith("node ")) {
            var id = line.trim().substring(4).trim();
            this.selectedNode = id.isEmpty() ? null : id;
            this.view = View.CLUSTER;
            this.message = id.isEmpty() ? "node selection cleared" : "node " + id;
            return true;
        }
        try {
            if (cmd.equals("drain confirm")) {
                var op = this.control.drain();
                this.message = op.id() + ": " + op.state().name().toLowerCase(Locale.ROOT);
                return true;
            }
            switch (cmd) {
                case "", "u" -> this.message = "refreshed";
                case "1", "v" -> this.view = View.STATUS;
                case "2", "c" -> this.view = View.CONFIG;
                case "3", "l" -> this.view = View.LOGS;
                case "5", "g" -> this.view = View.METRICS;
                case "6", "k" -> this.view = View.CLUSTER;
                case "s" -> act("start", this.control::start);
                case "x" -> act("stop", this.control::stop);
                case "r" -> act("reload", this.control::reload);
                case "n" -> this.message = "drain stops accepting and closes the server once in-flight work ends;"
                        + " type 'drain confirm' to start";
                case "q" -> {
                    return false;
                }
                default -> this.message = "unknown command '" + line.trim() + "' (h for help)";
            }
            if (cmd.equals("h") || cmd.equals("?")) {
                this.message = "commands: s start, x stop, r reload, n drain, v/c/l/g/k status/config/logs/metrics/cluster,"
                        + " node <id> node detail, Enter refresh, q quit";
            }
        } catch (ServerControl.ControlException e) {
            this.message = color(RED, "error: " + e.getMessage());
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
                .append(tab(View.METRICS, "g metrics")).append(" | ")
                .append(tab(View.CLUSTER, "k cluster")).append("]\n");
        sb.append("State: ").append(stateText(status.state()));
        if (status.state() == ServerStatus.State.RUNNING) {
            sb.append("  up ").append(formatUptime(status.uptime()));
        }
        sb.append('\n');
        switch (this.view) {
            case STATUS -> renderStatus(sb, status);
            case CONFIG -> status.config().forEach((k, v) -> sb.append(String.format("  %-22s %s%n", k, v)));
            case LOGS -> renderLogs(sb);
            case METRICS -> renderMetrics(sb);
            case CLUSTER -> renderClusterView(sb);
        }
        sb.append('\n');
        if (!this.message.isEmpty()) {
            sb.append(this.message).append('\n');
        }
        sb.append("[s]tart [x] stop [r]eload [n] drain [h]elp [q]uit > ");
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
        var drain = this.control.drainStatus();
        if (drain != null) {
            sb.append("  Drain             : ").append(drain.id()).append(' ').append(drainText(drain.state()));
            if (drain.state() == DrainOperation.State.RUNNING) {
                sb.append(" since ").append(drain.startedAt());
            } else if (drain.error() != null) {
                sb.append(" (").append(drain.error()).append(')');
            }
            sb.append('\n');
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

    private void renderMetrics(StringBuilder sb) {
        var report = this.control.metrics();
        if (report == null) {
            sb.append("  metrics are not available for this server\n");
            return;
        }
        int reporting = report.nodes().size();
        var nodes = String.format(Locale.ROOT, "Nodes reporting %d/%d", reporting, report.nodesTotal());
        sb.append(reporting < report.nodesTotal() ? color(YELLOW, nodes + " (missing nodes are not in the totals)")
                : nodes).append('\n');
        if (reporting == 0) {
            return;
        }
        if (reporting > 1) {
            sb.append(color(BOLD, "Aggregate")).append('\n');
            sb.append(MetricsView.render(MetricsView.aggregate(
                    report.nodes().stream().map(MetricsView.Node::snapshot).toList())));
        }
        for (var node : report.nodes()) {
            sb.append(color(BOLD, "Node " + node.nodeId())).append('\n');
            sb.append(MetricsView.render(node.snapshot()));
        }
    }

    private void renderClusterView(StringBuilder sb) {
        ClusterStatus cluster;
        try {
            cluster = ClusterView.fetch(this.control::clusterStatus, this.clusterTimeout);
        } catch (TimeoutException e) {
            sb.append(color(RED, "  cluster status unavailable: no answer within " + this.clusterTimeout.toMillis()
                    + " ms")).append('\n');
            return;
        } catch (IllegalStateException e) {
            sb.append(color(RED, "  cluster status unavailable: " + e.getMessage())).append('\n');
            return;
        }
        if (cluster == null) {
            sb.append("  no cluster status attached to this server\n");
            return;
        }
        var nodes = ClusterView.nodes(cluster);
        long up = nodes.stream().filter(n -> n.health() == ClusterView.Health.UP).count();
        long down = nodes.stream().filter(n -> n.health() == ClusterView.Health.DOWN).count();
        sb.append(String.format(Locale.ROOT, "Cluster desired %d: %d nodes, %d up, %d degraded, %d down%n",
                cluster.desiredVersion(), nodes.size(), up, nodes.size() - up - down, down));
        sb.append(String.format(Locale.ROOT, "  %-16s %-9s %8s %9s %10s %7s  %s%n", "NODE", "HEALTH", "APPLIED",
                "REQ/S", "P99", "ERRORS", "CHECKS"));
        ClusterView.NodeHealth selected = null;
        for (var n : nodes) {
            var node = n.node();
            if (node.nodeId().equals(this.selectedNode)) {
                selected = n;
            }
            sb.append(String.format(Locale.ROOT, "  %-16s %s %8d %9.1f %10s %7d  %s%n", node.nodeId(),
                    healthText(n.health()), node.appliedVersion(), node.requestsPerSecond(), latency(node.p99Nanos()),
                    node.errors(), n.summary()));
        }
        if (this.selectedNode == null) {
            sb.append("  node <id> shows one node's checks\n");
        } else if (selected == null) {
            sb.append("  node ").append(this.selectedNode).append(" is not in the cluster status\n");
        } else {
            sb.append(color(BOLD, "Node " + this.selectedNode)).append(": ").append(healthText(selected.health()))
                    .append('\n');
            for (var check : selected.checks()) {
                sb.append(check.ok() ? "  [ok]   " : "  [FAIL] ").append(check.name());
                if (!check.ok()) {
                    sb.append(": ").append(check.detail());
                }
                sb.append('\n');
            }
            sb.append(String.format(Locale.ROOT, "  requests %d  errors %d%n", selected.node().requests(),
                    selected.node().errors()));
        }
    }

    private String healthText(ClusterView.Health health) {
        var text = String.format(Locale.ROOT, "%-9s", health);
        return switch (health) {
            case UP -> color(GREEN, text);
            case DEGRADED -> color(YELLOW, text);
            case DOWN -> color(RED, text);
        };
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

    private String drainText(DrainOperation.State state) {
        return switch (state) {
            case RUNNING -> color(YELLOW, "DRAINING");
            case DONE -> color(GREEN, "DRAINED");
            case FAILED -> color(RED, "FAILED");
        };
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
