package com.smartbank.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Encrypts any plain-text signing secrets left from before migration V11, then clears them. */
@Component
public class WebhookSecretMigrator {
    private static final Logger log = LoggerFactory.getLogger(WebhookSecretMigrator.class);
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public WebhookSecretMigrator(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public int encryptPlaintextSecrets() {
        var rows = jdbc.query("SELECT id, secret FROM webhook_endpoints WHERE secret IS NOT NULL",
                (rs, n) -> new Object[] { rs.getObject("id", UUID.class), rs.getString("secret") });
        for (Object[] row : rows)
            jdbc.update("UPDATE webhook_endpoints SET secret_encrypted = ?, secret = NULL WHERE id = ? AND secret IS NOT NULL",
                    cipher.encrypt((String) row[1], row[0].toString()), row[0]);
        if (!rows.isEmpty()) log.info("Encrypted {} plain-text webhook secrets", rows.size());
        return rows.size();
    }
}
