package com.smartbank.ledger;

import com.smartbank.auth.AccessService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The bank's books: administrators only. */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {
    private final ChartOfAccounts chart;
    private final AccessService access;

    public LedgerController(ChartOfAccounts chart, AccessService access) {
        this.chart = chart;
        this.access = access;
    }

    @GetMapping("/accounts")
    public List<ChartOfAccounts.AccountBalance> accounts(@RequestParam(defaultValue = "CAD") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        access.requireAdmin();
        return chart.accounts(currency, asOf);
    }

    /** Optional {@code asOf}: the books as they stood at that moment. */
    @GetMapping("/trial-balance")
    public ChartOfAccounts.TrialBalance trialBalance(@RequestParam(defaultValue = "CAD") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {
        access.requireAdmin();
        return chart.trialBalance(currency, asOf);
    }

    @GetMapping("/transactions/{id}/journal")
    public List<ChartOfAccounts.JournalLine> journal(@PathVariable UUID id) {
        access.requireAdmin();
        return chart.journal(id);
    }
}
