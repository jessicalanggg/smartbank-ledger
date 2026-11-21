package com.smartbank.transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface BankingTransactionRepository extends JpaRepository<BankingTransaction, UUID> {
    Optional<BankingTransaction> findByRequestKey(String requestKey);
    List<BankingTransaction> findByRequestKeyIn(java.util.Collection<String> requestKeys);
    List<BankingTransaction> findByLinkIdOrderByLinkIndex(UUID linkId);

    /** Locks the transaction so reversals of it are decided one at a time. */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT * FROM banking_transactions WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<BankingTransaction> findLockedById(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(t.amount), 0) from BankingTransaction t where t.reversesTransactionId = :id")
    java.math.BigDecimal sumReversedAmount(@org.springframework.data.repository.query.Param("id") UUID id);
    List<BankingTransaction> findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(
        UUID sourceAccountId, UUID destinationAccountId, Pageable pageable);
}
