package com.smartbank.ledger;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The chart of accounts, journal lines and the trial balance, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ChartOfAccountsTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired BankingService banking;
    @Autowired ChartOfAccounts chart;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void freshBooks() {
        LedgerTestData.truncateLedger(jdbc);
        LedgerTestData.postOpeningBalancesForAllAccounts(jdbc);
    }

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID account(String balance) {
        return LedgerTestData.openAccount(jdbc, "COA-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(balance), "CAD");
    }

    private static String key() { return "coa-" + UUID.randomUUID(); }

    private String code(UUID account) {
        return jdbc.queryForObject("SELECT code FROM ledger_accounts WHERE customer_account_id = ?", String.class, account);
    }

    /** "CODE DR 10.00" / "CODE CR 10.00" for each line of a transaction. */
    private List<String> journal(UUID transactionId) {
        return chart.journal(transactionId).stream()
                .map(l -> l.code() + (l.debit().signum() > 0 ? " DR " + l.debit().toPlainString() : " CR " + l.credit().toPlainString()))
                .sorted().toList();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void everyCustomerAccountGetsItsOwnLiabilityAccount() {
        UUID a = account("0.00");
        var row = jdbc.queryForMap("""
                SELECT la.code, la.account_type, la.normal_balance, p.code AS parent
                FROM ledger_accounts la JOIN ledger_accounts p ON p.id = la.parent_id
                WHERE la.customer_account_id = ?""", a);
        assertTrue(((String) row.get("code")).startsWith("2000-COA-"));
        assertEquals("LIABILITY", row.get("account_type"));
        assertEquals("CREDIT", row.get("normal_balance"));
        assertEquals("2000", row.get("parent"));
        // The system accounts for the currency exist, each with the normal side its type implies.
        assertEquals(List.of("1000 ASSET DEBIT", "2000 LIABILITY CREDIT", "3500 EQUITY CREDIT", "3900 EQUITY CREDIT",
                        "4000 REVENUE CREDIT"),
                jdbc.queryForList("""
                        SELECT code || ' ' || account_type || ' ' || normal_balance FROM ledger_accounts
                        WHERE currency = 'CAD' AND customer_account_id IS NULL ORDER BY code""", String.class));
    }

    @Test
    void everyPostingTypeIsCorrectDoubleEntry() {
        UUID a = account("100.00"), b = account("0.00");
        String ca = code(a), cb = code(b);
        UUID deposit = banking.deposit(a, new MoneyRequest(new BigDecimal("50.00"), key())).id();
        UUID withdrawal = banking.withdraw(a, new MoneyRequest(new BigDecimal("20.00"), key())).id();
        UUID transfer = banking.transfer(new TransferRequest(a, b, new BigDecimal("30.00"), key())).id();
        UUID fee = banking.chargeFee(a, new MoneyRequest(new BigDecimal("2.50"), key())).id();
        UUID reversal = banking.reverse(deposit, new ReversalRequest(new BigDecimal("10.00"), key())).id();

        // A customer's money is the bank's liability: credits increase it, debits reduce it.
        assertEquals(List.of("1000 DR 50.00", ca + " CR 50.00").stream().sorted().toList(), journal(deposit));
        assertEquals(List.of("1000 CR 20.00", ca + " DR 20.00").stream().sorted().toList(), journal(withdrawal));
        assertEquals(List.of(ca + " DR 30.00", cb + " CR 30.00").stream().sorted().toList(), journal(transfer));
        assertEquals(List.of("4000 CR 2.50", ca + " DR 2.50").stream().sorted().toList(), journal(fee));
        assertEquals(List.of("1000 CR 10.00", ca + " DR 10.00").stream().sorted().toList(), journal(reversal));
    }

    @Test
    void trialBalanceBalancesAndSatisfiesTheAccountingEquation() {
        UUID a = account("100.00"), b = account("0.00");
        banking.deposit(a, new MoneyRequest(new BigDecimal("50.00"), key()));
        banking.transfer(new TransferRequest(a, b, new BigDecimal("30.00"), key()));
        banking.chargeFee(b, new MoneyRequest(new BigDecimal("1.25"), key()));
        banking.withdraw(b, new MoneyRequest(new BigDecimal("5.00"), key()));

        var tb = chart.trialBalance("CAD", null);
        assertTrue(tb.balanced(), "Total debits must equal total credits");
        assertTrue(tb.accountingEquationHolds(), "Assets = Liabilities + Equity + Revenue - Expenses");
        assertEquals(0, tb.totalDebits().compareTo(tb.totalCredits()));
        var byCode = tb.accounts().stream().collect(java.util.stream.Collectors.toMap(
                ChartOfAccounts.AccountBalance::code, ChartOfAccounts.AccountBalance::balance));
        assertMoney("45.00", byCode.get("1000"));      // cash in 50, out 5
        assertMoney("1.25", byCode.get("4000"));       // fee revenue
        assertMoney("120.00", byCode.get(code(a)));    // 100 + 50 - 30
        assertMoney("23.75", byCode.get(code(b)));     // 30 - 1.25 - 5
        // The control account rolls up every customer's balance and equals what accounts store.
        BigDecimal stored = jdbc.queryForObject("SELECT SUM(balance) FROM accounts WHERE currency = 'CAD'", BigDecimal.class);
        assertMoney(stored.toPlainString(), byCode.get("2000"));
    }

    @Test
    void trialBalanceCanBeTakenAsOfAnEarlierMoment() throws Exception {
        UUID a = account("100.00");
        banking.deposit(a, new MoneyRequest(new BigDecimal("40.00"), key()));
        Thread.sleep(20);
        Instant between = Instant.now();
        Thread.sleep(20);
        banking.chargeFee(a, new MoneyRequest(new BigDecimal("3.00"), key()));

        var before = chart.trialBalance("CAD", between);
        var now = chart.trialBalance("CAD", null);
        assertTrue(before.balanced() && before.accountingEquationHolds());
        assertMoney("0.00", balance(before, "4000"));
        assertMoney("3.00", balance(now, "4000"));
        assertMoney("140.00", balance(before, code(a)));
        assertMoney("137.00", balance(now, code(a)));
    }

    private static BigDecimal balance(ChartOfAccounts.TrialBalance tb, String code) {
        return tb.accounts().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow().balance();
    }

    @Test
    void feesNeedFundsAndAreIdempotent() {
        UUID a = account("2.00");
        String k = key();
        UUID first = banking.chargeFee(a, new MoneyRequest(new BigDecimal("1.50"), k)).id();
        assertEquals(first, banking.chargeFee(a, new MoneyRequest(new BigDecimal("1.50"), k)).id());
        assertThrows(BankingException.class, () -> banking.chargeFee(a, new MoneyRequest(new BigDecimal("0.51"), key())));
        assertThrows(BankingException.class, () -> banking.withdraw(a, new MoneyRequest(new BigDecimal("1.50"), k)),
                "A fee's key cannot be reused for a withdrawal");
        assertMoney("0.50", jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, a));
    }

    @Test
    void ledgerApiAndFeesAreForAdministratorsOnly() throws Exception {
        UUID admin = UUID.randomUUID(), customer = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'ADMIN'), (?, ?, 'x', 'CUSTOMER')",
                admin, admin + "@t.local", customer, customer + "@t.local");
        UUID a = account("10.00");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", customer, a);

        String fee = "{\"amount\":\"1.00\"}";
        mvc.perform(post("/api/v1/accounts/{id}/fees", a).with(jwt().jwt(j -> j.subject(customer.toString())))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(fee)).andExpect(status().isForbidden());
        String charged = mvc.perform(post("/api/v1/accounts/{id}/fees", a).with(jwt().jwt(j -> j.subject(admin.toString())))
                        .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(fee))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertEquals("FEE", JsonPath.read(charged, "$.type"));

        mvc.perform(get("/api/v1/ledger/trial-balance").with(jwt().jwt(j -> j.subject(customer.toString()))))
                .andExpect(status().isForbidden());
        String tb = mvc.perform(get("/api/v1/ledger/trial-balance").param("currency", "CAD")
                        .with(jwt().jwt(j -> j.subject(admin.toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(true, JsonPath.read(tb, "$.balanced"));
        assertEquals(true, JsonPath.read(tb, "$.accountingEquationHolds"));
        String journal = mvc.perform(get("/api/v1/ledger/transactions/{id}/journal", JsonPath.<String>read(charged, "$.id"))
                        .with(jwt().jwt(j -> j.subject(admin.toString()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(2, (int) JsonPath.read(journal, "$.length()"));
    }

    @Test
    void theDatabaseKeepsTheChartConsistent() {
        assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, created_at)
                VALUES (gen_random_uuid(), '5000', 'Wrong side', 'EXPENSE', 'CREDIT', 'CAD', now())"""),
                "An expense is debit-normal");
        UUID a = account("0.00");
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE ledger_accounts SET account_type = 'ASSET', normal_balance = 'DEBIT' WHERE customer_account_id = ?", a),
                "A customer's account is a liability");
    }

    @Test
    void reconciliationNoticesAnEntryWithNoChartAccount() {
        UUID a = account("10.00");
        jdbc.update("DELETE FROM ledger_accounts WHERE customer_account_id = ?", a);
        try {
            var report = reconciliation.run();
            assertFalse(report.isClean());
            assertTrue(report.entriesWithoutChartAccount() >= 1);
            assertEquals(1, report.customerAccountsWithoutChartAccount());
        } finally {
            jdbc.update("""
                    INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, currency, posting_kind,
                                                 customer_account_id, parent_id, created_at)
                    SELECT gen_random_uuid(), '2000-' || a.account_number, 'Customer deposits: ' || a.account_number,
                           'LIABILITY', 'CREDIT', a.currency, 'CUSTOMER', a.id, p.id, now()
                    FROM accounts a JOIN ledger_accounts p ON p.code = '2000' AND p.currency = a.currency WHERE a.id = ?""", a);
        }
    }
}
