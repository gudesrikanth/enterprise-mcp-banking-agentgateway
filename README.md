# Bank MCP Platform — enterprise agentgateway on Azure

A real-time **retail-bank MCP tool platform**: two MCP servers federated behind
**Solo enterprise agentgateway** on **AKS**, with **Microsoft Entra ID** JWT auth,
**persona/role tool authorization**, **per-tool rate limiting**, **custom telemetry**,
and an **Istio (Envoy) ambient mesh** with ingress/egress load balancing.

```
Azure LB ─▶ agentgateway (JWT·RBAC·rate-limit·federation) ─▶ Istio/Envoy mesh ─▶ mcp-accounts / mcp-payments ─▶ bank-core
   (1)                         (2)                                 (3 mTLS+LB)                                      egress (4) ─▶ external
```

## Layout
| Path | What |
|---|---|
| `apps/` | `bank-core` (REST), `mcp-accounts`, `mcp-payments` (Spring Boot, Java 25) |
| `infra/terraform/` | AKS + ACR + managed Istio + NAT egress (`tofu validate` passes) |
| `k8s/` | `00-apps`, `20-federation`, `30-jwt-entra`, `31-rbac-personas`, `40-rate-limit`, `50-telemetry` |
| `k8s/istio/` | ambient enrollment, waypoint authz, egress, AKS LoadBalancer params |
| `docs/` | the full guide (below) |

## Docs (read in order)
| # | Doc |
|---|---|
| 00 | [Overview & architecture](docs/00-overview-and-architecture.md) |
| 01 | [Domain & apps](docs/01-domain-and-apps.md) |
| 02 | [Azure infra: AKS + ACR + Istio + NAT (Terraform)](docs/02-azure-infra-aks-acr.md) |
| 03 | [Entra ID JWT setup](docs/03-entra-jwt-setup.md) |
| 04 | [Install enterprise agentgateway](docs/04-agentgateway-install.md) |
| 05 | [Federation](docs/05-federation.md) |
| 06 | [Security: JWT + persona RBAC](docs/06-security-jwt-rbac-personas.md) |
| 07 | [Per-tool rate limiting](docs/07-rate-limiting.md) |
| 08 | [Observability & telemetry](docs/08-observability-telemetry.md) |
| 09 | [End-to-end flow](docs/09-end-to-end-flow.md) |
| 10 | [Presentation script (~15 min)](docs/10-presentation-script.md) |
| 11 | [Networking: Istio / Envoy / ingress / egress LB](docs/11-networking-istio-envoy-loadbalancing.md) |

## Status
- ✅ Apps built; **federation + real-time transfer + Entra JWT deny** verified on minikube.
- ✅ Persona RBAC, per-tool rate-limit, telemetry policies applied (accepted).
- ✅ Terraform validated; Istio/ingress/egress manifests + docs complete.
- ⏳ Allow + RBAC + rate-limit behavioral test needs a fresh Entra token (see docs/06/09).
- ⏳ AKS provisioning runs on your subscription (docs/02).

## Quickstart (local, minikube — same data plane as AKS)
```bash
# build + load images
for s in bank-core mcp-accounts mcp-payments; do docker build -t bank/$s:0.1.0 apps/$s && minikube image load bank/$s:0.1.0; done
# deploy (assumes enterprise-agentgateway already installed — docs/04)
kubectl apply -f k8s/00-apps.yaml -f k8s/20-federation.yaml -f k8s/30-jwt-entra.yaml \
              -f k8s/31-rbac-personas.yaml -f k8s/40-rate-limit.yaml -f k8s/50-telemetry.yaml
kubectl -n agentgateway-system port-forward svc/agentgateway-proxy 8080:8080
# then docs/09 for the request flow (with an Entra Bearer token)
```
