# 05 — Federation (one endpoint, two MCP servers)

`k8s/20-federation.yaml` turns the two MCP servers into one **virtual MCP
endpoint**.

## How it works
- `EnterpriseAgentgatewayBackend bank-tools` lists two **static** targets
  (accounts → `mcp-accounts.bank:8090/mcp`, payments → `mcp-payments.bank:8091/mcp`),
  `protocol: StreamableHTTP`, `failureMode: FailOpen` (a dead target doesn't break
  the union).
- An `HTTPRoute bank-mcp` (`parentRefs: agentgateway-proxy`) routes the gateway's
  MCP traffic to that backend.
- The proxy **prefixes** tools by target name and **routes `tools/call` by the
  prefix**: `accounts_get_account`, `payments_transfer_funds`, etc.

## The MCP servers are marked as MCP upstreams
Each Service port carries `appProtocol: kgateway.dev/mcp` so the proxy speaks MCP
to them.

## Verified (minikube)
`tools/list` → 6 tools: `accounts_get_account`, `accounts_get_balance`,
`accounts_list_accounts`, `accounts_list_transactions`, `payments_list_transfers`,
`payments_transfer_funds`. A `payments_transfer_funds` call routed to mcp-payments
→ bank-core and posted a real transfer.

## Static vs selector targets
We use **static** (explicit Service FQDN) for clarity. For auto-discovery at scale,
use `selector` targets (label match); then the prefix is `<service>-<port>_<tool>`
and `mcp.tool.target` in CEL is that string. See the workshop's
`mcp-tool-federation.md`.

## Adding a third tool server later
Add a target to `bank-tools.spec.mcp.targets` and re-apply — clients keep one
endpoint; no client changes. That's the federation payoff.
