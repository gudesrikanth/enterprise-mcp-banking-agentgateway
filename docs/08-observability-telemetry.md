# 08 — Observability & custom telemetry

The gateway is the one place that sees every tool call with the caller's identity
— so it's where audit/attribution lives.

## Custom access-log enrichment (`k8s/50-telemetry.yaml`)
`EnterpriseAgentgatewayPolicy bank-telemetry`, attached to the Gateway, adds
Entra + MCP fields to the proxy access logs via CEL:
```yaml
frontend:
  accessLog:
    attributes:
      add:
        - { name: user.roles,  expression: jwt.roles }
        - { name: user.oid,    expression: jwt.oid }     # the principal (object id)
        - { name: user.appid,  expression: jwt.appid }
        - { name: user.tenant, expression: jwt.tid }
        - { name: mcp.tool,    expression: mcp.tool.name }
        - { name: mcp.target,  expression: mcp.tool.target }
```
Now every line answers **who** (Entra identity) called **which tool** on **which
backend**, allow/denied, with latency. View:
```bash
kubectl -n agentgateway-system logs -l app.kubernetes.io/name=agentgateway-proxy --tail=50
```

## Built-in signals
- **Metrics:** Prometheus at the proxy `:15020` (request rates, tool calls, MCP
  metrics).
- **Traces:** OpenTelemetry with MCP spans (`mcp.method`, `mcp.tool.name`,
  `mcp.target`) → the telemetry collector → Grafana/Tempo and the **Solo UI**.
- The install also brings a `tracing` policy (`frontend.tracing` → the collector).

## Note for this environment
On minikube we **scaled the UI / clickhouse / telemetry collector to 0** to save
resources, so the dashboards aren't up locally — the *access-log enrichment* still
works (stdout). On AKS, leave them running (sized per `docs/02`) for full Grafana/
Tempo dashboards and the Solo UI:
```bash
kubectl -n agentgateway-system port-forward svc/solo-enterprise-ui 9000:9000
```

## Why this matters for a bank
Regulators ask "who moved money, on whose behalf, when?" With OBO (future work)
the `user.oid` is the **human**, not a service account — real attribution. The
enriched logs/traces are the audit trail.
