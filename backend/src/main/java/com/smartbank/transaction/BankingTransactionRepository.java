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
    List<BankingTransaction> findBySourceAccountIdOrDestinationAccountIdOrderByCreatedAtDesc(
        UUID sourceAccountId, UUID destinationAccountId, Pageable pageable);
}
