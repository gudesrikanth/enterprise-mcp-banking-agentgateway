# 09 — End-to-end flow (one request, fully annotated)

What happens when an agent calls `transfer_funds` through the platform on AKS.

```
[1] Agent gets an Entra JWT
    POST https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token
    grant_type=client_credentials, scope=api://<clientId>/.default
    → JWT  { aud:<clientId>, iss:.../v2.0, roles:["payments-admin"], oid, tid }

[2] Agent → Azure Load Balancer  (INGRESS LB)
    http(s)://<gw-ip>:8080/mcp   Authorization: Bearer <jwt>
    LB spreads to one agentgateway-proxy replica

[3] agentgateway-proxy  (AI EDGE — all policy here)
    a. JWT auth (bank-jwt): verify sig vs Entra JWKS, iss, aud     → 401 if bad
    b. RBAC  (bank-rbac):  "payments-admin" in jwt.roles?          → 403 if not
       (a teller token would have transfer_funds HIDDEN from tools/list)
    c. rate limit (bank-ratelimit): transfer_funds ≤ 3/min         → 429 if over
    d. federation: "payments_transfer_funds" → target=payments, tool=transfer_funds
    e. telemetry (bank-telemetry): log user.oid/roles/tenant + mcp.tool

[4] proxy → mcp-payments   (EAST-WEST via Istio ambient)
    ztunnel mTLS; waypoint (Envoy) AuthorizationPolicy: only the gateway SA may call
    LB across mcp-payments replicas

[5] mcp-payments → bank-core   (mTLS + LB in the mesh)
    POST /api/transfers → debit/credit, balance mutates live

[6] (if a tool needed an external vendor) EGRESS
    Istio egress gateway (Envoy) / agentgateway remote-MCP → out the NAT gateway IP

[7] response unwinds; one enriched audit log line ties it all together
```

## The same flow as commands (works on minikube too, port-forward :8080)
```bash
URL=http://localhost:8080/mcp
ACC='Accept: application/json, text/event-stream'; CT='Content-Type: application/json'
AUTH="Authorization: Bearer $TOKEN"     # a real Entra token

# handshake
SID=$(curl -s -D - -o /dev/null -H "$ACC" -H "$CT" -H "$AUTH" -X POST $URL \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"demo","version":"1"}}}' \
  | awk 'tolower($1)=="mcp-session-id:"{print $2}' | tr -d '\r')
curl -s -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST $URL \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null

# tools/list (payments-admin sees transfer_funds; teller would not)
curl -s -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST $URL \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# the transfer
curl -s -H "$ACC" -H "$CT" -H "$AUTH" -H "mcp-session-id: $SID" -X POST $URL \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"payments_transfer_funds","arguments":{"fromAccountId":"ACC-1001","toAccountId":"ACC-1002","amountCents":25000}}}'
```

## Expected outcomes (the demo matrix)
| Token | tools/list | transfer_funds | accounts_get_balance |
|---|---|---|---|
| none | 401 | 401 | 401 |
| teller | no `payments_transfer_funds` | **403** | ✅ |
| payments-admin | all 6 tools | ✅ (then **429** after 3/min) | ✅ |

## Verified so far (minikube)
[2]–[5] without auth: federation + real-time transfer ✅. [3a] JWT deny: 401 ✅.
[3b/c] allow + RBAC + rate-limit: pending a fresh Entra token. Mesh hops [4]/[5]
and egress [6] are AKS-targeted (managed Istio), config-validated.
