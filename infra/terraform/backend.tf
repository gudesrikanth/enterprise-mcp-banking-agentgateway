# Remote state in Azure Blob Storage (required for CI / team use). The values are
# supplied at `terraform init` time via -backend-config (from GitHub vars), so no
# secrets live in code. For local validate without a backend:
#   tofu init -backend=false && tofu validate
terraform {
  backend "azurerm" {
    # Authenticate to the state storage account with the OIDC service principal
    # (no storage access key / SAS token committed or required). The SP needs the
    # "Storage Blob Data Contributor" role on the state storage account.
    use_azuread_auth = true
    use_oidc         = true
  }
}
