package com.smartbank.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByAccountNumber(String accountNumber);

    java.util.List<Account> findByOwnerUserIdOrderByAccountNumber(UUID ownerUserId);

    boolean existsByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update Account a set a.ownerUserId = :userId where a.id = :id and a.ownerUserId is null")
    int assignOwnerIfUnassigned(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Locks the row FOR NO KEY UPDATE rather than FOR UPDATE: it still excludes other
     * balance changes, but does not conflict with the KEY SHARE locks PostgreSQL takes
     * when a ledger entry or transaction referencing this account is inserted. With
     * FOR UPDATE, a hold could deadlock with a transfer that has inserted its entries.
     */
    @Query(value = "SELECT * FROM accounts WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Account> findLockedById(@Param("id") UUID id);
}
