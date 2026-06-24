# 12 — CI/CD (GitHub Actions)

Two workflows in `.github/workflows/`, DevOps-standard: **OIDC** federated login
(no long-lived cloud secrets), PR = plan, main = apply, remote Terraform state,
protected `production` environment, and **functional tests** that fail the build.

> **Repo layout:** `.github/` must sit at the **repository root**. These workflows
> assume **`enterprise-mcp-banking-agentgateway/` is the repo root** (paths `infra/terraform`, `apps/`,
> `k8s/`, `tests/`). If `enterprise-mcp-banking-agentgateway` is a subfolder of a larger repo, move
> `.github` to the repo root and prefix the paths with `enterprise-mcp-banking-agentgateway/`.

## Pipelines
| Workflow | Trigger | What it does |
|---|---|---|
| `terraform.yml` | PR / push main / manual | `init` (remote state) → `fmt` → `validate` → `plan`; **apply** on main or manual `apply`; manual `destroy` |
| `build-deploy-test.yml` | push main / manual | **build** (matrix `az acr build` → ACR, tagged with the commit SHA) → **deploy** (AKS: ensure gateway, set image refs, apply federation + mesh + policies, wait for rollout) → **functional-tests** (`tests/functional/mcp_smoke.sh`) |

### Functional tests asserted (`tests/functional/mcp_smoke.sh`)
1. no token → **401**
2. `tools/list` exposes all **6** federated tools
3. `accounts_list_accounts` returns accounts
4. `payments_transfer_funds` (payments-admin) → **POSTED**
5. rate limit: a rapid 4th `transfer_funds` → **429**

---

## GitHub **Secrets** to add (Settings → Secrets and variables → Actions → Secrets)
| Secret | What | Used by |
|---|---|---|
| `AZURE_CLIENT_ID` | the **CI** Entra app (client) id (OIDC) | both |
| `AZURE_TENANT_ID` | your tenant id (`c39d7aef-…`) | both |
| `AZURE_SUBSCRIPTION_ID` | target subscription | both |
| `SOLO_TRIAL_LICENSE_KEY` | Solo trial license | deploy (gateway install) |
| `BANK_APP_CLIENT_ID` | the **bank** Entra app id (`835c171b-…`) for minting test tokens | functional-tests |
| `BANK_APP_CLIENT_SECRET` | that app's client secret | functional-tests |
| `BANK_APP_TENANT_ID` | bank app tenant (here = `AZURE_TENANT_ID`) | functional-tests |

## GitHub **Variables** to add (…→ Variables — non-secret)
| Variable | Example |
|---|---|
| `TFSTATE_RESOURCE_GROUP` | `tfstate-rg` |
| `TFSTATE_STORAGE_ACCOUNT` | `bankmcptfstate` |
| `TFSTATE_CONTAINER` | `tfstate` |
| `ACR_NAME` | `bankmcpacr` (from `tofu output acr_login_server`, name part) |
| `AKS_NAME` | `bankmcp-aks` |
| `AKS_RESOURCE_GROUP` | `bankmcp-rg` |

> `AZURE_*` ids aren't truly secret, but storing them as secrets is conventional.

---

## One-time setup (run once)

### 1. Remote Terraform state (storage account)
```bash
az group create -n tfstate-rg -l eastus
az storage account create -n bankmcptfstate -g tfstate-rg --sku Standard_LRS --min-tls-version TLS1_2
az storage container create -n tfstate --account-name bankmcptfstate --auth-mode login
```

### 2. CI app registration + OIDC federated credentials (passwordless)
```bash
ORG=<github-org>; REPO=<repo>; SUB=<subscription-id>
APP_ID=$(az ad app create --display-name bank-mcp-ci --query appId -o tsv)
az ad sp create --id "$APP_ID"

# trust GitHub OIDC for the 3 subjects the workflows use:
for SUBJECT in \
  "repo:$ORG/$REPO:ref:refs/heads/main" \
  "repo:$ORG/$REPO:pull_request" \
  "repo:$ORG/$REPO:environment:production"; do
  az ad app federated-credential create --id "$APP_ID" --parameters "{
    \"name\":\"gh-$(echo $SUBJECT | tr ':/' '--')\",
    \"issuer\":\"https://token.actions.githubusercontent.com\",
    \"subject\":\"$SUBJECT\",
    \"audiences\":[\"api://AzureADTokenExchange\"]}"
done
```

### 3. RBAC for the CI app
```bash
# Terraform needs to create resources + assign AcrPull (User Access Administrator):
az role assignment create --assignee "$APP_ID" --role "Contributor" --scope "/subscriptions/$SUB"
az role assignment create --assignee "$APP_ID" --role "User Access Administrator" --scope "/subscriptions/$SUB"
# After AKS/ACR exist, scope these tighter (least privilege):
#   AcrPush on the ACR, "Azure Kubernetes Service RBAC Cluster Admin" on the AKS.
```
Put `AZURE_CLIENT_ID=$APP_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` into secrets.

### 4. Protect the `production` environment
Settings → Environments → **production** → add **required reviewers** (and optionally
restrict to `main`). `apply`, `deploy`, and `destroy` run there, so a human approves
real changes.

### 5. Bank app for test tokens
Already created in `docs/03` (`835c171b-…`). Add a client secret and put
`BANK_APP_CLIENT_ID` / `BANK_APP_CLIENT_SECRET` / `BANK_APP_TENANT_ID` into secrets.
For the **teller-blocked** path in tests, assign only `teller` to a second app and
add a second token step (optional).

---

## Order of operations
1. Add secrets/variables + run the one-time setup.
2. `terraform.yml` (push to `infra/terraform/**` or manual `apply`) → AKS + ACR + Istio + NAT.
3. Set `ACR_NAME` / `AKS_NAME` / `AKS_RESOURCE_GROUP` variables from the TF outputs.
4. `build-deploy-test.yml` (push to `apps/**`/`k8s/**` or manual) → images → deploy → functional tests gate the pipeline green.
