package com.bank.mcp.accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** MCP server (Streamable HTTP, /mcp) exposing read-only account tools. */
@SpringBootApplication
public class McpAccountsApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpAccountsApplication.class, args);
    }
}
