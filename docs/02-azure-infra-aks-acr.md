# 02 — Azure infra: AKS + ACR + Istio + NAT (Terraform)

Provisions everything the bank platform runs on, in `infra/terraform/`:

| Resource | Why |
|---|---|
| Resource group | container for everything |
| VNet + `aks` subnet | BYO network (needed for NAT egress) |
| **NAT Gateway** + static public IP | stable, allow-listable **egress IP** for outbound calls |
| **ACR** (+ `AcrPull` to AKS) | private image registry; AKS pulls with its managed identity |
| **AKS** | Azure CNI, NAT egress, **managed Istio add-on**, OIDC + workload identity |

`tofu validate` passes against the `azurerm ~> 4.0` schema.

## Prerequisites
- `az login` (or a service principal) with Contributor + User Access Administrator
  on the subscription (the `AcrPull` role assignment needs RBAC write).
- `terraform` or `tofu`, and `docker` + `kubectl`.

## 1. Provision
```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars     # edit subscription_id, location, etc.

# pick a managed-Istio revision valid for your region/version, set istio_revision:
az aks mesh get-revisions --location eastus -o table

tofu init
tofu apply        # ~10–15 min (AKS + mesh add-on)
```
Capture the outputs:
```bash
tofu output -raw acr_login_server     # e.g. bankmcpacr.azurecr.io
tofu output -raw egress_ip            # give to vendors for allow-listing
eval "$(tofu output -raw get_credentials)"   # points kubectl at the new AKS
```

## 2. Push the images to ACR
```bash
ACR=$(tofu output -raw acr_login_server)
az acr login --name "${ACR%%.*}"
for svc in bank-core mcp-accounts mcp-payments; do
  # build straight in ACR (no local docker needed) OR docker tag+push:
  az acr build -r "${ACR%%.*}" -t "$ACR/$svc:0.1.0" ../../apps/$svc
done
```

## 3. Point the manifests at ACR
The k8s manifests use `bank/<svc>:0.1.0` (minikube). For AKS, swap to
`<acr>/<svc>:0.1.0`:
```bash
cd ../../          # enterprise-mcp-banking-agentgateway/
sed "s#image: bank/#image: $ACR/#g" k8s/00-apps.yaml > k8s/00-apps.aks.yaml
```

## 4. Install the gateway + enroll the mesh + deploy
```bash
# Gateway API CRDs + enterprise-agentgateway (same as docs/04, your license):
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.5.0/standard-install.yaml
kubectl create namespace agentgateway-system
helm upgrade -i --namespace agentgateway-system --version v2026.6.0 enterprise-agentgateway-crds \
  oci://us-docker.pkg.dev/solo-public/enterprise-agentgateway/charts/enterprise-agentgateway-crds
helm upgrade -i -n agentgateway-system enterprise-agentgateway \
  oci://us-docker.pkg.dev/solo-public/enterprise-agentgateway/charts/enterprise-agentgateway \
  --version v2026.6.0 --set-string licensing.licenseKey="$SOLO_TRIAL_LICENSE_KEY"

# mesh enrollment + L7 authz + egress (managed Istio is already on via Terraform):
kubectl apply -f k8s/istio/00-ambient.yaml
kubectl apply -f k8s/istio/10-waypoint-authz.yaml
kubectl apply -f k8s/istio/20-egress.yaml

# apps + AKS gateway params (LoadBalancer ingress + mesh join) + federation + policies:
kubectl apply -f k8s/00-apps.aks.yaml
kubectl apply -f k8s/istio/aks-gateway-parameters.yaml   # replaces the minikube 10-gateway params
kubectl apply -f k8s/20-federation.yaml
kubectl apply -f k8s/30-jwt-entra.yaml
kubectl apply -f k8s/31-rbac-personas.yaml
kubectl apply -f k8s/40-rate-limit.yaml
kubectl apply -f k8s/50-telemetry.yaml
```

## 5. Verify (real Azure LB, no port-forward)
```bash
GW=$(kubectl -n agentgateway-system get svc -l gateway.networking.k8s.io/gateway-name=agentgateway-proxy \
      -o jsonpath='{.items[0].status.loadBalancer.ingress[0].ip}')
echo "gateway: http://$GW:8080/mcp"
# same MCP handshake as docs/09, with an Entra Bearer token
```

## Cost & teardown
- ~3 × `Standard_D4s_v5` nodes + AKS + ACR + NAT + LB. **Stop the cluster when idle**
  (`az aks stop`) and `tofu destroy` when done:
```bash
cd infra/terraform && tofu destroy
```

## Notes / enterprise hardening
- **State:** use a remote backend (azurerm backend → a storage account) for team use.
- **Private cluster:** add `private_cluster_enabled = true` + private ACR (Premium + private endpoint).
- **Image scanning:** Defender for Containers / Trivy in CI before `az acr build`.
- **Workload identity:** use the `oidc_issuer_url` output to create Entra federated
  credentials so in-cluster agents authenticate without client secrets.
