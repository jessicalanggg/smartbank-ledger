package com.smartbank.auth;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "app_users")
public class AppUser {
    @Id private UUID id = UUID.randomUUID();
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(nullable = false, length = 20) private String role = "CUSTOMER";
    protected AppUser() {}
    public AppUser(String email, String passwordHash) { this.email = email; this.passwordHash = passwordHash; }
    /** An administrator: can read the bank's books, charge fees and reverse deposits and withdrawals. */
    public static AppUser admin(String email, String passwordHash) {
        AppUser user = new AppUser(email, passwordHash);
        user.role = "ADMIN";
        return user;
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
}
