# 06 — Security: Entra JWT auth + persona/role RBAC

Two policies turn the open federation into a zero-trust, role-gated tool surface.

## JWT authentication (`k8s/30-jwt-entra.yaml`)
- `EnterpriseAgentgatewayPolicy bank-jwt`, attached to the **Gateway**, `mode: Strict`.
- Validates every request's `Authorization: Bearer` against Microsoft Entra:
  - issuer `https://login.microsoftonline.com/<tenant>/v2.0`
  - audiences: the app's GUID **and** `api://<clientId>` (Entra app-only tokens put
    the bare GUID in `aud`)
  - JWKS: **inlined** here (self-contained). Entra rotates keys ~6 weeks; for prod
    use `jwks.remote.backendRef` (auto-refresh) — schema is
    `jwks.{inline | remote.{backendRef, cacheDuration, jwksPath}}`.
- **Verified:** no token → `401 no bearer token found`; malformed → `401 token
  header is malformed`.

> Gotcha: `jwks.url` is **not** a field on this version — it's `inline` or `remote`.

## Persona / role RBAC (`k8s/31-rbac-personas.yaml`)
- `EnterpriseAgentgatewayPolicy bank-rbac`, attached to the **Backend**
  (`spec.backend.mcp.authorization`), applied to **both `tools/list` and
  `tools/call`** → personas get a *filtered tool catalog*, not just blocked calls.
- Entra **app roles** arrive in the `roles` claim → `jwt.roles` (a list). Rule:
  ```
  ("payments-admin" in jwt.roles) ||
  ("teller" in jwt.roles && mcp.tool.name != "transfer_funds")
  ```
  → `payments-admin` = all tools; `teller` = everything except `transfer_funds`;
  no banking role = nothing.

> Two CEL gotchas (verified against the workshop):
> - `mcp.tool.name` is the **upstream-native** name (`transfer_funds`), **not** the
>   federation-prefixed `payments_transfer_funds`.
> - For per-backend filtering use `mcp.tool.target` (the target name).

## Test it (with a real Entra token)
```bash
TOKEN=$(curl -s -X POST "https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token" \
  -d grant_type=client_credentials -d client_id=<clientId> -d client_secret=<secret> \
  -d scope=api://<clientId>/.default | jq -r .access_token)

# payments-admin token: tools/list shows transfer_funds AND the call succeeds.
# teller-only token:     tools/list HIDES payments_transfer_funds; calling it → 403.
# no token:              401.
```

## Defense in depth
JWT + RBAC is the **edge** control (who/what may call which tool). The Istio mesh
(docs/11) adds **east-west** mTLS + AuthorizationPolicy (which workload may reach
which service) — a stolen pod identity still can't reach bank-core directly.
