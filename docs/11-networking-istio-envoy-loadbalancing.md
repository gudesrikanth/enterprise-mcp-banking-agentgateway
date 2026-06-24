# 11 — Networking: Ingress/Egress LB, Istio, Envoy (and how agentgateway fits)

This is the networking blueprint for the bank platform on AKS. The key idea:
**agentgateway, Istio, Envoy, and the Azure load balancers are four cooperating
layers — not competing choices.** Each owns a different slice of traffic and does
load balancing at a different point.

## The four layers

```
        Internet / partners / internal agents
                       │
            ┌──────────▼───────────┐  (1) INGRESS LB  — L4 Azure Load Balancer
            │  Azure Load Balancer │      (or Application Gateway for L7/WAF)
            └──────────┬───────────┘      spreads traffic across proxy replicas
                       │
            ┌──────────▼───────────┐  (2) AI EDGE GATEWAY — agentgateway (Rust)
            │   agentgateway-proxy │      MCP-aware: JWT (Entra), RBAC, rate-limit,
            │   (2+ replicas)      │      federation, telemetry; LB across MCP backends
            └──────────┬───────────┘
                       │  mTLS (joined to the mesh)
        ┌──────────────┼───────────────────────────┐  (3) SERVICE MESH (east-west)
        │      Istio ambient  —  Envoy data plane   │      ztunnel (L4 mTLS+LB) +
        │   ztunnel (per node) + waypoint (L7)      │      Envoy waypoint (L7 policy)
        └───┬───────────────┬───────────────┬───────┘
            ▼               ▼               ▼
      mcp-accounts     mcp-payments      bank-core      (each: N replicas, mTLS, LB)
            │
            ▼  (4) EGRESS — Istio egress gateway (Envoy) + Azure NAT gateway
      external vendor MCP / APIs   (controlled, load-balanced, fixed egress IP)
```

| # | Layer | Proxy | Where it load-balances |
|---|---|---|---|
| 1 | **Ingress LB** | Azure LB (L4) / App Gateway (L7) | across agentgateway proxy replicas |
| 2 | **AI edge gateway** | **agentgateway** (Rust, *not* Envoy) | across MCP backend endpoints/replicas |
| 3 | **Service mesh** | **Istio** → **Envoy** (ambient: ztunnel + waypoint) | across pod replicas of each service, with mTLS |
| 4 | **Egress** | Istio egress gateway (**Envoy**) / Azure NAT | across external upstream endpoints; stable egress IP |

> **Envoy vs agentgateway.** Envoy is Istio's data-plane proxy (sidecar, or in
> *ambient* mode the ztunnel + waypoint). agentgateway is a **separate** Rust proxy
> purpose-built for agent/MCP/LLM traffic. They are complementary: agentgateway is
> the L7 *AI* gateway at the edge; Envoy/Istio is the *service mesh* underneath.

---

## (1) Ingress load balancing

The `Gateway` (gatewayClassName `enterprise-agentgateway`) produces a Kubernetes
`Service`. On AKS, set it to `LoadBalancer` and AKS provisions an **Azure Load
Balancer** with a public (or internal) IP that spreads traffic across the proxy
replicas.

`EnterpriseAgentgatewayParameters` controls the service (we used ClusterIP +
port-forward on minikube; on AKS use LoadBalancer):

```yaml
spec:
  service:
    spec:
      type: LoadBalancer
    metadata:
      annotations:
        # INTERNAL Azure LB (private) — typical for a bank's internal/partner VNet:
        service.beta.kubernetes.io/azure-load-balancer-internal: "true"
        # health probe path for the LB:
        service.beta.kubernetes.io/azure-load-balancer-health-probe-request-path: /healthz
  deployment:
    spec:
      replicas: 2          # the LB spreads across these
```

**L7 / WAF option:** front the LB with **Azure Application Gateway** (WAF v2) for
TLS termination, WAF rules, and path routing before agentgateway — common for
internet-facing bank ingress. App Gateway → agentgateway Service.

**Two ingress gateways pattern (recommended for a bank):** one **internet-facing**
Gateway (partners, strict OAuth/WAF) and one **internal** Gateway (employee agents),
each its own `Gateway` + Azure LB, sharing the same backends/policies.

---

## (2) agentgateway load balancing & resilience

agentgateway itself load-balances across the **endpoints of each MCP backend**
(when a target is a Service with multiple pods, or a selector matching many pods).
It also applies retries, timeouts, and circuit breaking per backend — configured
on the `EnterpriseAgentgatewayBackend` / policies. This is L7 *MCP* load balancing
(by tool call), distinct from the mesh's L4/L7 service load balancing.

---

## (3) East-west service mesh — Istio ambient (Envoy)

Inside the cluster, traffic between agentgateway ⇄ mcp-accounts ⇄ mcp-payments ⇄
bank-core should be **mTLS-encrypted, authorized, and load-balanced**. That's the
service mesh's job. We use **Istio ambient mode**:

- **ztunnel** (a per-node L4 proxy, Rust) — transparent mTLS + L4 load balancing,
  no sidecars. Lightweight.
- **waypoint** (an **Envoy**-based per-namespace/service L7 proxy) — added only
  where you need L7 policy (HTTP routing, L7 authorization, richer telemetry).

**Why ambient (not sidecars):** no per-pod Envoy sidecar (lower memory/CPU — it
matters, as our minikube starvation showed), incremental adoption, and **Solo
co-created ambient**. agentgateway integrates with it natively.

### Enrollment is just a label
```yaml
# add bank-core, mcp-accounts, mcp-payments to the mesh — no pod changes:
metadata:
  labels:
    istio.io/dataplane-mode: ambient
```
Applied to the `bank` namespace (and `agentgateway-system`), every pod gets mTLS
via ztunnel automatically.

### agentgateway joins the mesh
The proxy joins ambient via its Deployment pod labels (set through
`EnterpriseAgentgatewayParameters` — the install manifest has this commented):
```yaml
spec:
  deployment:
    spec:
      template:
        metadata:
          labels:
            istio.io/dataplane-mode: ambient
```
Now the agentgateway→MCP-server hop is mTLS, and the mesh load-balances across
MCP-server replicas.

### Enforce mTLS + L7 authz
```yaml
apiVersion: security.istio.io/v1
kind: PeerAuthentication
metadata: { name: default, namespace: bank }
spec: { mtls: { mode: STRICT } }     # reject any non-mTLS traffic in the mesh
```
A **waypoint** (Envoy) for the `bank` namespace adds L7 authorization — e.g. "only
the agentgateway service account may call bank-core":
```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: bank-waypoint, namespace: bank }
spec:
  gatewayClassName: istio-waypoint
  listeners: [{ name: mesh, port: 15008, protocol: HBONE }]
```

**Defense in depth:** Entra JWT + RBAC at the agentgateway edge (who/what may call
which tool) **plus** mesh mTLS + AuthorizationPolicy east-west (which workload may
reach which service). A stolen pod identity still can't call bank-core directly.

---

## (4) Egress load balancing & control

When a bank agent calls an **external** MCP server or API (vendor market-data, KYC),
egress must be **controlled, observable, and from a stable IP** (vendor allow-lists).
Three cooperating mechanisms:

1. **agentgateway remote-MCP backend** — for *MCP* egress, the same gateway proxies
   the outbound MCP call over TLS, so auth/audit/rate-limit apply outbound too:
   ```yaml
   kind: EnterpriseAgentgatewayBackend
   spec:
     mcp:
       targets:
         - name: vendor
           static: { host: vendor-mcp.example.com, port: 443, protocol: StreamableHTTP, policies: { tls: {} } }
   ```
2. **Istio egress gateway (Envoy)** — route all mesh egress through a dedicated
   Envoy egress gateway for L7 policy, TLS origination, and load balancing across
   external endpoints. Declare external hosts with a `ServiceEntry`:
   ```yaml
   apiVersion: networking.istio.io/v1
   kind: ServiceEntry
   metadata: { name: vendor-mcp, namespace: bank }
   spec:
     hosts: ["vendor-mcp.example.com"]
     ports: [{ number: 443, name: https, protocol: TLS }]
     resolution: DNS
     location: MESH_EXTERNAL
   ```
   Pair with a `Sidecar`/egress `AuthorizationPolicy` to **deny-by-default** egress
   (only allow-listed hosts) — a core bank compliance control.
3. **Azure NAT Gateway** — give the AKS node pool a NAT gateway so all egress
   leaves from a **stable, allow-listable public IP**, and outbound SNAT ports
   scale. (Terraform attaches it to the node subnet.)

---

## AKS specifics (what Terraform sets up — see `docs/02`)

- **Managed Istio add-on:** `az aks mesh enable` (or Terraform `service_mesh_profile`)
  — Microsoft runs istiod; you just label namespaces. Supports ambient.
- **Ingress:** Gateway `Service: LoadBalancer` → Azure LB; optional App Gateway WAF.
- **Egress:** NAT Gateway on the node subnet for stable egress IP; egress gateway
  + ServiceEntry for control.
- **Node pool sizing:** budget for istiod/ztunnel + agentgateway + enterprise
  controller/ext-auth/rate-limiter/telemetry. (Our minikube starvation is the
  cautionary tale — see `docs/04`.)

---

## Putting it together (request, ingress → tool → egress)

1. Partner agent hits the **Azure LB** public IP → spread to an agentgateway replica.
2. **agentgateway**: validate Entra JWT → RBAC (role→tool) → rate-limit → pick backend.
3. Call to `mcp-payments` rides **ztunnel mTLS**; the **waypoint (Envoy)** enforces
   "only agentgateway may call this," and load-balances across mcp-payments replicas.
4. `mcp-payments` → `bank-core`, again mTLS + LB in the mesh.
5. If a tool needs an **external** vendor, egress goes through the **egress gateway
   (Envoy)** / agentgateway remote-MCP, out the **NAT gateway** IP.
6. Access logs/traces (enriched with Entra claims + mTLS peer identity) give one
   audit trail across all hops.

See `k8s/istio/` for the applyable manifests (ambient enrollment, mTLS, waypoint,
egress ServiceEntry) and `infra/terraform/` for the AKS mesh add-on + LB + NAT.
