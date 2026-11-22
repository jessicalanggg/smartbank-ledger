package com.smartbank.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs ledger seals with an Ed25519 key that the database never holds.
 *
 * The hash chain alone shows that history changed, unless someone with full access
 * to the database rewrote every seal from the change onwards. With each seal's hash
 * signed by a key kept in the application's configuration (or a secrets manager),
 * a rewritten chain cannot carry valid signatures, and anyone holding the public key
 * can check it.
 *
 * Keys are configured as base64: the private key in PKCS#8 DER, the public keys in
 * X.509 DER (`openssl genpkey -algorithm ed25519`, then `openssl pkey -outform DER`
 * and `openssl pkey -pubout -outform DER`). To rotate, sign with a new key and list
 * the old public key under previous-public-keys, so older seals still verify. Each
 * seal records the id of the key that signed it: the first 16 hex digits of the
 * SHA-256 of the public key.
 */
@Component
public class SealSigner {
    private static final Logger log = LoggerFactory.getLogger(SealSigner.class);
    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey signingKey;
    private final String signingKeyId;
    private final Map<String, PublicKey> verificationKeys = new LinkedHashMap<>();

    public record Signed(String keyId, String signature) { }

    public record PublishedKey(String keyId, String algorithm, String publicKey, boolean current) { }

    public SealSigner(@Value("${smartbank.ledger.seal-signing.private-key:}") String privateKey,
                      @Value("${smartbank.ledger.seal-signing.public-key:}") String publicKey,
                      @Value("${smartbank.ledger.seal-signing.previous-public-keys:}") List<String> previousPublicKeys) {
        try {
            KeyFactory keys = KeyFactory.getInstance(ALGORITHM);
            if (privateKey.isBlank() != publicKey.isBlank())
                throw new IllegalArgumentException("Set both seal-signing.private-key and seal-signing.public-key, or neither");
            if (!privateKey.isBlank()) {
                signingKey = keys.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.trim())));
                PublicKey current = keys.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())));
                signingKeyId = keyId(current);
                verificationKeys.put(signingKeyId, current);
                byte[] probe = "smartbank-seal-key-check".getBytes(StandardCharsets.UTF_8);
                if (!verify(current, probe, sign(probe)))
                    throw new IllegalArgumentException("seal-signing.public-key does not match seal-signing.private-key");
            } else {
                signingKey = null;
                signingKeyId = null;
                log.warn("Ledger seals are not signed: set smartbank.ledger.seal-signing.private-key and public-key");
            }
            for (String previous : previousPublicKeys == null ? List.<String>of() : previousPublicKeys) {
                if (previous.isBlank()) continue;
                PublicKey key = keys.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(previous.trim())));
                verificationKeys.putIfAbsent(keyId(key), key);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid ledger seal signing keys: " + e.getMessage(), e);
        }
    }

    /** A signer that signs nothing and knows no keys, for verification outside Spring. */
    public static SealSigner disabled() {
        return new SealSigner("", "", List.of());
    }

    public boolean enabled() {
        return signingKey != null;
    }

    /** Signs a seal's number and hash with the current key. */
    Signed sign(long seq, String hash) {
        return new Signed(signingKeyId, Base64.getEncoder().encodeToString(sign(message(seq, hash))));
    }

    enum Check { VALID, INVALID, UNKNOWN_KEY }

    /** Checks a seal's signature against the configured public keys. */
    Check verify(long seq, String hash, String keyId, String signature) {
        PublicKey key = verificationKeys.get(keyId);
        if (key == null) return Check.UNKNOWN_KEY;
        try {
            return verify(key, message(seq, hash), Base64.getDecoder().decode(signature)) ? Check.VALID : Check.INVALID;
        } catch (IllegalArgumentException e) {
            return Check.INVALID;
        }
    }

    /** The public keys seals are checked with; safe to publish. */
    public List<PublishedKey> publicKeys() {
        List<PublishedKey> published = new ArrayList<>();
        verificationKeys.forEach((id, key) -> published.add(new PublishedKey(id, ALGORITHM,
                Base64.getEncoder().encodeToString(key.getEncoded()), id.equals(signingKeyId))));
        return published;
    }

    /** What is signed: a fixed prefix, the seal's number and its hash. */
    static byte[] message(long seq, String hash) {
        return ("smartbank-ledger-seal:v1:" + seq + ":" + hash).getBytes(StandardCharsets.UTF_8);
    }

    static String keyId(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] sign(byte[] message) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(signingKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
