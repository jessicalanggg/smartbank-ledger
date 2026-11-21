package com.smartbank.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SSRF rules, without network access: hostnames resolve through a fake resolver. */
class WebhookUrlGuardTest {
    private static final Map<String, String> DNS = Map.of(
            "hooks.example.com", "93.184.216.34",
            "rebinding.example.com", "10.0.0.5",
            "metadata.example.com", "169.254.169.254",
            "localhost", "127.0.0.1");

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        String ip = DNS.get(host);
        if (ip != null) return new InetAddress[] { InetAddress.getByName(ip) };
        if (host.matches("[0-9.]+") || host.contains(":")) return new InetAddress[] { InetAddress.getByName(host) };
        throw new UnknownHostException(host);
    }

    private final WebhookUrlGuard guard = new WebhookUrlGuard(false, false, WebhookUrlGuardTest::resolve);

    @Test
    void publicHttpsUrlIsAccepted() {
        assertEquals("https://hooks.example.com/smartbank", guard.validate("https://hooks.example.com/smartbank").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/hook", "https://127.0.0.1/hook", "https://127.1.2.3/hook",
            "https://10.1.2.3/hook", "https://172.16.0.1/hook", "https://172.31.255.255/hook", "https://192.168.1.1/hook",
            "https://169.254.169.254/latest/meta-data", "https://100.64.0.1/hook", "https://0.0.0.0/hook",
            "https://224.0.0.1/hook", "https://198.18.0.1/hook",
            "https://[::1]/hook", "https://[::]/hook", "https://[fc00::1]/hook", "https://[fd12:3456::1]/hook",
            "https://[fe80::1]/hook", "https://[::ffff:127.0.0.1]/hook", "https://[::ffff:169.254.169.254]/hook",
            "https://[2002:a00:1::]/hook", "https://[64:ff9b::a00:1]/hook",
            "https://rebinding.example.com/hook", "https://metadata.example.com/hook"})
    void internalTargetsAreBlocked(String url) {
        assertThrows(WebhookUrlGuard.BlockedUrlException.class, () -> guard.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.example.com/hook", "ftp://hooks.example.com/hook", "https://user:pass@hooks.example.com/hook",
            "https:///nohost", "not a url", "https://does-not-exist.example.com/hook"})
    void malformedOrUnsafeUrlsAreRejected(String url) {
        assertThrows(WebhookUrlGuard.BlockedUrlException.class, () -> guard.validate(url));
    }

    @Test
    void publicIpv6AndEmbeddedPublicIpv4AreAllowed() {
        assertDoesNotThrow(() -> guard.validate("https://[2606:4700:4700::1111]/hook"));
        assertDoesNotThrow(() -> guard.validate("https://[::ffff:93.184.216.34]/hook"));
    }

    @Test
    void deliveryTimeCheckCatchesAHostThatNowResolvesInternally() {
        // Registered while public; the DNS answer has since changed (DNS rebinding).
        var changed = new WebhookUrlGuard(false, false, host -> new InetAddress[] { InetAddress.getByName("10.0.0.7") });
        assertThrows(WebhookUrlGuard.BlockedUrlException.class,
                () -> changed.checkResolved(java.net.URI.create("https://hooks.example.com/hook")));
    }

    @Test
    void localDevelopmentCanAllowHttpAndPrivateTargets() {
        var dev = new WebhookUrlGuard(true, true, WebhookUrlGuardTest::resolve);
        assertDoesNotThrow(() -> dev.validate("http://localhost:9000/hook"));
    }
}
