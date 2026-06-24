# 07 — Per-tool rate limiting

`k8s/40-rate-limit.yaml` caps the sensitive `transfer_funds` tool.

## Conditional rate limit (what we use)
`EnterpriseAgentgatewayPolicy bank-ratelimit`, attached to the Gateway:
```yaml
traffic:
  rateLimit:
    conditional:
      - condition: 'mcp.tool.name == "transfer_funds"'   # only this tool
        policy:
          local:
            - requests: 3
              unit: Minutes        # enum: Hours | Minutes | Seconds
```
A **local** token bucket lives on the proxy — simplest form, no external service.
3 transfers/min aggregate; the 4th in a minute → **429**.

## Per-identity quotas (production)
`local` is aggregate. To key the limit on the **caller** (per user/team), use
`policy.global` with `descriptors` (keyed on a `jwt` claim) backed by the
`rate-limiter` service:
```yaml
policy:
  global:
    backendRef: { name: rate-limiter-enterprise-agentgateway, namespace: agentgateway-system, port: 8081, group: "", kind: Service }
    domain: bank
    descriptors:
      - entries:
          - { ... key on jwt.oid / jwt.roles ... }
        # requests/unit via a RateLimitConfig
```
(See the workshop `mcp-tool-rate-limiting.md` for the exact descriptor/RateLimitConfig
shape on your version.)

## Test (with a payments-admin token)
```bash
# call payments_transfer_funds 4 times within a minute → 4th returns 429
```
Enforced **before** the tool reaches mcp-payments, so a runaway agent can't hammer
the write path.

> Schema: `traffic.rateLimit.{local[], global{backendRef,descriptors,domain,failureMode}, conditional[{condition, policy}]}`.
