package com.bank.core;

/**
 * A ledger entry against an account.
 *
 * @param id           transaction id
 * @param accountId    account this entry belongs to
 * @param type         DEBIT / CREDIT
 * @param amountCents  amount in cents (always positive; sign implied by type)
 * @param counterparty the other side of the transaction
 * @param ts           ISO-8601 timestamp
 */
public record Transaction(String id, String accountId, String type, long amountCents,
                          String counterparty, String ts) {
}
