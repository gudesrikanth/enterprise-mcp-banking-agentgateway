# 01 — Domain & apps

Three Spring Boot (Java 25 / Boot 4) services in `apps/`. The bank is in-memory
(no DB) so balances change in real time without infra.

## bank-core (`:8081`) — system of record
- `Account(id, owner, type, balanceCents, currency, updatedAt)` — money in integer
  cents (no float drift).
- `Transaction(id, accountId, type, amountCents, counterparty, ts)`.
- Seeded: `ACC-1001` Ada (CHECKING $4,250), `ACC-1002` Alan (SAVINGS $18,900),
  `ACC-1003` Grace (CHECKING $7,010).
- REST:
  | Method | Path | |
  |---|---|---|
  | GET | `/api/accounts` | all accounts |
  | GET | `/api/accounts/{id}` | one account (404 unknown) |
  | GET | `/api/accounts/{id}/transactions` | ledger, newest first |
  | POST | `/api/transfers` | `{fromAccountId,toAccountId,amountCents}` → debits/credits, **mutates balances live**, returns the debit txn + new source balance; 400 on insufficient funds / unknown account |
- Actuator liveness/readiness for k8s probes.

## mcp-accounts (`:8090/mcp`) — read tools (teller-safe)
Spring AI MCP server; tools call bank-core via `RestClient`:
`list_accounts`, `get_account`, `get_balance`, `list_transactions`.

## mcp-payments (`:8091/mcp`) — the sensitive write tool
`transfer_funds(fromAccountId, toAccountId, amountCents)` (uses
`RestClient.exchange` so a 400 returns its JSON body to the caller instead of
throwing) and `list_transfers`. `transfer_funds` is what we gate with RBAC and
rate-limit.

## Why two MCP servers?
To demonstrate **federation** (one endpoint, two backends) and **tool-level
policy** (read tools vs the sensitive write). Through the gateway the tools are
prefixed: `accounts_*` and `payments_*`.

## Build & images
Each app has a multi-stage `Dockerfile` (temurin 25 build → jre runtime, shared
`.m2` BuildKit cache). Local:
```bash
docker build -t bank/<svc>:0.1.0 apps/<svc>
minikube image load bank/<svc>:0.1.0      # local
# AKS: az acr build -r <acr> -t <acr>/<svc>:0.1.0 apps/<svc>   (docs/02)
```

## Verified (minikube)
`accounts_list_accounts` returns the three accounts; `payments_transfer_funds`
of $250 moved ACC-1001 from `425000`→`400000` cents and posted `TXN-1005`.
