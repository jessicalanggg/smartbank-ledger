package com.smartbank.ledger;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Signed seals: a rewrite of the whole hash chain by someone with full database
 * access, which the chain alone cannot detect, fails because the attacker cannot sign.
 */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = "smartbank.ledger.seal-interval=PT1H")
@AutoConfigureMockMvc
class SealSignatureTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    static final KeyPair BANK_KEY = generate();

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("smartbank.ledger.seal-signing.private-key", () -> encode(BANK_KEY.getPrivate().getEncoded()));
        registry.add("smartbank.ledger.seal-signing.public-key", () -> encode(BANK_KEY.getPublic().getEncoded()));
    }

    @Autowired LedgerSealer sealer;
    @Autowired SealSigner signer;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired BankingService banking;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;

    private UUID a, b;

    @BeforeEach
    void freshBooks() {
        LedgerTestData.truncateLedger(jdbc);
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
        a = LedgerTestData.openAccount(jdbc, "SIG-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "CAD");
        b = LedgerTestData.openAccount(jdbc, "SIG-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal("100.00"), "CAD");
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static SealSigner signerFor(KeyPair key, KeyPair... previous) {
        return new SealSigner(encode(key.getPrivate().getEncoded()), encode(key.getPublic().getEncoded()),
                java.util.Arrays.stream(previous).map(p -> encode(p.getPublic().getEncoded())).toList());
    }

    private UUID transfer(String amount) {
        return banking.transfer(new TransferRequest(a, b, new BigDecimal(amount), "sig-" + UUID.randomUUID())).id();
    }

    private void tamper(String... statements) {
        jdbc.execute("ALTER TABLE banking_transactions DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE ledger_seals DISABLE TRIGGER USER");
        try {
            for (String sql : statements) jdbc.execute(sql);
        } finally {
            jdbc.execute("ALTER TABLE banking_transactions ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE ledger_seals ENABLE TRIGGER USER");
        }
    }

    /** What an attacker with full database access does: change a transaction, then recompute every seal. */
    private void rewriteChain(UUID tx, java.util.function.BiFunction<Long, String, String[]> signWith) {
        tamper("UPDATE banking_transactions SET request_key = 'forged' WHERE id = '" + tx + "'");
        String prev = LedgerSealer.GENESIS;
        for (long seq = 1; seq <= sealer.head().seq(); seq++) {
            List<UUID> ids = jdbc.queryForList("SELECT transaction_id FROM ledger_sealed_transactions s "
                    + "JOIN banking_transactions t ON t.id = s.transaction_id WHERE seal_seq = ? ORDER BY t.created_at, t.id",
                    UUID.class, seq);
            String hash = LedgerSealer.sealHash(seq, prev, sealer.canonical(ids));
            String[] signature = signWith.apply(seq, hash);
            jdbc.update("ALTER TABLE ledger_seals DISABLE TRIGGER USER");
            try {
                jdbc.update("UPDATE ledger_seals SET prev_hash = ?, hash = ?, key_id = ?, signature = ? WHERE seq = ?",
                        prev, hash, signature[0], signature[1], seq);
            } finally {
                jdbc.update("ALTER TABLE ledger_seals ENABLE TRIGGER USER");
            }
            prev = hash;
        }
    }

    @Test
    void sealsAreSignedAndAnyoneWithThePublicKeyCanCheckThem() throws Exception {
        transfer("10.00");
        var seal = sealer.sealPending().get(0);
        assertNotNull(seal.signature());
        assertTrue(sealer.verify().intact());

        // An outside auditor needs only the published public key and the seal itself.
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'ADMIN')", admin, admin + "@t.local");
        String keys = mvc.perform(get("/api/v1/ledger/seals/public-keys").with(jwt().jwt(j -> j.subject(admin.toString()))))
                .andReturn().getResponse().getContentAsString();
        assertEquals(seal.keyId(), JsonPath.read(keys, "$[0].keyId"));
        assertEquals(Boolean.TRUE, JsonPath.read(keys, "$[0].current"));
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode((String) JsonPath.read(keys, "$[0].publicKey"))));
        Signature check = Signature.getInstance("Ed25519");
        check.initVerify(publicKey);
        check.update(("smartbank-ledger-seal:v1:" + seal.seq() + ":" + seal.hash()).getBytes());
        assertTrue(check.verify(Base64.getDecoder().decode(seal.signature())));
    }

    @Test
    void aFullRewriteOfTheChainIsCaughtWithoutAnyExternalRecord() {
        UUID tx = transfer("10.00");
        sealer.sealPending();
        transfer("1.00");
        sealer.sealPending();

        // Keeping the old signatures: they no longer match the new hashes.
        var original = jdbc.queryForList("SELECT key_id, signature FROM ledger_seals ORDER BY seq");
        rewriteChain(tx, (seq, hash) -> new String[] {
                (String) original.get((int) (seq - 1)).get("key_id"), (String) original.get((int) (seq - 1)).get("signature") });
        assertEquals("its signature does not match", sealer.verify().problem());

        // Dropping the signatures: the latest seal must be signed.
        rewriteChain(tx, (seq, hash) -> new String[] { null, null });
        assertEquals("the latest seal is not signed", sealer.verify().problem());

        // Signing with the attacker's own key: the bank does not know it.
        SealSigner attacker = signerFor(generate());
        rewriteChain(tx, (seq, hash) -> {
            var signed = attacker.sign(seq, hash);
            return new String[] { signed.keyId(), signed.signature() };
        });
        assertEquals("it is signed with an unknown key", sealer.verify().problem());
        assertFalse(reconciliation.run().isClean());
        LedgerTestData.truncateLedger(jdbc);  // leave clean books for the next test
    }

    @Test
    void switchingSigningOnCoversTheHistorySealedBefore() {
        LedgerSealer unsigned = new LedgerSealer(jdbc, transactions, Clock.systemUTC(), SealSigner.disabled());
        transfer("10.00");
        unsigned.sealPending();
        transfer("2.00");
        unsigned.sealPending();
        assertEquals("the latest seal is not signed", sealer.verify().problem());

        // The signing sealer adds a seal with no transactions that vouches for everything before it.
        var attestation = sealer.sealPending();
        assertEquals(1, attestation.size());
        assertEquals(0, attestation.get(0).transactionCount());
        assertNotNull(attestation.get(0).signature());
        assertTrue(sealer.verify().intact());
        assertTrue(sealer.sealPending().isEmpty(), "Nothing more to seal");
    }

    @Test
    void sealsSignedWithARotatedKeyStillVerify() {
        transfer("10.00");
        sealer.sealPending();  // signed with the bank's current key

        KeyPair next = generate();
        LedgerSealer rotated = new LedgerSealer(jdbc, transactions, Clock.systemUTC(), signerFor(next, BANK_KEY));
        transfer("3.00");
        var newer = rotated.sealPending().get(0);
        assertEquals(SealSigner.keyId(next.getPublic()), newer.keyId());
        assertTrue(rotated.verify().intact(), "Old seals verify with the previous key, new ones with the new key");

        LedgerSealer forgotten = new LedgerSealer(jdbc, transactions, Clock.systemUTC(), signerFor(next));
        assertEquals("it is signed with an unknown key", forgotten.verify().problem());
        LedgerTestData.truncateLedger(jdbc);  // the app's own signer does not know the new key
    }

    @Test
    void mismatchedKeysAreRefusedAtStartup() {
        KeyPair one = generate(), other = generate();
        var e = assertThrows(IllegalStateException.class, () -> new SealSigner(
                encode(one.getPrivate().getEncoded()), encode(other.getPublic().getEncoded()), List.of()));
        assertTrue(e.getMessage().contains("does not match"));
        assertThrows(IllegalStateException.class, () -> new SealSigner(encode(one.getPrivate().getEncoded()), "", List.of()));
    }
}
