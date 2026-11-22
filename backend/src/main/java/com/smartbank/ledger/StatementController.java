package com.smartbank.ledger;

import com.smartbank.auth.AccessService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class StatementController {
    private final Statements statements;
    private final AccessService access;
    private final Clock clock;

    public StatementController(Statements statements, AccessService access, Clock clock) {
        this.statements = statements;
        this.access = access;
        this.clock = clock;
    }

    /** The owner's statement for [from, to); defaults to the last 30 days. */
    @GetMapping("/{id}/statement")
    public Statements.Statement statement(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        access.requireOwner(id);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : from;
        return statements.statement(id, start, end);
    }
}
