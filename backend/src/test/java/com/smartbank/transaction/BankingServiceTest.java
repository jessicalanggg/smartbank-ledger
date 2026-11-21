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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BankingServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BankingTransactionRepository transactions = mock(BankingTransactionRepository.class);
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate = inlineTransactions();
    private final BankingService service = new BankingService(accounts, transactions, ledger, transactionTemplate);

    /** Runs the callback directly, as if inside a transaction. */
    @SuppressWarnings("unchecked")
    private static org.springframework.transaction.support.TransactionTemplate inlineTransactions() {
        var template = mock(org.springframework.transaction.support.TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(i ->
            ((org.springframework.transaction.support.TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        return template;
    }

    private Account account(UUID id, String balance, String currency) {
        Account a = new AccountFixture().make(id, balance, currency);
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        when(accounts.findLockedById(id)).thenReturn(Optional.of(a));
        // The conditional debit succeeds only when the fixture's balance covers it.
        when(accounts.debitIfAvailable(eq(id), any())).thenAnswer(i ->
            a.getAvailableBalance().compareTo(i.getArgument(1)) >= 0 ? 1 : 0);
        when(accounts.credit(eq(id), any())).thenReturn(1);
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
        account(from, "1250.00", "CAD");
        account(to, "3800.50", "CAD");
        when(transactions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        service.transfer(new TransferRequest(from, to, new BigDecimal("200.00"), "transfer-test-1"));
        // Ledger rows first, then the balance updates, debit before credit (UUID order here).
        var order = inOrder(transactions, ledger, accounts);
        order.verify(transactions).saveAndFlush(any());
        order.verify(ledger).saveAllAndFlush(anyList());
        order.verify(accounts).debitIfAvailable(from, new BigDecimal("200.00"));
        order.verify(accounts).credit(to, new BigDecimal("200.00"));
        verify(accounts, never()).findLockedById(any());
    }
    @Test void insufficientFundsDoesNotCreditTarget() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "10.00", "CAD");
        account(to, "100.00", "CAD");
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("200.00"), "too-much")));
        verify(accounts, never()).credit(any(), any());
        verify(transactions, never()).saveAndFlush(any());
    }
    @Test void staleReadIsOverruledByTheConditionalDebit() {
        // The unlocked read says there is enough, but by the time of the debit there is not.
        UUID from = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID to = UUID.fromString("22222222-2222-4222-8222-222222222222");
        account(from, "500.00", "CAD");
        account(to, "0.00", "CAD");
        when(accounts.debitIfAvailable(eq(from), any())).thenReturn(0);
        when(transactions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("200.00"), "stale")));
        verify(accounts, never()).credit(any(), any());
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
        verify(accounts, never()).debitIfAvailable(any(), any());
        verify(accounts, never()).credit(any(), any());
    }
    @Test void simultaneousDuplicateIsReplayedFromTheCommittedRow() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "100.00", "CAD"); account(to, "0.00", "CAD");
        BankingTransaction winner = new BankingTransaction(TransactionType.TRANSFER, from, to,
            new BigDecimal("5.00"), "CAD", "dup");
        // First lookup: nothing committed yet. Insert hits the unique index. Second lookup: the winner.
        when(transactions.findByRequestKey("dup")).thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.saveAndFlush(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("request_key"));
        var result = service.transfer(new TransferRequest(from, to, new BigDecimal("5.00"), "dup"));
        assertEquals(winner.getId(), result.id());
    }
    @Test void duplicateDepositReturnsOriginalWithoutLocking() {
        UUID id = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.DEPOSIT, null, id,
            new BigDecimal("5.00"), "CAD", "dep");
        when(transactions.findByRequestKey("dep")).thenReturn(Optional.of(previous));
        var result = service.deposit(id, new MoneyRequest(new BigDecimal("5.00"), "dep"));
        assertEquals(previous.getId(), result.id());
        verify(accounts, never()).credit(any(), any());
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
