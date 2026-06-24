package com.bank.mcp.accounts;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Read-only banking tools, backed by bank-core's REST API. These are the
 * low-risk tools a "teller" persona is allowed to call.
 */
@Component
public class AccountTools {

    public record AccountDto(String id, String owner, String type, long balanceCents,
                             String currency, String updatedAt) {}
    public record TransactionDto(String id, String accountId, String type, long amountCents,
                                 String counterparty, String ts) {}

    private final RestClient rest;

    public AccountTools(@Value("${bank.core-url:http://localhost:8081}") String baseUrl) {
        this.rest = RestClient.create(baseUrl);
    }

    @McpTool(name = "list_accounts", description = "List all bank accounts with balances.")
    public List<AccountDto> listAccounts() {
        return rest.get().uri("/api/accounts").retrieve()
                .body(new ParameterizedTypeReference<List<AccountDto>>() {});
    }

    @McpTool(name = "get_account", description = "Get one account by id, e.g. ACC-1001.")
    public AccountDto getAccount(
            @McpToolParam(description = "Account id, e.g. ACC-1001") String accountId) {
        return rest.get().uri("/api/accounts/{id}", accountId).retrieve().body(AccountDto.class);
    }

    @McpTool(name = "get_balance", description = "Get the current balance for an account.")
    public Map<String, Object> getBalance(
            @McpToolParam(description = "Account id, e.g. ACC-1001") String accountId) {
        AccountDto a = getAccount(accountId);
        return Map.of("accountId", a.id(), "balanceCents", a.balanceCents(),
                "currency", a.currency(), "asOf", a.updatedAt());
    }

    @McpTool(name = "list_transactions", description = "List recent transactions for an account (most recent first).")
    public List<TransactionDto> listTransactions(
            @McpToolParam(description = "Account id, e.g. ACC-1001") String accountId) {
        return rest.get().uri("/api/accounts/{id}/transactions", accountId).retrieve()
                .body(new ParameterizedTypeReference<List<TransactionDto>>() {});
    }
}
