package com.smartbank.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Agent API keys: 256 random bits with a recognizable prefix. Only the SHA-256 of a
 * key is stored. A key this random needs no slow password hash: guessing one is
 * infeasible however fast each guess is.
 */
final class AgentKeys {
    static final String PREFIX = "sbk_agent_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentKeys() { }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What is shown of a key after creation, to tell keys apart. */
    static String displayPrefix(String key) {
        return key.substring(0, PREFIX.length() + 6);
    }
}
