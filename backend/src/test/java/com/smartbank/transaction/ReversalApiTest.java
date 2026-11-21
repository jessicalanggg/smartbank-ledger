package com.smartbank.transaction;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Reversals through the secured API, against real PostgreSQL, plus the database's own limit check. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReversalApiTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingService banking;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired TransactionTemplate transactionTemplate;

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', ?)", id, id + "@t.local", role);
        return id;
    }

    private UUID account(UUID owner, String balance) {
        UUID id = LedgerTestData.openAccount(jdbc, "REV-" + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal(balance), "CAD");
        if (owner != null) jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private UUID transfer(UUID from, UUID to, String amount) {
        return banking.transfer(new TransferRequest(from, to, new BigDecimal(amount), "rev-" + UUID.randomUUID())).id();
    }

    private MockHttpServletResponse reverse(UUID user, UUID transaction, String amount, String key) throws Exception {
        String body = amount == null ? "{}" : "{\"amount\":\"" + amount + "\"}";
        return mvc.perform(post("/api/v1/transactions/{id}/reversals", transaction)
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .header("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void payeeRefundsATransferInParts() throws Exception {
        UUID customer = user("CUSTOMER"), merchant = user("CUSTOMER");
        UUID wallet = account(customer, "100.00"), shop = account(merchant, "0.00");
        UUID payment = transfer(wallet, shop, "80.00");

        var first = reverse(merchant, payment, "30.00", null);
        assertEquals(201, first.getStatus());
        assertEquals("REVERSAL", JsonPath.read(first.getContentAsString(), "$.type"));
        assertEquals(payment.toString(), JsonPath.read(first.getContentAsString(), "$.reversesTransactionId"));
        assertEquals(shop.toString(), JsonPath.read(first.getContentAsString(), "$.fromAccountId"));
        assertMoney("50.00", balance(wallet));
        assertMoney("50.00", balance(shop));

        assertEquals(409, reverse(merchant, payment, "50.01", null).getStatus(), "Only 50.00 is left to reverse");
        var rest = reverse(merchant, payment, null, null);  // no amount: whatever is left
        assertEquals(201, rest.getStatus());
        assertEquals(50.00, ((Number) JsonPath.read(rest.getContentAsString(), "$.amount")).doubleValue());
        assertEquals(409, reverse(merchant, payment, null, null).getStatus(), "Already fully reversed");
        assertMoney("100.00", balance(wallet));
        assertMoney("0.00", balance(shop));
    }

    @Test
    void onlyThePayeeOrAnAdminCanReverseATransfer() throws Exception {
        UUID customer = user("CUSTOMER"), merchant = user("CUSTOMER"), stranger = user("CUSTOMER"), admin = user("ADMIN");
        UUID wallet = account(customer, "100.00"), shop = account(merchant, "0.00");
        UUID payment = transfer(wallet, shop, "40.00");

        assertEquals(403, reverse(customer, payment, null, null).getStatus(), "The payer cannot pull money back");
        assertEquals(403, reverse(stranger, payment, null, null).getStatus());
        assertEquals(201, reverse(admin, payment, "10.00", null).getStatus());
        assertMoney("70.00", balance(wallet));
    }

    @Test
    void depositsAndWithdrawalsNeedAnAdmin() throws Exception {
        UUID customer = user("CUSTOMER"), admin = user("ADMIN");
        UUID account = account(customer, "100.00");
        UUID deposit = banking.deposit(account, new MoneyRequest(new BigDecimal("25.00"), "rev-d-" + account)).id();
        UUID withdrawal = banking.withdraw(account, new MoneyRequest(new BigDecimal("40.00"), "rev-w-" + account)).id();
        assertMoney("85.00", balance(account));

        // A customer reversing their own withdrawal would create money.
        assertEquals(403, reverse(customer, withdrawal, null, null).getStatus());
        assertEquals(403, reverse(customer, deposit, null, null).getStatus());

        assertEquals(201, reverse(admin, deposit, null, null).getStatus());     // the deposit bounced
        assertMoney("60.00", balance(account));
        assertEquals(201, reverse(admin, withdrawal, null, null).getStatus());  // the withdrawal failed
        assertMoney("100.00", balance(account));
    }

    @Test
    void refundNeedsFundsInThePayeesAccount() throws Exception {
        UUID merchant = user("CUSTOMER");
        UUID wallet = account(null, "100.00"), shop = account(merchant, "0.00"), elsewhere = account(null, "0.00");
        UUID payment = transfer(wallet, shop, "60.00");
        transfer(shop, elsewhere, "50.00");  // the merchant already spent most of it

        var refund = reverse(merchant, payment, null, null);
        assertEquals(409, refund.getStatus());
        assertTrue(refund.getContentAsString().contains("Insufficient funds"));
        assertEquals(201, reverse(merchant, payment, "10.00", null).getStatus());
        assertMoney("50.00", balance(wallet));
    }

    @Test
    void retriesAreIdempotentAndOddTargetsAreRejected() throws Exception {
        UUID admin = user("ADMIN");
        UUID a = account(null, "100.00"), b = account(null, "0.00");
        UUID payment = transfer(a, b, "20.00");

        var first = reverse(admin, payment, "5.00", "rev-key");
        var retry = reverse(admin, payment, "5.00", "rev-key");
        assertEquals(first.getContentAsString(), retry.getContentAsString());
        assertEquals("true", retry.getHeader("Idempotent-Replayed"));
        assertMoney("85.00", balance(a));

        UUID reversalId = UUID.fromString(JsonPath.read(first.getContentAsString(), "$.id"));
        assertEquals(409, reverse(admin, reversalId, null, null).getStatus(), "A reversal cannot be reversed");
        assertEquals(404, reverse(admin, UUID.randomUUID(), null, null).getStatus());
        UUID opening = jdbc.queryForObject("SELECT id FROM banking_transactions WHERE transaction_type = 'OPENING_BALANCE' "
                + "AND destination_account_id = ?", UUID.class, a);
        assertEquals(409, reverse(admin, opening, null, null).getStatus(), "Opening balances cannot be reversed");
    }

    @Test
    void concurrentPartialRefundsNeverExceedTheOriginal() throws Exception {
        // b has plenty of money, so the only limit is "never more than the original 100.00".
        UUID a = account(null, "1000.00"), b = account(null, "1000.00");
        UUID payment = transfer(a, b, "100.00");

        ExecutorService pool = Executors.newFixedThreadPool(16);
        int succeeded = 0;
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 40; i++)
                attempts.add(pool.submit(() -> {
                    gate.await();
                    try {
                        banking.reverse(payment, new ReversalRequest(new BigDecimal("7.00"), "race-" + UUID.randomUUID()));
                        return true;
                    } catch (BankingException e) {
                        return false;
                    }
                }));
            gate.countDown();
            for (var f : attempts) if (f.get(1, TimeUnit.MINUTES)) succeeded++;
        } finally {
            pool.shutdownNow();
        }
        assertEquals(14, succeeded, "14 x 7.00 = 98.00 fits in 100.00; a 15th would not");
        assertMoney("998.00", balance(a));
        assertMoney("1002.00", balance(b));
    }

    @Test
    void databaseRejectsReversalsBeyondTheOriginalEvenWithoutTheService() {
        UUID a = account(null, "100.00"), b = account(null, "0.00");
        UUID payment = transfer(a, b, "10.00");
        // Insert a too-large reversal directly, bypassing every application check.
        Exception e = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id,
                        amount, currency, created_at, reverses_transaction_id)
                    VALUES (?, 'REVERSAL', ?, ?, 10.01, 'CAD', now(), ?)""", id, b, a, payment);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                    VALUES (gen_random_uuid(), ?, 'CUSTOMER', ?, -10.01, 'CAD', now()),
                           (gen_random_uuid(), ?, 'CUSTOMER', ?, 10.01, 'CAD', now())""", id, b, id, a);
        }));
        String message = rootMessage(e);
        assertTrue(message.contains("more than its amount"), message);

        // Nor one that does not mirror the original's accounts.
        Exception wrongWay = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id,
                        amount, currency, created_at, reverses_transaction_id)
                    VALUES (?, 'REVERSAL', ?, ?, 1.00, 'CAD', now(), ?)""", id, a, b, payment);
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                    VALUES (gen_random_uuid(), ?, 'CUSTOMER', ?, -1.00, 'CAD', now()),
                           (gen_random_uuid(), ?, 'CUSTOMER', ?, 1.00, 'CAD', now())""", id, a, id, b);
        }));
        assertTrue(rootMessage(wrongWay).contains("does not mirror"), rootMessage(wrongWay));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
