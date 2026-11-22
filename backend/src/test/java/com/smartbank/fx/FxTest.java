package com.smartbank.fx;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.ledger.ChartOfAccounts;
import com.smartbank.ledger.LedgerReconciliation;
import com.smartbank.ledger.LedgerSealer;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/** Multi-currency accounts and FX transfers through quotes, against real PostgreSQL. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = {"smartbank.fx.quote-ttl=PT2S", "smartbank.ledger.seal-interval=PT1H"})
@AutoConfigureMockMvc
class FxTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingService banking;
    @Autowired FxService fx;
    @Autowired ChartOfAccounts chart;
    @Autowired LedgerReconciliation reconciliation;
    @Autowired LedgerSealer sealer;
    @Autowired TransactionTemplate transactionTemplate;

    private UUID user, admin, cad, usd, jpy, othersUsd;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        admin = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER'), (?, ?, 'x', 'ADMIN')",
                user, user + "@t.local", admin, admin + "@t.local");
        cad = account(user, "1000.00", "CAD");
        usd = account(user, "100.00", "USD");
        jpy = account(user, "0.00", "JPY");
        othersUsd = account(null, "0.00", "USD");
        fx.setRate("CAD", "USD", new BigDecimal("0.7333"), null);
        fx.setRate("USD", "CAD", new BigDecimal("1.3600"), null);
        fx.setRate("USD", "JPY", new BigDecimal("151.237"), null);
    }

    @AfterEach
    void booksStillBalance() {
        var report = reconciliation.run();
        assertTrue(report.isClean(), () -> "Books out of balance: " + report);
    }

    private UUID account(UUID owner, String balance, String currency) {
        UUID id = LedgerTestData.openAccount(jdbc, "FX-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(balance), currency);
        if (owner != null) jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, UUID as, String body) throws Exception {
        return mvc.perform(request.with(jwt().jwt(j -> j.subject(as.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private String quote(UUID from, UUID to, String amount) throws Exception {
        var r = call(post("/api/v1/fx/quotes"), user,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\"}".formatted(from, to, amount));
        assertEquals(201, r.getStatus(), r.getContentAsString());
        return r.getContentAsString();
    }

    private MockHttpServletResponse execute(String quoteId, String key) throws Exception {
        return call(post("/api/v1/fx/transfers").header("Idempotency-Key", key), user, "{\"quoteId\":\"" + quoteId + "\"}");
    }

    @Test
    void quoteLocksTheRateAndExecutionPostsBalancedEntriesInEachCurrency() throws Exception {
        String q = quote(cad, usd, "100.00");
        assertEquals(73.33, ((Number) JsonPath.read(q, "$.buyAmount")).doubleValue());
        assertEquals("USD", JsonPath.read(q, "$.buyCurrency"));

        // The rate changes after the quote; the quote keeps the rate it locked.
        fx.setRate("CAD", "USD", new BigDecimal("0.5000"), null);
        var r = execute(JsonPath.read(q, "$.id"), "fx-1");
        assertEquals(201, r.getStatus(), r.getContentAsString());
        String tx = r.getContentAsString();
        assertEquals("FX_TRANSFER", JsonPath.read(tx, "$.type"));
        assertEquals(73.33, ((Number) JsonPath.read(tx, "$.counterAmount")).doubleValue());
        assertMoney("900.00", balance(cad));
        assertMoney("173.33", balance(usd));

        // Each currency balances on its own, through its FX position account.
        var lines = chart.journal(UUID.fromString(JsonPath.read(tx, "$.id")));
        assertEquals(4, lines.size());
        for (String currency : List.of("CAD", "USD")) {
            BigDecimal debits = lines.stream().filter(l -> l.currency().equals(currency)).map(ChartOfAccounts.JournalLine::debit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credits = lines.stream().filter(l -> l.currency().equals(currency)).map(ChartOfAccounts.JournalLine::credit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, debits.compareTo(credits), currency);
            assertTrue(lines.stream().anyMatch(l -> l.currency().equals(currency) && l.code().equals("3500")), currency);
            var tb = chart.trialBalance(currency, null);
            assertTrue(tb.balanced() && tb.accountingEquationHolds(), currency);
        }
    }

    @Test
    void convertedAmountsRoundDownToTheTargetCurrencysMinorUnit() throws Exception {
        assertEquals(7.33, ((Number) JsonPath.read(quote(cad, usd, "10.00"), "$.buyAmount")).doubleValue());  // 7.333
        assertEquals(1512, ((Number) JsonPath.read(quote(usd, jpy, "10.00"), "$.buyAmount")).intValue());    // 1512.37
        assertEquals(409, call(post("/api/v1/fx/quotes"), user,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"0.01\"}".formatted(cad, usd)).getStatus(),
                "0.01 CAD is 0.0073 USD: too small to convert");
    }

    @Test
    void quotesAreSingleUseShortLivedAndPrivate() throws Exception {
        String q = quote(cad, usd, "10.00");
        String id = JsonPath.read(q, "$.id");
        var first = execute(id, "fx-once");
        var retry = execute(id, "fx-once");
        assertEquals(first.getContentAsString(), retry.getContentAsString(), "Same key: the same transfer");
        var reuse = execute(id, "fx-again");
        assertEquals(409, reuse.getStatus());
        assertTrue(reuse.getContentAsString().contains("already used"));

        UUID stranger = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", stranger, stranger + "@t.local");
        String other = JsonPath.read(quote(cad, usd, "5.00"), "$.id");
        assertEquals(404, call(post("/api/v1/fx/transfers").header("Idempotency-Key", "fx-steal"), stranger,
                "{\"quoteId\":\"" + other + "\"}").getStatus(), "Someone else's quote");

        String late = JsonPath.read(quote(cad, usd, "5.00"), "$.id");
        Thread.sleep(2_100);
        var expired = execute(late, "fx-late");
        assertEquals(409, expired.getStatus());
        assertTrue(expired.getContentAsString().contains("expired"));
        assertMoney("990.00", balance(cad));
    }

    @Test
    void anFxTransferCanPaySomeoneElsesAccountButNotFromOneYouDoNotOwn() throws Exception {
        String q = quote(cad, othersUsd, "20.00");
        assertEquals(201, execute(JsonPath.read(q, "$.id"), "fx-pay").getStatus());
        assertMoney("14.66", balance(othersUsd));
        assertEquals(403, call(post("/api/v1/fx/quotes"), user,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1.00\"}".formatted(othersUsd, cad)).getStatus());
    }

    @Test
    void insufficientFundsAtExecutionLeavesTheQuoteUsable() throws Exception {
        String id = JsonPath.read(quote(usd, cad, "100.00"), "$.id");
        banking.withdraw(usd, new MoneyRequest(new BigDecimal("50.00"), "fx-drain-" + usd));
        assertEquals(409, execute(id, "fx-poor").getStatus());
        banking.deposit(usd, new MoneyRequest(new BigDecimal("50.00"), "fx-refill-" + usd));
        assertEquals(201, execute(id, "fx-rich").getStatus(), "The failed attempt rolled back and did not consume the quote");
        assertMoney("1136.00", balance(cad));
    }

    @Test
    void invalidConversionsAreRejected() throws Exception {
        UUID cad2 = account(user, "0.00", "CAD");
        assertEquals(409, call(post("/api/v1/fx/quotes"), user,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1.00\"}".formatted(cad, cad2)).getStatus(),
                "Same currency: use a normal transfer");
        assertEquals(409, call(post("/api/v1/fx/quotes"), user,
                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1.00\"}".formatted(jpy, cad)).getStatus(),
                "No JPY to CAD rate");
        assertThrows(BankingException.class, () -> banking.transfer(new TransferRequest(cad, usd, BigDecimal.ONE, "fx-plain")),
                "A plain transfer cannot cross currencies");
    }

    @Test
    void amountsCannotHaveMoreDecimalsThanTheirCurrency() {
        var e = assertThrows(BankingException.class,
                () -> banking.deposit(jpy, new MoneyRequest(new BigDecimal("10.50"), "jpy-dec")));
        assertEquals("JPY amounts cannot have decimals", e.getMessage());
        banking.deposit(jpy, new MoneyRequest(new BigDecimal("10.00"), "jpy-whole"));
        assertMoney("10", balance(jpy));
    }

    @Test
    void ratesAreSetByAdministratorsOnly() throws Exception {
        String body = "{\"baseCurrency\":\"EUR\",\"quoteCurrency\":\"CAD\",\"rate\":\"1.4800\"}";
        assertEquals(403, call(post("/api/v1/fx/rates"), user, body).getStatus());
        assertEquals(201, call(post("/api/v1/fx/rates"), admin, body).getStatus());
        String rates = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/fx/rates")
                .with(jwt().jwt(j -> j.subject(user.toString())))).andReturn().getResponse().getContentAsString();
        List<Number> eurCad = JsonPath.read(rates, "$[?(@.baseCurrency=='EUR' && @.quoteCurrency=='CAD')].rate");
        assertEquals(1.48, eurCad.get(0).doubleValue());
    }

    @Test
    void oneQuoteRacedByManyRequestsPostsOnce() throws Exception {
        String id = JsonPath.read(quote(cad, usd, "50.00"), "$.id");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int created = 0;
        try {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                attempts.add(pool.submit(() -> execute(id, "fx-race-" + n).getStatus()));
            }
            for (var f : attempts) if (f.get(1, TimeUnit.MINUTES) == 201) created++;
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, created, "Different keys, one quote: only one conversion");
        assertMoney("950.00", balance(cad));
    }

    @Test
    void theDatabaseRequiresEachCurrencyToBalance() {
        // An FX transfer whose USD side does not match its CAD side, inserted directly.
        UUID tx = UUID.randomUUID(), quoteless = UUID.randomUUID();
        Exception e = assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(status -> jdbc.update("""
                WITH t AS (INSERT INTO banking_transactions (id, transaction_type, source_account_id, destination_account_id,
                               amount, currency, counter_amount, counter_currency, fx_rate, fx_quote_id, created_at)
                           VALUES (?, 'FX_TRANSFER', ?, ?, 10.00, 'CAD', 7.00, 'USD', 0.7, ?, now()))
                INSERT INTO ledger_entries (id, transaction_id, ledger_account, account_id, amount, currency, created_at)
                VALUES (gen_random_uuid(), ?, 'CUSTOMER', ?, -10.00, 'CAD', now()),
                       (gen_random_uuid(), ?, 'FX_POSITION', NULL, 10.00, 'CAD', now()),
                       (gen_random_uuid(), ?, 'FX_POSITION', NULL, -7.00, 'USD', now()),
                       (gen_random_uuid(), ?, 'CUSTOMER', ?, 7.50, 'USD', now())""",
                tx, cad, usd, quoteless, tx, cad, tx, tx, tx, usd)));
        assertTrue(String.valueOf(rootMessage(e)).contains("not balanced"), rootMessage(e));
    }

    @Test
    void fxDetailsAreCoveredByTheHashChain() throws Exception {
        String txId = JsonPath.read(execute(JsonPath.read(quote(cad, usd, "10.00"), "$.id"), "fx-sealed").getContentAsString(), "$.id");
        sealer.sealPending();
        assertTrue(sealer.verify().intact());
        jdbc.execute("ALTER TABLE banking_transactions DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE banking_transactions SET fx_rate = 0.9 WHERE id = ?::uuid", txId);
        } finally {
            jdbc.execute("ALTER TABLE banking_transactions ENABLE TRIGGER USER");
        }
        assertFalse(sealer.verify().intact(), "A changed FX rate breaks the chain");
        jdbc.execute("ALTER TABLE banking_transactions DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE banking_transactions SET fx_rate = 0.7333 WHERE id = ?::uuid", txId);
        } finally {
            jdbc.execute("ALTER TABLE banking_transactions ENABLE TRIGGER USER");
        }
        assertTrue(sealer.verify().intact());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
