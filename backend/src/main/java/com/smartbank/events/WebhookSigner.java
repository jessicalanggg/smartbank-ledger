package com.smartbank.events;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Signature header: {@code SmartBank-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256>},
 * where the HMAC is computed with the endpoint's secret over {@code "<t>.<raw body>"}.
 * Receivers recompute it, compare in constant time, and reject old timestamps to
 * prevent replays.
 */
public final class WebhookSigner {
    public static final String SIGNATURE_HEADER = "SmartBank-Signature";
    public static final String EVENT_ID_HEADER = "SmartBank-Event-Id";
    public static final String DELIVERY_ID_HEADER = "SmartBank-Delivery-Id";
    public static final String ATTEMPT_HEADER = "SmartBank-Delivery-Attempt";

    private WebhookSigner() { }

    public static String signatureHeader(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    public static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
