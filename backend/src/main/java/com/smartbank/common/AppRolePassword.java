package com.smartbank.common;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * After migrations, gives the application's database role (smartbank_app, created by
 * V17) its password and lets it log in. Runs as the migration user, so the secret
 * lives only in configuration, never in a migration. With no password configured it
 * does nothing, for databases where an administrator manages the role.
 */
@Component
public class AppRolePassword implements Callback {
    static final String ROLE = "smartbank_app";
    private final String password;

    public AppRolePassword(@Value("${smartbank.db.app-password:}") String password) {
        this.password = password;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE && !password.isBlank();
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try (PreparedStatement quote = connection.prepareStatement("SELECT quote_literal(?)")) {
            quote.setString(1, password);
            String literal;
            try (ResultSet rs = quote.executeQuery()) {
                rs.next();
                literal = rs.getString(1);
            }
            // ALTER ROLE takes no bind parameters; the literal is quoted by PostgreSQL itself.
            try (Statement alter = connection.createStatement()) {
                alter.execute("ALTER ROLE " + ROLE + " WITH LOGIN PASSWORD " + literal);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not set the password of database role " + ROLE
                    + "; the migration user needs CREATEROLE, or leave smartbank.db.app-password empty", e);
        }
    }

    @Override
    public String getCallbackName() {
        return "app-role-password";
    }
}
