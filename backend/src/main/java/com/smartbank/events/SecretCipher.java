package com.smartbank.events;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts webhook signing secrets at rest with AES-256-GCM. The key is derived
 * (SHA-256) from {@code smartbank.webhooks.encryption-key}, which must be at least
 * 32 characters and kept out of the database. Each ciphertext is bound to its
 * endpoint id as associated data, so a ciphertext copied onto another endpoint's
 * row fails to decrypt. Stored as {@code v1:<base64(nonce | ciphertext | tag)>};
 * the version prefix leaves room for rotating the encryption key later.
 */
@Component
public class SecretCipher {
    private static final String VERSION = "v1:";
    private static final int NONCE_BYTES = 12, TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${smartbank.webhooks.encryption-key}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.length() < 32)
            throw new IllegalArgumentException("smartbank.webhooks.encryption-key (WEBHOOK_ENCRYPTION_KEY) must be at least 32 characters");
        try {
            byte[] material = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(material, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String plaintext, String associatedData) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    public String decrypt(String stored, String associatedData) {
        if (stored == null || !stored.startsWith(VERSION))
            throw new IllegalStateException("Unknown secret format");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt secret: wrong key, or the data was altered", e);
        }
    }
}
