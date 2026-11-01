package sjws_nettask.h3;

import io.netty.handler.codec.quic.QLogConfiguration;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.quic.QuicChannelOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional qlog for HTTP/3 tests (#367). Enable with {@code -Dsjws.h3.qlog.dir=<dir>}.
 * Off by default so local runs stay quiet; CI sets the property and uploads the dir on failure.
 */
final class H3Qlog {
    private static final AtomicLong SEQ = new AtomicLong();
    private static final String PROP = "sjws.h3.qlog.dir";

    private H3Qlog() {}

    static Optional<Path> dir() {
        var raw = System.getProperty(PROP);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(raw));
    }

    /** A qlog file path under the configured dir, or empty when qlog is off. */
    static Optional<QLogConfiguration> next(String role) {
        return dir().map(root -> {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create " + PROP + " dir " + root, e);
            }
            var file = root.resolve(role + "-" + SEQ.incrementAndGet() + "-" + ProcessHandle.current().pid() + ".qlog");
            return new QLogConfiguration(file.toAbsolutePath().toString(), "sjws-h3-" + role,
                    "HTTP/3 " + role + " qlog for #367 (MAX_STREAM_DATA / STREAM_DATA diagnosis)");
        });
    }

    static final ChannelOption<QLogConfiguration> OPTION = QuicChannelOption.QLOG;
}
