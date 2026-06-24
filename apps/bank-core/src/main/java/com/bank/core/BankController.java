package com.bank.core;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** REST face of the bank core. The MCP servers call these endpoints. */
@RestController
@RequestMapping("/api")
public class BankController {

    /** Body for POST /api/transfers. */
    public record TransferRequest(String fromAccountId, String toAccountId, long amountCents) {}

    private final BankService bank;

    public BankController(BankService bank) {
        this.bank = bank;
    }

    @GetMapping("/accounts")
    public List<Account> accounts() {
        return bank.accounts();
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<Account> account(@PathVariable String id) {
        Account a = bank.account(id);
        return a == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(a);
    }

    @GetMapping("/accounts/{id}/transactions")
    public ResponseEntity<List<Transaction>> transactions(@PathVariable String id) {
        if (bank.account(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(bank.transactions(id));
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(@RequestBody TransferRequest req) {
        try {
            Transaction debit = bank.transfer(req.fromAccountId(), req.toAccountId(), req.amountCents());
            Account from = bank.account(req.fromAccountId());
            return ResponseEntity.ok(Map.of(
                    "transaction", debit,
                    "sourceBalanceCents", from.balanceCents(),
                    "status", "POSTED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "status", "REJECTED"));
        }
    }
}
