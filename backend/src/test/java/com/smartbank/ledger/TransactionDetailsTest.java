package com.smartbank.ledger;

import com.jayway.jsonpath.JsonPath;
import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.BankingService;
import com.smartbank.transaction.MoneyRequest;
import com.smartbank.transaction.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A customer's view of one transaction: its ledger lines and the seal covering it. */
@ActiveProfiles("test")
@Testcontainers
@SpringBootTest(properties = "smartbank.ledger.seal-interval=PT1H")
@AutoConfigureMockMvc
class TransactionDetailsTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankingService banking;
    @Autowired LedgerSealer sealer;

    private UUID payer, payee, stranger, admin, from, to;

    @BeforeEach
    void setUp() {
        payer = user("CUSTOMER");
        payee = user("CUSTOMER");
        stranger = user("CUSTOMER");
        admin = user("ADMIN");
        from = account(payer, "100.00");
        to = account(payee, "0.00");
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', ?)", id, id + "@t.local", role);
        return id;
    }

    private UUID account(UUID owner, String balance) {
        UUID id = LedgerTestData.openAccount(jdbc, "DET-" + UUID.randomUUID().toString().substring(0, 8), new BigDecimal(balance), "CAD");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id = ?", owner, id);
        return id;
    }

    private MockHttpServletResponse details(UUID tx, UUID as) throws Exception {
        return mvc.perform(get("/api/v1/transactions/" + tx + "/details").with(jwt().jwt(j -> j.subject(as.toString()))))
                .andReturn().getResponse();
    }

    @Test
    void bothPartiesSeeTheLedgerLinesAndTheSeal() throws Exception {
        UUID tx = banking.transfer(new TransferRequest(from, to, new BigDecimal("30.00"), "det-" + UUID.randomUUID())).id();

        String before = details(tx, payer).getContentAsString();
        assertNull(JsonPath.read(before, "$.seal"), "Not sealed until the sealer runs");
        assertEquals("TRANSFER", JsonPath.read(before, "$.transaction.type"));
        // Double entry: the payer's account is debited, the payee's credited, by the same amount.
        List<Number> debits = JsonPath.read(before, "$.journal[*].debit");
        List<Number> credits = JsonPath.read(before, "$.journal[*].credit");
        assertEquals(30.0, debits.stream().mapToDouble(Number::doubleValue).sum());
        assertEquals(30.0, credits.stream().mapToDouble(Number::doubleValue).sum());

        sealer.sealPending();
        String after = details(tx, payee).getContentAsString();
        assertNotNull(JsonPath.read(after, "$.seal.seq"));
        assertEquals(Boolean.FALSE, JsonPath.read(after, "$.seal.signed"), "No signing key in this test");
    }

    @Test
    void onlyThePartiesAndAdministratorsCanSeeIt() throws Exception {
        UUID tx = banking.transfer(new TransferRequest(from, to, new BigDecimal("5.00"), "det-" + UUID.randomUUID())).id();
        assertEquals(403, details(tx, stranger).getStatus());
        assertEquals(200, details(tx, admin).getStatus());
        assertEquals(404, details(UUID.randomUUID(), payer).getStatus());
    }

    @Test
    void depositsHaveOneCustomerSide() throws Exception {
        UUID tx = banking.deposit(to, new MoneyRequest(new BigDecimal("12.00"), "det-" + UUID.randomUUID())).id();
        String body = details(tx, payee).getContentAsString();
        // Cash and settlement is debited, the customer's deposit account credited.
        assertEquals(List.of("1000"), JsonPath.read(body, "$.journal[?(@.debit > 0)].code"));
        assertEquals(403, details(tx, payer).getStatus());
        assertFalse(body.contains("refundable"), "Only transfers can be refunded by their payee");
    }

    @Test
    void refundableShrinksAsThePayeeRefunds() throws Exception {
        UUID tx = banking.transfer(new TransferRequest(from, to, new BigDecimal("40.00"), "det-" + UUID.randomUUID())).id();
        assertEquals(40.0, refundable(tx));

        int status = mvc.perform(post("/api/v1/transactions/" + tx + "/reversals")
                        .with(jwt().jwt(j -> j.subject(payee.toString())))
                        .contentType("application/json")
                        .content("{\"amount\":15.00,\"requestKey\":\"ref-" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getStatus();
        assertEquals(201, status);
        assertEquals(25.0, refundable(tx));
    }

    private double refundable(UUID tx) throws Exception {
        Number n = JsonPath.read(details(tx, payee).getContentAsString(), "$.refundable");
        return n.doubleValue();
    }
}
