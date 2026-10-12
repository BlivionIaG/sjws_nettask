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

    enum View { STATUS, CONFIG, LOGS }

    private final ServerControl control;
    private final LogBuffer log;
    private final Path logFile;
    private final boolean ansi;
    private View view = View.STATUS;
    private String message = "";

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
            switch (cmd) {
                case "", "u" -> this.message = "refreshed";
                case "1", "v" -> this.view = View.STATUS;
                case "2", "c" -> this.view = View.CONFIG;
                case "3", "l" -> this.view = View.LOGS;
                case "s" -> act("start", this.control::start);
                case "x" -> act("stop", this.control::stop);
                case "r" -> act("reload", this.control::reload);
                case "q" -> {
                    return false;
                }
                default -> this.message = "unknown command '" + line.trim() + "' (h for help)";
            }
            if (cmd.equals("h") || cmd.equals("?")) {
                this.message = "commands: s start, x stop, r reload, v/c/l status/config/logs, Enter refresh, q quit";
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
                .append(tab(View.LOGS, "l logs")).append("]\n");
        sb.append("State: ").append(stateText(status.state()));
        if (status.state() == ServerStatus.State.RUNNING) {
            sb.append("  up ").append(formatUptime(status.uptime()));
        }
        sb.append('\n');
        switch (this.view) {
            case STATUS -> renderStatus(sb, status);
            case CONFIG -> status.config().forEach((k, v) -> sb.append(String.format("  %-22s %s%n", k, v)));
            case LOGS -> renderLogs(sb);
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
        sb.append("Recent events:\n");
        this.log.tail(5).forEach(l -> sb.append("  ").append(l).append('\n'));
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
