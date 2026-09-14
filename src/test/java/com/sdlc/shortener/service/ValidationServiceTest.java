package com.sdlc.shortener.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationServiceTest {

    private static InetAddress ip(String dotted) {
        try {
            String[] parts = dotted.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                bytes[i] = (byte) Integer.parseInt(parts[i]);
            }
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    /** A resolver backed by a fixed hostname -> IP map, so tests never touch DNS. */
    private ValidationService withResolvedIps(Map<String, String> hostToIp) {
        return new ValidationService(host -> {
            String resolved = hostToIp.get(host);
            return resolved == null ? new InetAddress[0] : new InetAddress[]{ip(resolved)};
        }, "short.example");
    }

    // ---- scheme allowlist -------------------------------------------------------

    @Test
    void acceptsHttpAndHttps() {
        ValidationService v = withResolvedIps(Map.of("example.com", "93.184.216.34"));
        assertThat(v.validateUrl("https://example.com/page").valid()).isTrue();
        assertThat(v.validateUrl("http://example.com/page").valid()).isTrue();
    }

    @Test
    void rejectsNonHttpSchemes() {
        ValidationService v = withResolvedIps(Map.of());
        assertThat(v.validateUrl("file:///etc/passwd").valid()).isFalse();
        assertThat(v.validateUrl("javascript:alert(1)").valid()).isFalse();
        assertThat(v.validateUrl("data:text/html,<script>1</script>").valid()).isFalse();
        assertThat(v.validateUrl("gopher://example.com").valid()).isFalse();
    }

    // ---- internal-range denylist, by resolved IP, not hostname string -------------------

    @Test
    void rejectsLoopback() {
        ValidationService v = withResolvedIps(Map.of("evil.example", "127.0.0.1"));
        assertThat(v.validateUrl("http://evil.example/").valid()).isFalse();
    }

    @Test
    void rejectsRfc1918PrivateRanges() {
        ValidationService v = withResolvedIps(Map.of(
                "a.example", "10.0.0.5", "b.example", "172.16.0.5", "c.example", "192.168.1.5"));
        assertThat(v.validateUrl("http://a.example/").valid()).isFalse();
        assertThat(v.validateUrl("http://b.example/").valid()).isFalse();
        assertThat(v.validateUrl("http://c.example/").valid()).isFalse();
    }

    @Test
    void rejectsTheCloudMetadataEndpointSpecifically() {
        ValidationService v = withResolvedIps(Map.of("metadata.evil.example", "169.254.169.254"));
        assertThat(v.validateUrl("http://metadata.evil.example/").valid()).isFalse();
    }

    @Test
    void rejectsOtherLinkLocalAddresses() {
        ValidationService v = withResolvedIps(Map.of("evil.example", "169.254.1.1"));
        assertThat(v.validateUrl("http://evil.example/").valid()).isFalse();
    }

    @Test
    void theCriticalCase_publicHostnameResolvingToAPrivateIpIsRejected() {
        // The whole point of resolving DNS rather than string-matching the
        // hostname: a hostname that LOOKS public but resolves to loopback/internal
        // must still be blocked (DNS rebinding / attacker-controlled DNS).
        ValidationService v = withResolvedIps(Map.of("looks-public.com", "127.0.0.1"));
        assertThat(v.validateUrl("http://looks-public.com/").valid()).isFalse();
    }

    @Test
    void acceptsAGenuinelyPublicAddress() {
        ValidationService v = withResolvedIps(Map.of("example.com", "93.184.216.34"));
        assertThat(v.validateUrl("http://example.com/").valid()).isTrue();
    }

    @Test
    void rejectsAnUnresolvableHost() {
        ValidationService v = withResolvedIps(Map.of()); // resolver returns empty
        assertThat(v.validateUrl("http://no-such-host.invalid/").valid()).isFalse();
    }

    // ---- self-shortening prevention -----------------------------------------------------

    @Test
    void rejectsSelfShortening() {
        ValidationService v = withResolvedIps(Map.of("short.example", "93.184.216.34"));
        assertThat(v.validateUrl("https://short.example/abc123").valid()).isFalse();
    }

    // ---- reserved aliases -----------------------------------------------------------------

    @Test
    void rejectsReservedAliases() {
        ValidationService v = withResolvedIps(Map.of());
        assertThat(v.validateAlias("api").valid()).isFalse();
        assertThat(v.validateAlias("admin").valid()).isFalse();
        assertThat(v.validateAlias("health").valid()).isFalse();
        assertThat(v.validateAlias("static").valid()).isFalse();
    }

    @Test
    void acceptsANonReservedAlias() {
        ValidationService v = withResolvedIps(Map.of());
        assertThat(v.validateAlias("my-campaign").valid()).isTrue();
    }
}
