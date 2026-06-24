package com.bank.core;

/**
 * A customer account. Money is stored in integer minor units (cents) to avoid
 * floating-point drift — standard practice for financial data.
 *
 * @param id          account id, e.g. "ACC-1001"
 * @param owner       account holder name
 * @param type        CHECKING / SAVINGS
 * @param balanceCents current balance in cents
 * @param currency    ISO-4217 code, e.g. "USD"
 * @param updatedAt   ISO-8601 timestamp of the last balance change
 */
public record Account(String id, String owner, String type, long balanceCents,
                      String currency, String updatedAt) {
}
