package com.bank.core;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory bank. Seeded with a few accounts and transactions. A transfer
 * mutates two balances and appends two ledger entries with the current
 * timestamp, so reads reflect changes in real time (no database needed).
 *
 * <p>Thread-safety is coarse (synchronized transfer) — fine for a demo.
 */
@Service
public class BankService {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final List<Transaction> ledger = new ArrayList<>();
    private final AtomicLong txnSeq = new AtomicLong(1000);

    public BankService() {
        seedAccount("ACC-1001", "Ada Lovelace", "CHECKING", 4_250_00);
        seedAccount("ACC-1002", "Alan Turing", "SAVINGS", 18_900_00);
        seedAccount("ACC-1003", "Grace Hopper", "CHECKING", 7_010_00);
        // a little starting history
        post("ACC-1001", "CREDIT", 1_200_00, "Payroll");
        post("ACC-1002", "DEBIT", 45_00, "Coffee Bar");
        post("ACC-1003", "CREDIT", 300_00, "Refund");
    }

    private void seedAccount(String id, String owner, String type, long cents) {
        accounts.put(id, new Account(id, owner, type, cents, "USD", Instant.now().toString()));
    }

    /** All accounts (snapshot). */
    public List<Account> accounts() {
        return new ArrayList<>(accounts.values());
    }

    /** One account, or {@code null} if unknown. */
    public Account account(String id) {
        return accounts.get(id == null ? "" : id.trim().toUpperCase());
    }

    /** Most-recent-first transactions for an account. */
    public List<Transaction> transactions(String accountId) {
        String key = accountId == null ? "" : accountId.trim().toUpperCase();
        return ledger.stream()
                .filter(t -> t.accountId().equals(key))
                .sorted(Comparator.comparing(Transaction::ts).reversed())
                .toList();
    }

    /**
     * Move money between two accounts. Returns the debit transaction on the
     * source account. Throws {@link IllegalArgumentException} for unknown
     * accounts, non-positive amounts, or insufficient funds.
     */
    public synchronized Transaction transfer(String fromId, String toId, long amountCents) {
        Account from = account(fromId);
        Account to = account(toId);
        if (from == null) throw new IllegalArgumentException("unknown source account: " + fromId);
        if (to == null) throw new IllegalArgumentException("unknown destination account: " + toId);
        if (from.id().equals(to.id())) throw new IllegalArgumentException("source and destination are the same");
        if (amountCents <= 0) throw new IllegalArgumentException("amountCents must be positive");
        if (from.balanceCents() < amountCents) throw new IllegalArgumentException("insufficient funds");

        updateBalance(from, -amountCents);
        updateBalance(to, +amountCents);
        post(to.id(), "CREDIT", amountCents, from.owner());
        return post(from.id(), "DEBIT", amountCents, to.owner());
    }

    private void updateBalance(Account a, long deltaCents) {
        accounts.put(a.id(), new Account(a.id(), a.owner(), a.type(),
                a.balanceCents() + deltaCents, a.currency(), Instant.now().toString()));
    }

    private Transaction post(String accountId, String type, long amountCents, String counterparty) {
        Transaction t = new Transaction("TXN-" + txnSeq.incrementAndGet(),
                accountId, type, amountCents, counterparty, Instant.now().toString());
        ledger.add(t);
        return t;
    }
}
