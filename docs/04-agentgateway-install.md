# 04 — Install enterprise agentgateway (control plane + proxy)

Same install on minikube and AKS — only sizing differs.

## Components it brings up (namespace `agentgateway-system`)
| Pod | Role |
|---|---|
| `enterprise-agentgateway` | **controller** — reconciles CRDs → programs the proxy (xDS) |
| `agentgateway-proxy` | **data plane** — the MCP proxy clients hit |
| `ext-auth-service` | auth enforcement (JWT/ext-authz) |
| `rate-limiter` | global rate-limit backend |
| `ext-cache` | caching |
| `solo-enterprise-ui`, `telemetry-collector`, `clickhouse` | observability (optional) |

## Install
```bash
# 1) Gateway API CRDs
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.0/standard-install.yaml

# 2) enterprise CRDs (no license)
kubectl create namespace agentgateway-system
helm upgrade -i --namespace agentgateway-system --version v2026.6.0 enterprise-agentgateway-crds \
  oci://us-docker.pkg.dev/solo-public/enterprise-agentgateway/charts/enterprise-agentgateway-crds

# 3) controller (NEEDS your Solo trial license key)
helm upgrade -i -n agentgateway-system enterprise-agentgateway \
  oci://us-docker.pkg.dev/solo-public/enterprise-agentgateway/charts/enterprise-agentgateway \
  --version v2026.6.0 --set-string licensing.licenseKey="$SOLO_TRIAL_LICENSE_KEY"

# 4) the Gateway + parameters (minikube: ClusterIP; AKS: k8s/istio/aks-gateway-parameters.yaml)
kubectl apply -f k8s/10-gateway.yaml        # or the AKS LoadBalancer variant
```
GatewayClass is `enterprise-agentgateway`. Confirm:
```bash
kubectl get gatewayclass
kubectl -n agentgateway-system get pods
```

## ⚠️ Capacity (the lesson from minikube)
The full stack + Istio + the bank apps starves a small node. On minikube the
**controller crash-looped** ("leader election lost — lease lock context deadline
exceeded") and the proxy lost XDS (`tools/list` hung). Mitigations:
- Free host RAM (we stopped a host docker-compose stack), delete leftover namespaces.
- Scale observability extras to 0: `kubectl -n agentgateway-system scale deploy/solo-enterprise-ui --replicas=0` and the `management-clickhouse-shard0` / `solo-enterprise-telemetry-collector` statefulsets.
- `kubectl -n agentgateway-system patch enterpriseagentgatewayparameters agentgateway-config --type=merge -p '{"spec":{"deployment":{"spec":{"replicas":1}}}}'`.

On **AKS** this is a node-pool sizing decision — see `docs/02` (3 × Standard_D4s_v5).
