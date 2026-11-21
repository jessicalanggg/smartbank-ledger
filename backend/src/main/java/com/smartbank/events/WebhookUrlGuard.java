package com.smartbank.events;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.Locale;

/**
 * Protects against server-side request forgery (SSRF) through webhook URLs. Without
 * it, a user could register http://169.254.169.254/… (cloud metadata) or
 * http://localhost:5432 and make SmartBank send requests into its own network.
 *
 * A URL must be absolute https (http only if allowed), without credentials, and
 * every address its host resolves to must be public: not loopback, private,
 * link-local, carrier-grade NAT, unique-local, multicast or unspecified, including
 * IPv4 addresses embedded in IPv6 (mapped, compatible, 6to4, NAT64).
 *
 * The check runs at registration and again before every delivery, so a hostname
 * that later starts resolving to an internal address (DNS rebinding) is caught. A
 * small window remains between this check and the HTTP client's own lookup; closing
 * it fully needs an egress proxy or connection-level IP pinning.
 */
@Component
public class WebhookUrlGuard {
    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private final boolean allowHttp;
    private final boolean allowPrivateTargets;
    private final Resolver resolver;

    @Autowired
    public WebhookUrlGuard(@Value("${smartbank.webhooks.allow-http:false}") boolean allowHttp,
                           @Value("${smartbank.webhooks.allow-private-targets:false}") boolean allowPrivateTargets) {
        this(allowHttp, allowPrivateTargets, InetAddress::getAllByName);
    }

    public WebhookUrlGuard(boolean allowHttp, boolean allowPrivateTargets, Resolver resolver) {
        this.allowHttp = allowHttp;
        this.allowPrivateTargets = allowPrivateTargets;
        this.resolver = resolver;
    }

    public static class BlockedUrlException extends IllegalArgumentException {
        public BlockedUrlException(String message) { super(message); }
    }

    /** Validates a URL at registration; returns it normalized. */
    public URI validate(String url) {
        if (url == null || url.isBlank() || url.length() > 2048)
            throw new BlockedUrlException("A URL of at most 2048 characters is required");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new BlockedUrlException("Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || (allowHttp && scheme.equals("http"))))
            throw new BlockedUrlException(allowHttp ? "URL must be http or https" : "URL must be https");
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw new BlockedUrlException("URL must have a host");
        if (uri.getRawUserInfo() != null)
            throw new BlockedUrlException("URL must not contain credentials");
        checkResolved(uri);
        return uri.normalize();
    }

    /** Re-checks where the host resolves now; called before every delivery. */
    public void checkResolved(URI uri) {
        if (allowPrivateTargets) return;
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new BlockedUrlException("Host does not resolve: " + host);
        }
        if (addresses.length == 0) throw new BlockedUrlException("Host does not resolve: " + host);
        for (InetAddress address : addresses)
            if (!isPublic(address))
                throw new BlockedUrlException("URL resolves to a non-public address (" + address.getHostAddress() + ")");
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress())
            return false;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) return isPublicV4(b);
        // IPv6: unique-local fc00::/7, deprecated site-local fec0::/10 (covered above), and embedded IPv4.
        if ((b[0] & 0xfe) == 0xfc) return false;
        if (isZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff)  // ::ffff:a.b.c.d (mapped)
            return isPublicV4(new byte[] { b[12], b[13], b[14], b[15] });
        if (isZero(b, 0, 12))                                                     // ::a.b.c.d (compatible)
            return isPublicV4(new byte[] { b[12], b[13], b[14], b[15] });
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02)                        // 2002:a.b.c.d::/16 (6to4)
            return isPublicV4(new byte[] { b[2], b[3], b[4], b[5] });
        if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && isZero(b, 4, 12))                                               // 64:ff9b::a.b.c.d (NAT64)
            return isPublicV4(new byte[] { b[12], b[13], b[14], b[15] });
        return true;
    }

    private static boolean isPublicV4(byte[] b) {
        int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
        if (a0 == 0 || a0 == 10 || a0 == 127) return false;                 // this network, private, loopback
        if (a0 == 169 && a1 == 254) return false;                           // link-local, cloud metadata
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;                // private
        if (a0 == 192 && a1 == 168) return false;                           // private
        if (a0 == 100 && a1 >= 64 && a1 <= 127) return false;               // carrier-grade NAT
        if (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0) return false;       // IETF protocol assignments
        if (a0 == 198 && (a1 == 18 || a1 == 19)) return false;              // benchmarking
        if (a0 >= 224) return false;                                        // multicast and reserved
        return true;
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }
}
