package com.bank.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Retail-bank core service. Holds accounts and transactions in memory and
 * serves them over REST. Balances change in real time when a transfer is
 * posted. This is the system-of-record that the two MCP servers wrap as tools.
 */
@SpringBootApplication
public class BankCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankCoreApplication.class, args);
    }
}
