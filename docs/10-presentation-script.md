# 10 — Presentation script (~15 min)

A tight narrative for demoing the platform to engineers/architects/stakeholders.

## 0. One-line framing (30s)
> "A bank wants AI agents to use banking capabilities as tools — safely. This is
> one **governed endpoint** for all MCP tools: Entra-authenticated, role-authorized
> to the individual tool, rate-limited, mesh-encrypted, and fully audited — without
> changing any tool server."

## 1. The problem (1 min)
Without a gateway: every agent talks to every tool server directly; auth, limits,
and audit are reinvented per server. Show the topology diagram (`docs/00`).

## 2. The apps (1 min)
`bank-core` + two MCP servers (`mcp-accounts` read, `mcp-payments` write). Note the
sensitive one: `transfer_funds`. (`docs/01`.)

## 3. Federation — one endpoint (2 min)
`kubectl apply -f k8s/20-federation.yaml`. Connect MCP Inspector to the gateway →
`tools/list` shows `accounts_*` + `payments_*`. "Two servers, one endpoint, names
prefixed, routed by prefix." Call `accounts_list_accounts`.

## 4. Identity — Entra JWT (2 min)
Apply `30-jwt-entra.yaml`. Re-try **with no token → 401**. "The bank's IdP (Entra)
is the authority; the gateway validates every call against Entra's JWKS." Then add
a real Bearer token in Inspector → works.

## 5. Authorization — personas (3 min) ← the money shot
Apply `31-rbac-personas.yaml`.
- **teller** token: `tools/list` **hides** `payments_transfer_funds`; calling it → **403**.
- **payments-admin** token: sees all tools; `transfer_funds` succeeds → balance moves live.
"Same endpoint, same backend — the *tool surface itself* changes per role, decided
from the Entra `roles` claim. Filtered at `tools/list`, enforced at `tools/call`."

## 6. Abuse control — rate limit (1.5 min)
Apply `40-rate-limit.yaml`. Call `transfer_funds` 4× fast → **429** on the 4th.
"Sensitive write capped per minute; a runaway agent can't hammer it."

## 7. Audit — telemetry (1.5 min)
Apply `50-telemetry.yaml`. Tail the proxy logs → each line shows `user.oid`,
`user.roles`, `mcp.tool`, status, latency. On AKS show the Solo UI / Grafana.
"Who moved money, on whose behalf, when — one audit trail."

## 8. The network story (2 min)
Diagram from `docs/11`: **Azure LB → agentgateway → Istio/Envoy mesh → services**,
egress via egress gateway + NAT. "Four layers: ingress LB, AI edge gateway, mTLS
service mesh east-west, controlled egress. agentgateway is the AI-aware L7; Istio's
Envoy is the mesh. Defense in depth — JWT at the edge **and** mTLS+authz inside."

## 9. How it's run (1 min)
`infra/terraform/` → AKS + ACR + managed Istio + NAT (one `tofu apply`). GitOps the
CRDs. "Config is declarative; clients integrate once; we add tool servers by editing
one federation resource."

## 10. Close (30s)
> "One control point for security, governance, and observability across every MCP
> tool — internal and external — so the bank can adopt agentic AI without losing
> control of who can do what."

---

### Demo pre-flight checklist
- [ ] Gateway + apps deployed, `tools/list` returns 6 tools (no auth) first
- [ ] A **teller** token and a **payments-admin** token minted and fresh (≤1h)
- [ ] Proxy logs tailing in a side terminal
- [ ] (AKS) Solo UI / Grafana port-forwarded
- [ ] Apply policies live (don't pre-apply) so the audience sees each gate "click on"
