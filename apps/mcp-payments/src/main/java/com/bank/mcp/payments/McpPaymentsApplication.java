package com.bank.mcp.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** MCP server (Streamable HTTP, /mcp) exposing payment/transfer tools. */
@SpringBootApplication
public class McpPaymentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpPaymentsApplication.class, args);
    }
}
