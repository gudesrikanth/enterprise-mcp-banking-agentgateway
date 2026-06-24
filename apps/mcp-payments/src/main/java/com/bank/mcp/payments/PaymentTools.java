package com.bank.mcp.payments;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Payment tools, backed by bank-core. {@code transfer_funds} is a sensitive
 * write operation — this is the tool we gate with role-based authorization
 * (only "payments-admin") and rate-limit at the gateway.
 */
@Component
public class PaymentTools {

    public record TransactionDto(String id, String accountId, String type, long amountCents,
                                 String counterparty, String ts) {}
    private record TransferRequest(String fromAccountId, String toAccountId, long amountCents) {}

    private final RestClient rest;

    public PaymentTools(@Value("${bank.core-url:http://localhost:8081}") String baseUrl) {
        this.rest = RestClient.create(baseUrl);
    }

    @McpTool(name = "transfer_funds",
             description = "Move money between two accounts. SENSITIVE: requires the payments-admin role.")
    public String transferFunds(
            @McpToolParam(description = "Source account id, e.g. ACC-1001") String fromAccountId,
            @McpToolParam(description = "Destination account id, e.g. ACC-1002") String toAccountId,
            @McpToolParam(description = "Amount to move, in cents (integer minor units)") long amountCents) {
        var req = new TransferRequest(fromAccountId, toAccountId, amountCents);
        // exchange() so a 400 (insufficient funds, unknown account) returns its
        // JSON body to the caller instead of throwing.
        return rest.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .exchange((request, response) ->
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @McpTool(name = "list_transfers", description = "List recent transactions (transfer history) for an account.")
    public List<TransactionDto> listTransfers(
            @McpToolParam(description = "Account id, e.g. ACC-1001") String accountId) {
        return rest.get().uri("/api/accounts/{id}/transactions", accountId).retrieve()
                .body(new ParameterizedTypeReference<List<TransactionDto>>() {});
    }
}
