package com.smartbank.transaction;

import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.support.LedgerTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Two-phase transfers against real PostgreSQL, through the service and through the secured HTTP API. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class HoldIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired HoldService holds;
    @Autowired HoldExpiryJob expiryJob;
    @Autowired BankingService banking;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID account(String balance) {
        return LedgerTestData.openAccount(jdbc, "HOLD-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
    }

    private HoldResponse hold(UUID from, UUID to, String amount) {
        return holds.create(new CreateHoldRequest(from, to, new BigDecimal(amount), "hold-" + UUID.randomUUID(), null));
    }

    private BigDecimal column(String column, UUID account) {
        return jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private void assertAccount(UUID account, String balance, String pendingDebits, String pendingCredits) {
        assertMoney(balance, column("balance", account));
        assertMoney(pendingDebits, column("pending_debits", account));
        assertMoney(pendingCredits, column("pending_credits", account));
    }

    private long ledgerTransactionsFor(UUID account) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM banking_transactions
                WHERE transaction_type <> 'OPENING_BALANCE' AND ? IN (source_account_id, destination_account_id)""",
                Long.class, account);
    }

    // --- Service behaviour -------------------------------------------------

    @Test
    void holdReservesMoneyWithoutMovingIt() {
        UUID payer = account("100.00"), payee = account("0.00");
        HoldResponse h = hold(payer, payee, "70.00");

        assertEquals(HoldStatus.PENDING, h.status());
        assertAccount(payer, "100.00", "70.00", "0.00");
        assertAccount(payee, "0.00", "0.00", "70.00");
        assertEquals(0, ledgerTransactionsFor(payer), "No ledger posting until the hold is posted");
    }

    @Test
    void holdsReduceAvailableBalanceForEverythingElse() {
        UUID payer = account("100.00"), payee = account("0.00");
        hold(payer, payee, "70.00");

        assertThrows(BankingException.class, () -> hold(payer, payee, "40.00"));
        assertThrows(BankingException.class, () -> banking.transfer(
                new TransferRequest(payer, payee, new BigDecimal("40.00"), "hold-transfer-" + payer)));
        assertThrows(BankingException.class, () -> banking.withdraw(
                payer, new MoneyRequest(new BigDecimal("31.00"), "hold-withdraw-" + payer)));
        banking.withdraw(payer, new MoneyRequest(new BigDecimal("30.00"), "hold-withdraw-ok-" + payer));
        assertAccount(payer, "70.00", "70.00", "0.00");
    }

    @Test
    void postingInFullMovesMoneyThroughTheLedger() {
        UUID payer = account("100.00"), payee = account("0.00");
        HoldResponse posted = holds.post(hold(payer, payee, "70.00").id(), null);

        assertEquals(HoldStatus.POSTED, posted.status());
        assertMoney("70.00", posted.postedAmount());
        assertAccount(payer, "30.00", "0.00", "0.00");
        assertAccount(payee, "70.00", "0.00", "0.00");
        assertEquals("TRANSFER", jdbc.queryForObject(
                "SELECT transaction_type FROM banking_transactions WHERE id = ?", String.class, posted.postedTransactionId()));
    }

    @Test
    void partialPostReleasesTheRemainder() {
        UUID payer = account("100.00"), payee = account("0.00");
        HoldResponse posted = holds.post(hold(payer, payee, "80.00").id(), new BigDecimal("55.25"));

        assertMoney("55.25", posted.postedAmount());
        assertAccount(payer, "44.75", "0.00", "0.00");
        assertAccount(payee, "55.25", "0.00", "0.00");
    }

    @Test
    void postingMoreThanHeldIsRejected() {
        UUID payer = account("100.00"), payee = account("0.00");
        UUID id = hold(payer, payee, "50.00").id();
        assertThrows(BankingException.class, () -> holds.post(id, new BigDecimal("50.01")));
        assertAccount(payer, "100.00", "50.00", "0.00");
    }

    @Test
    void voidReleasesTheReservation() {
        UUID payer = account("100.00"), payee = account("0.00");
        HoldResponse voided = holds.voidHold(hold(payer, payee, "70.00").id());

        assertEquals(HoldStatus.VOIDED, voided.status());
        assertAccount(payer, "100.00", "0.00", "0.00");
        assertAccount(payee, "0.00", "0.00", "0.00");
        assertEquals(0, ledgerTransactionsFor(payer));
    }

    @Test
    void postAndVoidAreIdempotentAndFinal() {
        UUID payer = account("100.00"), payee = account("0.00");
        UUID posted = hold(payer, payee, "40.00").id();
        HoldResponse first = holds.post(posted, new BigDecimal("30.00"));
        HoldResponse again = holds.post(posted, new BigDecimal("30.00"));
        assertEquals(first.postedTransactionId(), again.postedTransactionId());
        assertEquals(1, ledgerTransactionsFor(payer));
        assertThrows(BankingException.class, () -> holds.post(posted, new BigDecimal("40.00")));
        assertThrows(BankingException.class, () -> holds.voidHold(posted));

        UUID voided = hold(payer, payee, "20.00").id();
        holds.voidHold(voided);
        assertEquals(HoldStatus.VOIDED, holds.voidHold(voided).status());
        assertThrows(BankingException.class, () -> holds.post(voided, null));
        assertAccount(payer, "70.00", "0.00", "0.00");
    }

    @Test
    void createIsIdempotentByRequestKey() {
        UUID payer = account("100.00"), payee = account("0.00");
        var request = new CreateHoldRequest(payer, payee, new BigDecimal("25.00"), "hold-key-" + payer, null);
        assertEquals(holds.create(request).id(), holds.create(request).id());
        assertThrows(BankingException.class, () -> holds.create(
                new CreateHoldRequest(payer, payee, new BigDecimal("26.00"), "hold-key-" + payer, null)));
        assertAccount(payer, "100.00", "25.00", "0.00");
    }

    @Test
    void expiryJobReleasesDueHoldsOnly() {
        UUID payer = account("100.00"), payee = account("0.00");
        UUID shortHold = holds.create(new CreateHoldRequest(payer, payee, new BigDecimal("30.00"),
                "hold-short-" + payer, 3_600L)).id();
        UUID longHold = hold(payer, payee, "20.00").id();  // default 7 days

        expiryJob.expireDue(Instant.now().plus(2, ChronoUnit.HOURS));

        assertEquals(HoldStatus.EXPIRED, holds.get(shortHold).status());
        assertEquals(HoldStatus.PENDING, holds.get(longHold).status());
        assertAccount(payer, "100.00", "20.00", "0.00");
        assertThrows(HoldExpiredException.class, () -> holds.post(shortHold, null));
    }

    @Test
    void postingAfterExpiryFailsAndStillReleasesTheHold() throws Exception {
        UUID payer = account("100.00"), payee = account("0.00");
        UUID id = holds.create(new CreateHoldRequest(payer, payee, new BigDecimal("30.00"),
                "hold-late-" + payer, 1L)).id();
        Thread.sleep(1_100);

        assertThrows(HoldExpiredException.class, () -> holds.post(id, null));
        assertEquals(HoldStatus.EXPIRED, holds.get(id).status(), "The expiry is committed, not rolled back");
        assertAccount(payer, "100.00", "0.00", "0.00");
        assertEquals(0, ledgerTransactionsFor(payer));
    }

    // --- Database guarantees -----------------------------------------------

    @Test
    void databaseAllowsOneStateChangeAndNoDeletes() {
        UUID payer = account("100.00"), payee = account("0.00");
        UUID id = hold(payer, payee, "10.00").id();
        holds.voidHold(id);

        assertThrows(Exception.class, () -> jdbc.update("UPDATE holds SET status = 'PENDING', resolved_at = NULL WHERE id = ?", id));
        assertThrows(Exception.class, () -> jdbc.update("DELETE FROM holds WHERE id = ?", id));
        UUID pending = hold(payer, payee, "10.00").id();
        assertThrows(Exception.class, () -> jdbc.update("UPDATE holds SET amount = 1 WHERE id = ?", pending));
        holds.voidHold(pending);
    }

    @Test
    void databaseRejectsNegativeAvailableBalance() {
        UUID payer = account("100.00");
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE accounts SET pending_debits = 100.01 WHERE id = ?", payer));
    }

    // --- HTTP API and authorization ----------------------------------------

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'not-a-real-hash', 'CUSTOMER')",
                id, id + "@test.local");
        return id;
    }

    private UUID ownedAccount(UUID owner, String balance) {
        UUID id = account(balance);
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private static RequestPostProcessor as(UUID user) {
        return jwt().jwt(j -> j.subject(user.toString()));
    }

    private String createViaApi(UUID payerUser, UUID from, UUID to, String amount) throws Exception {
        String body = """
                {"fromAccountId":"%s","toAccountId":"%s","amount":"%s","requestKey":"%s"}"""
                .formatted(from, to, amount, UUID.randomUUID());
        String json = mvc.perform(post("/api/v1/holds").with(as(payerUser))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }

    @Test
    void payerHoldsAndPayeePostsThroughTheApi() throws Exception {
        UUID payer = user(), payee = user(), stranger = user();
        UUID from = ownedAccount(payer, "100.00"), to = ownedAccount(payee, "0.00");

        // Only the owner of the source account can place a hold on it.
        mvc.perform(post("/api/v1/holds").with(as(payee)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"%s","toAccountId":"%s","amount":"10.00","requestKey":"%s"}"""
                                .formatted(from, to, UUID.randomUUID())))
                .andExpect(status().isForbidden());

        String id = createViaApi(payer, from, to, "60.00");
        mvc.perform(get("/api/v1/accounts/{id}", from).with(as(payer)))
                .andExpect(jsonPath("$.balance").value(100.00))
                .andExpect(jsonPath("$.availableBalance").value(40.00));

        // Both parties can see the hold; nobody else can.
        mvc.perform(get("/api/v1/holds/{id}", id).with(as(payee))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/holds/{id}", id).with(as(stranger))).andExpect(status().isForbidden());

        // Only the payee can capture or release it.
        mvc.perform(post("/api/v1/holds/{id}/post", id).with(as(payer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/holds/{id}/void", id).with(as(stranger))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/holds/{id}/post", id).with(as(payee))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"45.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.postedAmount").value(45.00));
        mvc.perform(post("/api/v1/holds/{id}/void", id).with(as(payee))).andExpect(status().isConflict());

        assertAccount(from, "55.00", "0.00", "0.00");
        assertAccount(to, "45.00", "0.00", "0.00");
    }

    @Test
    void holdCanBePlacedByAccountNumber() throws Exception {
        UUID payer = user();
        UUID from = ownedAccount(payer, "100.00"), to = account("0.00");
        String number = jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class, to);
        String body = """
                {"fromAccountId":"%s","toAccountNumber":" %s ","amount":"25.00"}""".formatted(from, number);
        String json = mvc.perform(post("/api/v1/holds/by-account-number").with(as(payer)).header("Idempotency-Key", "by-number-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAccountId").value(to.toString()))
                .andReturn().getResponse().getContentAsString();
        // Retrying replays; an unknown number is a 409, like transfers.
        mvc.perform(post("/api/v1/holds/by-account-number").with(as(payer)).header("Idempotency-Key", "by-number-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(content().string(json));
        mvc.perform(post("/api/v1/holds/by-account-number").with(as(payer)).header("Idempotency-Key", "by-number-2")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromAccountId":"%s","toAccountNumber":"NOPE-1","amount":"1.00"}""".formatted(from)))
                .andExpect(status().isConflict());
        assertAccount(from, "100.00", "25.00", "0.00");
    }

    @Test
    void unknownHoldIs404AndInvalidAmountIs400() throws Exception {
        UUID someone = user();
        mvc.perform(post("/api/v1/holds/{id}/void", UUID.randomUUID()).with(as(someone)))
                .andExpect(status().isNotFound());
        UUID from = ownedAccount(someone, "10.00"), to = account("0.00");
        mvc.perform(post("/api/v1/holds").with(as(someone)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId":"%s","toAccountId":"%s","amount":"1.001","requestKey":"k-%s"}"""
                                .formatted(from, to, from)))
                .andExpect(status().isBadRequest());
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
