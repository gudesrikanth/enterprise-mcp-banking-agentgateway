# Bank MCP Platform on AKS — Overview & Architecture

A presentable, enterprise-standard reference deployment: a retail-bank **MCP tool
platform** running on **Azure Kubernetes Service (AKS)**, fronted by **Solo
enterprise agentgateway**, with **Microsoft Entra ID** JWT auth, **persona/role
based tool authorization**, **per-tool rate limiting**, and **custom telemetry**.

This is the "why and what." The numbered docs that follow are the "how":

| Doc | Covers |
|---|---|
| `00-overview-and-architecture.md` | this file — the big picture + request flow |
| `01-domain-and-apps.md` | the bank domain + the 3 services (bank-core, mcp-accounts, mcp-payments) |
| `02-azure-infra-aks-acr.md` | Terraform: AKS + ACR, what it provisions, how to run it |
| `03-entra-jwt-setup.md` | Entra app registration, app roles, audience, issuer/JWKS — **do this first** |
| `04-agentgateway-install-on-aks.md` | install Gateway API CRDs + enterprise-agentgateway on AKS |
| `05-federation-backend-route.md` | federate the 2 MCP servers into one endpoint |
| `06-security-jwt-rbac-personas.md` | Entra JWT + persona/role tool filtering (CEL) |
| `07-rate-limiting.md` | per-tool, per-identity quotas |
| `08-observability-telemetry.md` | metrics, traces, custom access-log enrichment |
| `09-end-to-end-flow.md` | one annotated request, client → Entra → gateway → tool → core |
| `10-presentation-script.md` | how to demo/present this in ~15 minutes |

## The business story (what you present)

> A bank wants its AI agents (and partner agents) to use internal capabilities —
> *check a balance*, *list transactions*, *move money* — as **MCP tools**. Every
> call must be **authenticated** (Entra ID), **authorized to the tool level**
> (a teller can read; only a payments-admin can transfer), **rate-limited** on
> sensitive operations, and **fully audited** with the **human identity**
> attached. Agents integrate with **one stable endpoint**; the bank gets one
> control point for security, governance, and observability.

## Components

| Layer | Component | Role |
|---|---|---|
| System of record | **bank-core** (Spring Boot, REST) | accounts + transactions, live balances |
| Tool servers | **mcp-accounts** (MCP) | read tools: get_account, list_accounts, get_balance, list_transactions |
| | **mcp-payments** (MCP) | write tool: transfer_funds (+ list_transfers) |
| Data plane | **agentgateway-proxy** | one MCP endpoint; federates the 2 servers; enforces policy |
| Control plane | **enterprise-agentgateway controller** | reconciles CRDs → programs the proxy |
| Identity | **Microsoft Entra ID** | issues JWTs; app roles drive authorization |
| Platform | **AKS** + **ACR** | managed Kubernetes + private image registry |

## Topology

```
                         Microsoft Entra ID  (issuer + JWKS + app roles)
                                 │  OIDC login → JWT (roles claim)
   AI agent / Claude / ───JWT──▶ │
   partner client                ▼
                    ┌───────────────────────────────────────────┐
                    │           agentgateway-proxy (AKS)         │
                    │  1 JWT auth (Entra JWKS)                   │
                    │  2 RBAC: role → allowed tools (CEL)        │
                    │  3 rate limit: transfer_funds per identity │
                    │  4 telemetry: log/trace + Entra claims     │
                    │  5 federate + route by tool prefix         │
                    └───────────┬───────────────┬───────────────┘
                                │               │
                        accounts_*          payments_*
                                ▼               ▼
                        ┌──────────────┐  ┌──────────────┐
                        │ mcp-accounts │  │ mcp-payments │   (AKS pods)
                        └──────┬───────┘  └──────┬───────┘
                               └────────┬────────┘
                                        ▼
                                  ┌──────────┐
                                  │ bank-core│  accounts + transactions (live)
                                  └──────────┘
```

## End-to-end flow (summary — full version in `09`)

1. The agent obtains an **Entra JWT** (OIDC; the token carries `roles`, `tid`, `oid`, etc.).
2. It calls the **gateway** `/mcp` with `Authorization: Bearer <jwt>`.
3. The proxy validates the JWT against **Entra's JWKS** (issuer `https://login.microsoftonline.com/<tenant>/v2.0`). Bad/missing → **401**.
4. **RBAC (CEL)**: e.g. `payments_transfer_funds` requires `"payments-admin" in jwt.roles`; otherwise **403** — and `mcp-payments` never sees the call.
5. **Rate limit**: `transfer_funds` is capped per identity (e.g. ≤ 5/min) → **429** when exceeded.
6. The proxy strips the prefix, routes `accounts_*`→mcp-accounts / `payments_*`→mcp-payments, which call **bank-core**.
7. The proxy emits a **trace + access log** enriched with the caller's Entra claims (user/role/tenant) for audit.

## Why each enterprise piece exists

| Concern | Without a gateway | With agentgateway |
|---|---|---|
| Auth | every MCP server validates Entra JWTs itself | one JWT policy at the edge |
| Tool-level authz | each server hard-codes role checks | CEL on `jwt.roles` + `mcp.tool.name`, centrally |
| Abuse / fair-use | per-server, inconsistent | per-tool, per-identity quotas |
| Audit / attribution | scattered logs, service identity only | one trace stream with the **human** identity |
| Onboarding a new tool server | clients re-integrate | add a target to the federation CRD |

## Verify-locally-first principle

The Kubernetes + gateway + JWT/RBAC/rate-limit/telemetry layer is **identical**
on minikube and AKS (same enterprise-agentgateway). So we prove the entire
policy surface on the local cluster first, then the only AKS-specific delta is:
images come from **ACR** and Entra tokens are **real**. See `02` and `04`.
