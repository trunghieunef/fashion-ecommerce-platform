package vn.fashion.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxyClientIpTest {
  @TempDir Path directory;

  @Test
  void invalidForwardedIpv4CannotBeResolvedByDns() throws Exception {
    Path hosts = directory.resolve("hosts");
    Files.writeString(hosts, "127.0.0.1 trusted-gateway.test\n10.9.9.9 999.1.1.1 300.1.1.1\n");
    // Fork so the JDK resolver is configured before InetAddress initialises; no public DNS.
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djdk.net.hosts.file=" + hosts, "-cp", System.getProperty("java.class.path"),
        DnsProbe.class.getName()).redirectErrorStream(true).start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertThat(finished).isTrue();
    assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isZero();
  }

  @Test
  void socketZoneIsRemovedButForwardedZonesAreRejected() {
    var resolver = new ProxyClientIp("fe80::1");
    assertThat(assertDoesNotThrow(() -> resolver.resolve("fe80::1%eth0", "203.0.113.7"))).isEqualTo("203.0.113.7");
    assertThat(assertDoesNotThrow(() -> resolver.resolve("fe80::1%eth0", "2001:db8::7%eth0")))
        .isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @Test
  void onlyTrustedPeersCanSupplyCanonicalLiteralAddresses() {
    var resolver = new ProxyClientIp("127.0.0.1");
    assertThat(resolver.resolve("127.0.0.1", "0.0.0.0")).isEqualTo("0.0.0.0");
    assertThat(resolver.resolve("127.0.0.1", "255.255.255.255")).isEqualTo("255.255.255.255");
    assertThat(resolver.resolve("127.0.0.1", "2001:db8::7")).isEqualTo("2001:db8:0:0:0:0:0:7");
    assertThat(resolver.resolve("127.0.0.2", "203.0.113.7")).isEqualTo("127.0.0.2");
    for (String value : new String[]{"localhost", "127.1", "0127.0.0.1", "203.0.113.7,203.0.113.8", "::ffff:999.1.1.1"}) {
      assertThat(resolver.resolve("127.0.0.1", value)).isEqualTo("127.0.0.1");
    }
    assertThat(new ProxyClientIp("").resolve("127.0.0.1", "203.0.113.7")).isEqualTo("127.0.0.1");
  }

  public static class DnsProbe {
    public static void main(String[] args) {
      var resolver = new ProxyClientIp("trusted-gateway.test");
      for (String value : new String[]{"999.1.1.1", "300.1.1.1"}) {
        String result = resolver.resolve("127.0.0.1", value);
        if (!result.equals("127.0.0.1")) throw new AssertionError("Forwarded hostname was resolved: " + result);
      }
      if (!resolver.resolve("127.0.0.1", "203.0.113.7").equals("203.0.113.7")) {
        throw new AssertionError("Configured proxy DNS should still work");
      }
    }
  }
}
