package com.smartbank.benchmark;

import com.smartbank.support.LedgerTestData;
import com.smartbank.transaction.*;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Counts database round trips per operation. All traffic goes through Toxiproxy;
 * each operation is timed sequentially with and without a fixed delay added to
 * every response from PostgreSQL. Each round trip waits for one response, so
 *
 *   round trips = (median time with delay - median time without) / delay
 *
 * CPU time and machine noise cancel out of the difference. Also prints the SQL
 * statements one transfer sends, from PostgreSQL's statement log.
 *
 * Opt-in: mvn test -Dtest=RoundTripProfile -Dbenchmark=true
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "smartbank.webhooks.poll-interval=PT1H")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
class RoundTripProfile {
    private static final int DELAY_MS = 20;
    private static final int SAMPLES = 40;

    static final Network network = Network.newNetwork();
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(network).withNetworkAliases("pg")
            .withCommand("postgres", "-c", "log_statement=all", "-c", "log_line_prefix=%p|");
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.9.0").withNetwork(network);
    static final Proxy proxy;

    static {
        postgres.start();
        toxiproxy.start();
        try {
            proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "pg:5432");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s".formatted(
                toxiproxy.getHost(), toxiproxy.getMappedPort(8666), postgres.getDatabaseName()));
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired BankingService banking;
    @Autowired HoldService holds;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    interface Op { void run(int i) throws Exception; }

    @Test
    void roundTripsPerOperation() throws Exception {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_users (id, email, password_hash, role) VALUES (?, ?, 'x', 'CUSTOMER')", user, user + "@rt.local");
        UUID a = LedgerTestData.openAccount(jdbc, "RT-A", new BigDecimal("100000000.00"), "CAD");
        UUID b = LedgerTestData.openAccount(jdbc, "RT-B", new BigDecimal("100000000.00"), "CAD");
        jdbc.update("UPDATE accounts SET owner_user_id = ? WHERE id IN (?, ?)", user, a, b);
        String run = UUID.randomUUID().toString().substring(0, 8);
        BigDecimal one = BigDecimal.ONE;
        TransactionResponse replayed = banking.transfer(new TransferRequest(a, b, one, "rt-replay-" + run));
        List<UUID> holdIds = Collections.synchronizedList(new ArrayList<>());

        Map<String, Op> ops = new LinkedHashMap<>();
        ops.put("Transfer (service)", i -> banking.transfer(new TransferRequest(a, b, one, "rt-t-" + run + "-" + i + "-" + delayTag)));
        ops.put("Deposit (service)", i -> banking.deposit(a, new MoneyRequest(one, "rt-d-" + run + "-" + i + "-" + delayTag)));
        ops.put("Withdrawal (service)", i -> banking.withdraw(a, new MoneyRequest(one, "rt-w-" + run + "-" + i + "-" + delayTag)));
        ops.put("Retry of a committed transfer (service)", i -> banking.transfer(new TransferRequest(a, b, one, "rt-replay-" + run)));
        ops.put("Create hold (service)", i -> holdIds.add(holds.create(new CreateHoldRequest(a, b, one, "rt-h-" + run + "-" + i + "-" + delayTag, null)).id()));
        ops.put("Post hold (service)", i -> holds.post(holdIds.remove(0), null));
        ops.put("Transfer (HTTP, with idempotency key)", i -> mvc.perform(post("/api/v1/transfers")
                .with(jwt().jwt(j -> j.subject(user.toString())))
                .header("Idempotency-Key", "rt-api-" + run + "-" + i + "-" + delayTag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"1.00\"}".formatted(a, b))));

        StringBuilder table = new StringBuilder("""
                | Operation | Round trips | ms without delay | ms with %d ms delay |
                | --- | --- | --- | --- |
                """.formatted(DELAY_MS));
        for (var op : ops.entrySet()) {
            if (op.getKey().startsWith("Post hold")) {  // needs holds to post, created untimed
                for (int i = 0; i < 2 * (SAMPLES + 5); i++)
                    holdIds.add(holds.create(new CreateHoldRequest(a, b, one, "rt-hp-" + run + "-" + i, null)).id());
            }
            delayTag = "fast";
            double fast = median(op.getValue());
            proxy.toxics().latency("delay", ToxicDirection.DOWNSTREAM, DELAY_MS);
            delayTag = "slow";
            double slow;
            try {
                slow = median(op.getValue());
            } finally {
                proxy.toxics().get("delay").remove();
            }
            table.append("| %s | %.1f | %.1f | %.1f |%n".formatted(op.getKey(), (slow - fast) / DELAY_MS, fast, slow));
        }
        System.out.println("[roundtrips]\n" + table);
        System.out.println("[roundtrips] statements for one transfer:\n" + statementsForOneTransfer(a, b, run));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "round-trips.md"), table.toString());
    }

    private volatile String delayTag = "fast";

    private static double median(Op op) throws Exception {
        for (int i = 0; i < 5; i++) op.run(-1 - i + (int) (Math.random() * -1_000_000));  // warm up
        double[] ms = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            op.run(i);
            ms[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(ms);
        return ms[SAMPLES / 2];
    }

    /** The statements PostgreSQL logged for a single transfer. */
    private String statementsForOneTransfer(UUID a, UUID b, String run) throws Exception {
        String marker = "rt-log-" + run;
        int before = postgres.getLogs().length();
        banking.transfer(new TransferRequest(a, b, BigDecimal.ONE, marker));
        Thread.sleep(500);
        String logs = postgres.getLogs().substring(before);
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String line : logs.split("\n")) {
            int i = line.indexOf("LOG:  ");
            if (i < 0 || !(line.contains("statement:") || line.contains("execute"))) continue;
            String sql = line.substring(i + 6).replaceAll("\\s+", " ");
            out.append("  ").append(++n).append(". ").append(sql.length() > 150 ? sql.substring(0, 150) + "…" : sql).append('\n');
        }
        return out.toString();
    }
}
