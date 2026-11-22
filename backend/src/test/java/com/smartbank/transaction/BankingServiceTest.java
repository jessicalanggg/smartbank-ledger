package com.smartbank.transaction;

import com.smartbank.account.*;
import com.smartbank.ledger.LedgerAccount;
import com.smartbank.ledger.LedgerEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class BankingServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BankingTransactionRepository transactions = mock(BankingTransactionRepository.class);
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate = inlineTransactions();
    private final PostingWriter writer = mock(PostingWriter.class);
    private final BankingService service = new BankingService(accounts, transactions, transactionTemplate, writer,
        mock(com.smartbank.common.Currencies.class), mock(org.springframework.jdbc.core.JdbcTemplate.class));
    /** Balances the fake writer applies, keyed by account. */
    private final java.util.Map<UUID, Account> fixtures = new java.util.HashMap<>();

    {
        when(writer.post(any())).thenAnswer(i -> TransactionResponse.from(i.getArgument(0)));
        // The fake batch applies a debit only when the fixture's available balance covers it.
        when(writer.applyBalanceChanges(anyList())).thenAnswer(i -> {
            java.util.List<PostingWriter.BalanceChange> changes = i.getArgument(0);
            boolean[] applied = new boolean[changes.size()];
            for (int n = 0; n < changes.size(); n++) {
                var c = changes.get(n);
                applied[n] = c.delta().signum() > 0
                    || fixtures.get(c.accountId()).getAvailableBalance().compareTo(c.delta().negate()) >= 0;
            }
            return applied;
        });
    }

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
        fixtures.put(id, a);
        when(accounts.findAllById(anyIterable())).thenAnswer(i -> {
            java.util.List<Account> found = new java.util.ArrayList<>();
            for (Object key : (Iterable<?>) i.getArgument(0)) if (fixtures.containsKey(key)) found.add(fixtures.get(key));
            return found;
        });
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
        service.transfer(new TransferRequest(from, to, new BigDecimal("200.00"), "transfer-test-1"));
        // One write for the posting, then one balance batch, debit before credit (UUID order here).
        var order = inOrder(writer);
        order.verify(writer).post(argThat(tx -> tx.getSourceAccountId().equals(from)
            && tx.getAmount().compareTo(new BigDecimal("200.00")) == 0));
        order.verify(writer).applyBalanceChanges(java.util.List.of(
            new PostingWriter.BalanceChange(from, new BigDecimal("-200.00")),
            new PostingWriter.BalanceChange(to, new BigDecimal("200.00"))));
    }
    @Test void creditComesFirstWhenTheDestinationSortsFirst() {
        UUID from = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID to = UUID.fromString("11111111-1111-4111-8111-111111111111");
        account(from, "100.00", "CAD");
        account(to, "0.00", "CAD");
        service.transfer(new TransferRequest(from, to, new BigDecimal("10.00"), "order"));
        verify(writer).applyBalanceChanges(java.util.List.of(
            new PostingWriter.BalanceChange(to, new BigDecimal("10.00")),
            new PostingWriter.BalanceChange(from, new BigDecimal("-10.00"))));
    }
    @Test void insufficientFundsDoesNotCreditTarget() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "10.00", "CAD");
        account(to, "100.00", "CAD");
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("200.00"), "too-much")));
        verify(writer, never()).applyBalanceChanges(anyList());
        verify(writer, never()).post(any());
    }
    @Test void staleReadIsOverruledByTheConditionalDebit() {
        // The unlocked read says there is enough, but by the time of the debit there is not.
        UUID from = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID to = UUID.fromString("22222222-2222-4222-8222-222222222222");
        account(from, "500.00", "CAD");
        account(to, "0.00", "CAD");
        when(writer.applyBalanceChanges(anyList())).thenReturn(new boolean[] { false, true });
        // The debit did not apply, so the transfer fails (and the transaction rolls back).
        assertThrows(BankingException.class, () -> service.transfer(
            new TransferRequest(from, to, new BigDecimal("200.00"), "stale")));
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
        verify(writer, never()).post(any());
    }
    @Test void duplicateRequestReturnsOriginal() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.TRANSFER, from, to,
            new BigDecimal("5.00"), "CAD", "repeat");
        when(transactions.findByRequestKey("repeat")).thenReturn(Optional.of(previous));
        var result = service.transfer(new TransferRequest(from, to, new BigDecimal("5.00"), "repeat"));
        assertEquals(previous.getId(), result.id());
        verify(writer, never()).post(any());
        verify(writer, never()).applyBalanceChanges(anyList());
    }
    @Test void simultaneousDuplicateIsReplayedFromTheCommittedRow() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        account(from, "100.00", "CAD"); account(to, "0.00", "CAD");
        BankingTransaction winner = new BankingTransaction(TransactionType.TRANSFER, from, to,
            new BigDecimal("5.00"), "CAD", "dup");
        // First lookup: nothing committed yet. Insert hits the unique index. Second lookup: the winner.
        when(transactions.findByRequestKey("dup")).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new org.springframework.dao.DuplicateKeyException("request_key")).when(writer).post(any());
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
        verify(writer, never()).post(any());
    }
    @Test void depositKeyReusedForWithdrawalIsRejected() {
        UUID id = UUID.randomUUID();
        BankingTransaction previous = new BankingTransaction(TransactionType.DEPOSIT, null, id,
            new BigDecimal("5.00"), "CAD", "dep");
        when(transactions.findByRequestKey("dep")).thenReturn(Optional.of(previous));
        assertThrows(BankingException.class,
            () -> service.withdraw(id, new MoneyRequest(new BigDecimal("5.00"), "dep")));
        verify(writer, never()).post(any());
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
