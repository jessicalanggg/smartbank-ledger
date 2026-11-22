package com.smartbank.auth;

import com.smartbank.account.AccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Configuration
public class DemoUserBootstrap {
    @Bean
    CommandLineRunner demoUsers(UserRepository users, AccountRepository accounts, PasswordEncoder encoder,
            TransactionTemplate transactionTemplate,
            @Value("${smartbank.demo-password:}") String password) {
        return args -> {
            if (password.isBlank())
                return;
            transactionTemplate.executeWithoutResult(status -> {
                create(users, accounts, encoder, "demo.a@smartbank.local", password,
                        "11111111-1111-4111-8111-111111111111");
                create(users, accounts, encoder, "demo.a@smartbank.local", password,
                        "33333333-3333-4333-8333-333333333333");  // USD account, for FX
                create(users, accounts, encoder, "demo.b@smartbank.local", password,
                        "22222222-2222-4222-8222-222222222222");
            });
        };
    }

    void create(UserRepository users,
            AccountRepository accounts,
            PasswordEncoder encoder,
            String email,
            String password,
            String accountId) {

        AppUser user = users.findByEmail(email)
                .orElseGet(() -> users.saveAndFlush(
                        new AppUser(email, encoder.encode(password))));

        accounts.assignOwnerIfUnassigned(
                UUID.fromString(accountId),
                user.getId());
    }
}
