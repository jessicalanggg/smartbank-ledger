package com.smartbank.transaction;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
    Optional<Hold> findByRequestKey(String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findLockedById(@Param("id") UUID id);

    @Query("select h.id from Hold h where h.status = com.smartbank.transaction.HoldStatus.PENDING and h.expiresAt <= :now order by h.expiresAt")
    List<UUID> findIdsDueForExpiry(@Param("now") Instant now, Pageable page);

    @Query("select h from Hold h where h.sourceAccountId = :accountId or h.destinationAccountId = :accountId order by h.createdAt desc")
    List<Hold> findForAccount(@Param("accountId") UUID accountId, Pageable page);
}
