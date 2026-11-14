// In-JVM 2-stream dry check for LEAN_CONSUMER=0 admit.
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Lean0AdmitTest {
  public static void main(String[] args) throws Exception {
    Path jar = Path.of(System.getProperty("lean0.jar", "/workspace/bench-502/sjws_nettask-0.2-SNAPSHOT.jar"));
    if (!Files.isRegularFile(jar)) throw new IllegalStateException("jar not found: " + jar);
    Path lib = jar.getParent().resolve("lib");
    Path dir = Files.createTempDirectory(Path.of("/workspace/bench-502"), "lean0-admit-");
    Path site = dir.resolve("site");
    Files.createDirectories(site);
    Files.writeString(site.resolve("hello.txt"), "hi\n");
    Path swarm = dir.resolve("swarm");
    Files.createDirectories(swarm);
    Path tokens = dir.resolve("tokens");
    String t0 = java.util.HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(24));
    String t1 = java.util.HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(24));
    Files.writeString(tokens, "a0 " + t0 + " *\na1 " + t1 + " *\n");
    var ownerRW = java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    var ownerRWX = java.util.EnumSet.copyOf(ownerRW);
    ownerRWX.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    Files.setPosixFilePermissions(tokens, ownerRW);
    Files.setPosixFilePermissions(dir, ownerRWX);
    Path yaml = dir.resolve("server.yaml");
    Files.writeString(yaml, "server:\n  max_connections: 64\n  max_connections_per_ip: 64\n");
    int port = freePort();
    String java = Optional.ofNullable(System.getProperty("java.home")).orElse("") + "/bin/java";
    if (!Files.isExecutable(Path.of(java))) java = "java";
    List<String> cmd = new ArrayList<>();
    cmd.add(java);
    cmd.addAll(List.of(
        "-Xms256m", "-Xmx256m",
        "-Dsjws.swarm.enabled=true", "-Dsjws.swarm.stream.enabled=true",
        "-Dsjws.swarm.stream.credit.enabled=true",
        "-Dsjws.swarm.pool.shards=1", "-Dsjws.swarm.stream.shards=1",
        "-Dsjws.swarm.dir=" + swarm,
        "-Dsjws.swarm.node=lean0-test",
        "-Dsjws.swarm.tokens.file=" + tokens,
        "-Dsjws.messaging.segment.bytes=1048576",
        "-jar", jar.toString(),
        "--config=" + yaml,
        "--metrics=localhost",
        String.valueOf(port),
        site.toString()));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    Path slog = dir.resolve("server.log");
    pb.redirectOutput(slog.toFile());
    pb.redirectErrorStream(true);
    Process server = pb.start();
    try {
      waitHello(port, server, slog);
      StreamLoad.Result lean0 = runWave(port, tokens, dir.resolve("lean0.json"), false);
      StreamLoad.Result lean1 = runWave(port, tokens, dir.resolve("lean1.json"), true);
      System.out.println("=== lean0 ===");
      dump(lean0);
      System.out.println("=== lean1 ===");
      dump(lean1);
      int rc = 0;
      if (lean0.schedSent == 0 || lean0.bytes == 0) {
        System.err.println("FAIL: lean0 sent 0 (bytes=" + lean0.bytes + " sent=" + lean0.schedSent
            + " errors=" + lean0.errorCount + " 503=" + lean0.http503Responses + ")");
        rc = 1;
      }
      if (lean1.schedSent == 0 || lean1.bytes == 0) {
        System.err.println("FAIL: lean1 sent 0");
        rc = 1;
      }
      if (rc == 0) System.out.println("OK lean0-admit 2-stream dry check: lean0 sent=" + lean0.schedSent
          + " bytes=" + lean0.bytes + " lean1 sent=" + lean1.schedSent);
      System.exit(rc);
    } finally {
      server.destroy();
      server.waitFor(5, TimeUnit.SECONDS);
      if (server.isAlive()) server.destroyForcibly();
    }
  }

  static StreamLoad.Result runWave(int port, Path tokens, Path out, boolean lean) throws Exception {
    List<String> a = new ArrayList<>();
    a.addAll(List.of(
        "--base", "http://127.0.0.1:" + port,
        "--tokens-file", tokens.toString(),
        "--out", out.toString(),
        "--accept", "binary",
        "--streams", "2",
        "--groups", "2",
        "--secs", "2",
        "--warmup", "1",
        "--drain-timeout", "5",
        "--chunk-bytes", "1024"));
    if (lean) a.add("--lean-consumer");
    else a.add("--no-lean-consumer");
    StreamLoad.Args args = StreamLoad.parse(a.toArray(new String[0]));
    List<String> toks = StreamLoad.loadTokens(args.tokensFile);
    StreamLoad.Result r = StreamLoad.run(args, toks);
    Files.writeString(out, r.toJson());
    return r;
  }

  static void dump(StreamLoad.Result r) {
    System.out.println("  sent=" + r.schedSent + " recv=" + r.schedReceived + " bytes=" + r.bytes
        + " mib=" + r.mibS + " err=" + r.errorCount
        + " 503_resp=" + r.http503Responses + " 503_giveups=" + r.groupOpenRefusals
        + " 503_budget=" + r.http503BudgetExhausted
        + " cutoff=" + r.cutoffHit + " wall=" + r.wallS);
    System.out.println("  first_admit_warmup (" + (r.firstAdmitWarmup == null ? 0 : r.firstAdmitWarmup.size()) + "):");
    if (r.firstAdmitWarmup != null) for (String s : r.firstAdmitWarmup) System.out.println("    " + s);
    System.out.println("  first_admit_measure (" + (r.firstAdmit == null ? 0 : r.firstAdmit.size()) + "):");
    if (r.firstAdmit != null) for (String s : r.firstAdmit) System.out.println("    " + s);
    if (r.errors != null) for (String s : r.errors) System.out.println("    err: " + s);
  }

  static int freePort() throws Exception {
    try (var s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      return s.getLocalPort();
    }
  }

  static void waitHello(int port, Process server, Path slog) throws Exception {
    for (int i = 0; i < 100; i++) {
      if (!server.isAlive()) {
        System.err.println("server died:\n" + Files.readString(slog));
        throw new IllegalStateException("server exited " + server.exitValue());
      }
      try {
        var u = java.net.URI.create("http://127.0.0.1:" + port + "/hello.txt").toURL();
        try (var in = u.openStream()) { in.readAllBytes(); return; }
      } catch (Exception ignored) { Thread.sleep(50); }
    }
    System.err.println("server log:\n" + (Files.exists(slog) ? Files.readString(slog) : "(none)"));
    throw new IllegalStateException("server did not start");
  }
}
