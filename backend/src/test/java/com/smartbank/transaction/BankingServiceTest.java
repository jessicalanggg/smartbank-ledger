package com.smartbank.transaction;

import com.smartbank.account.*;
import com.smartbank.ledger.LedgerAccount;
import com.smartbank.ledger.LedgerEntry;
import com.smartbank.ledger.LedgerEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class BankingServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BankingTransactionRepository transactions = mock(BankingTransactionRepository.class);
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final BankingService service = new BankingService(accounts, transactions, ledger);

    private Account account(UUID id, String balance, String currency) {
        Account a = new AccountFixture().make(id, balance, currency);
        when(accounts.findLockedById(id)).thenReturn(Optional.of(a));
        return a;
    }
    private static class AccountFixture {
        Account make(UUID id, String balance, String currency) {
            try {
                var constructor = Account.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                Account a = constructor.newInstance();
                ReflectionTestUtils.setField(a, "id", id);
                ReflectionTestUtils.setField(a, "balance", new BigDecimal(balance));
                ReflectionTestUtils.setField(a, "currency", currency);
                return a;
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        }
    }
    @Test void transferUpdatesBothBalances() {
        UUID from = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID to = UUID.fromString("22222222-2222-4222-8222-222222222222");
        Account a = account(from, "1250.00", "CAD");
        Account b = account(to, "3800.50", "CAD");
        when(transactions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service.transfer(new TransferRequest(from, to, new BigDecimal("200.00"), "transfer-test-1"));
        assertEquals(0, a.getBalance().compareTo(new BigDecimal("1050.00")));
        assertEquals(0, b.getBalance().compareTo(new BigDecimal("4000.50")));
        verify(transactions).saveAndFlush(any());
        verify(ledger).saveAllAndFlush(anyList());
    }
    @Test void insufficientFundsDoesNotCreditTarget() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "10.00", "CAD");
        Account b = account(to, "100.00", "CAD");
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("200.00"), "too-much")));
        assertEquals(0, b.getBalance().compareTo(new BigDecimal("100.00")));
        verify(transactions, never()).saveAndFlush(any());
    }
    @Test void rejectsSameAccountTransfer() {
        UUID id = UUID.randomUUID();
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(id, id, new BigDecimal("1.00"), "same")));
    }
    @Test void rejectsCrossCurrencyTransfer() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "50.00", "CAD"); account(to, "50.00", "USD");
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("1.00"), "currency")));
        verify(transactions, never()).saveAndFlush(any());
    }
    @Test void duplicateRequestReturnsOriginal() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.TRANSFER, from, to,
            new BigDecimal("5.00"), "CAD", "repeat");
        when(transactions.findByRequestKey("repeat")).thenReturn(Optional.of(previous));
        var result = service.transfer(new TransferRequest(from, to, new BigDecimal("5.00"), "repeat"));
        assertEquals(previous.getId(), result.id());
        verify(accounts, never()).findLockedById(any());
    }
    @Test void duplicateDepositReturnsOriginalWithoutLocking() {
        UUID id = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.DEPOSIT, null, id,
            new BigDecimal("5.00"), "CAD", "dep");
        when(transactions.findByRequestKey("dep")).thenReturn(Optional.of(previous));
        var result = service.deposit(id, new MoneyRequest(new BigDecimal("5.00"), "dep"));
        assertEquals(previous.getId(), result.id());
        verify(accounts, never()).findLockedById(any());
        verify(transactions, never()).saveAndFlush(any());
    }
    @Test void depositKeyReusedForWithdrawalIsRejected() {
        UUID id = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.DEPOSIT, null, id,
            new BigDecimal("5.00"), "CAD", "dep");
        when(transactions.findByRequestKey("dep")).thenReturn(Optional.of(previous));
        assertThrows(BankingException.class,
            () -> service.withdraw(id, new MoneyRequest(new BigDecimal("5.00"), "dep")));
        verify(transactions, never()).saveAndFlush(any());
    }
    @Test void everyTransactionTypePostsBalancedEntries() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("12.34");
        var cases = java.util.List.of(
            new BankingTransaction(TransactionType.DEPOSIT, null, b, amount, "CAD", null),
            new BankingTransaction(TransactionType.WITHDRAWAL, a, null, amount, "CAD", null),
            new BankingTransaction(TransactionType.TRANSFER, a, b, amount, "CAD", null),
            new BankingTransaction(TransactionType.OPENING_BALANCE, null, b, amount, "CAD", null));
        for (BankingTransaction tx : cases) {
            var entries = BankingService.postings(tx);
            assertEquals(2, entries.size(), tx.getTransactionType().name());
            assertEquals(0, entries.stream().map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).signum(), tx.getTransactionType() + " must sum to zero");
            assertTrue(entries.stream().allMatch(e -> e.getTransactionId().equals(tx.getId())));
            if (tx.getDestinationAccountId() != null)
                assertTrue(entries.stream().anyMatch(e -> tx.getDestinationAccountId().equals(e.getAccountId())
                    && e.getAmount().compareTo(amount) == 0), "destination is credited");
            if (tx.getSourceAccountId() != null)
                assertTrue(entries.stream().anyMatch(e -> tx.getSourceAccountId().equals(e.getAccountId())
                    && e.getAmount().compareTo(amount.negate()) == 0), "source is debited");
        }
    }
    @Test void internalEntriesCannotPostToCustomerLedgerAccount() {
        assertThrows(IllegalArgumentException.class, () -> LedgerEntry.internal(
            UUID.randomUUID(), LedgerAccount.CUSTOMER, BigDecimal.ONE, "CAD", java.time.Instant.now()));
    }
}
