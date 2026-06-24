# 03 — Microsoft Entra ID setup (JWT auth for the gateway)

**Do this first** — it's the long-pole dependency. Outcome: an Entra **app
registration** that (a) issues JWTs the gateway can validate, and (b) carries
**app roles** we use for per-tool authorization. At the end you send me **four
values** and I wire the real issuer/JWKS into the gateway.

> Single-app simplicity: we use **one** app registration that both *exposes the
> API* (the audience + app roles) and acts as the *test client* (client-credentials).
> In production you'd typically split the **API app** from each **client/agent app**
> and assign roles to the clients — the gateway config is identical either way.

## What the gateway needs to validate a token
- **Issuer** (derived from tenant): `https://login.microsoftonline.com/<TENANT_ID>/v2.0`
- **JWKS** (derived): `https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys`
- **Audience**: the Application (client) ID **or** the Application ID URI (`api://<CLIENT_ID>`)
- **Roles claim**: `roles` (array of app-role values)

## Option A — Azure Portal

1. **Register the app**
   - Entra ID → App registrations → **New registration**
   - Name: `bank-mcp-gateway`, Supported account types: **Single tenant**, no redirect URI needed for service tokens. → Register.
   - Copy the **Application (client) ID** and **Directory (tenant) ID**.

2. **Expose an API (sets the audience)**
   - Manage → **Expose an API** → **Add** an Application ID URI → accept `api://<CLIENT_ID>`. This is the **audience**.
   - (Optional) Add a scope `access` (Admins and users) — used by interactive clients.

3. **Define app roles (drive authorization)**
   - Manage → **App roles** → **Create app role** (do this twice):
     | Display name | Value | Allowed member types |
     |---|---|---|
     | Teller (read) | `teller` | Users/Groups **and** Applications |
     | Payments Admin | `payments-admin` | Users/Groups **and** Applications |

4. **Force v2 tokens** (so issuer is `.../v2.0` and `roles` appear)
   - Manage → **Manifest** → set `"accessTokenAcceptedVersion": 2` → Save.

5. **Assign roles**
   - *For a service/agent (client-credentials):* Enterprise applications → `bank-mcp-gateway` → **Users and groups** isn't used for apps; instead assign the app role to the app's **service principal**. Easiest via CLI (Option B step 5) or by having a client app and granting it the role.
   - *For a user (interactive):* Enterprise applications → `bank-mcp-gateway` → **Users and groups** → **Add user** → pick the user → assign **Teller** or **Payments Admin**.

6. **A client secret** (for the client-credentials test only)
   - Certificates & secrets → **New client secret** → copy the value.

## Option B — az CLI (faster, reproducible)

```bash
# 0) login + pick the subscription/tenant you want
az login
TENANT_ID=$(az account show --query tenantId -o tsv)

# 1) register the app
APP_ID=$(az ad app create --display-name bank-mcp-gateway \
  --sign-in-audience AzureADMyOrg --query appId -o tsv)
echo "client/app id = $APP_ID"

# 2) set the Application ID URI (audience) + accept v2 tokens, and define app roles
az ad app update --id "$APP_ID" \
  --identifier-uris "api://$APP_ID" \
  --set api.requestedAccessTokenVersion=2 \
  --app-roles '[
    {"allowedMemberTypes":["User","Application"],"description":"Read-only banking tools","displayName":"Teller","isEnabled":true,"value":"teller","id":"11111111-1111-1111-1111-111111111111"},
    {"allowedMemberTypes":["User","Application"],"description":"Can move money","displayName":"Payments Admin","isEnabled":true,"value":"payments-admin","id":"22222222-2222-2222-2222-222222222222"}
  ]'

# 3) create the service principal + a secret (for the client-credentials test)
az ad sp create --id "$APP_ID" >/dev/null
APP_SECRET=$(az ad app credential reset --id "$APP_ID" --query password -o tsv)

# 4) (client-credentials path) assign an app role to THIS app's own SP so its
#    app-only token carries roles=[payments-admin]. resourceId = the SP objectId.
SP_OID=$(az ad sp show --id "$APP_ID" --query id -o tsv)
TOKEN=$(az account get-access-token --resource https://graph.microsoft.com --query accessToken -o tsv)
curl -s -X POST "https://graph.microsoft.com/v1.0/servicePrincipals/$SP_OID/appRoleAssignments" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"principalId\":\"$SP_OID\",\"resourceId\":\"$SP_OID\",\"appRoleId\":\"22222222-2222-2222-2222-222222222222\"}"
```

## Mint a test token (client-credentials) and inspect it

```bash
TOKEN=$(curl -s -X POST "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token" \
  -d "grant_type=client_credentials" \
  -d "client_id=$APP_ID" \
  -d "client_secret=$APP_SECRET" \
  -d "scope=api://$APP_ID/.default" | jq -r .access_token)

# decode the payload — expect: aud == api://$APP_ID (or $APP_ID), iss .../v2.0, roles:["payments-admin"]
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .
```

> If `roles` is empty: the app-role assignment (step 4) hasn't propagated, or
> `requestedAccessTokenVersion` isn't 2. If `aud` is a GUID rather than the URI,
> that's fine — the gateway can accept either; we'll set it to match what you send.

## Send me these four values
1. **TENANT_ID**
2. **CLIENT_ID** (Application ID)
3. **AUDIENCE** — `api://<CLIENT_ID>` (or the GUID, whichever your test token shows in `aud`)
4. **Role values** — confirm `teller` / `payments-admin` (or your preferred names)

> The **client secret is for your test only — do not send it to me.** Mint the
> token on your side; I only need a sample decoded payload to confirm claim names.

With those, I set the gateway JWT policy to:
```yaml
jwtAuthentication:
  mode: Strict
  providers:
    - issuer: https://login.microsoftonline.com/<TENANT_ID>/v2.0
      audiences: ["api://<CLIENT_ID>"]
      jwks:
        url: https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys
```
and the RBAC to e.g. `"payments-admin" in jwt.roles` for `payments_transfer_funds`.

## Notes for production (mention when presenting)
- Split **API app** (audience + roles) from **client/agent apps**; assign roles to clients.
- Prefer **groups** or **app roles** over per-user assignment at scale.
- Use **workload identity / managed identity** for in-cluster agents instead of client secrets.
- Rotate secrets; or go certificate-based / federated credentials.
