# Remote state in Azure Blob Storage (required for CI / team use). The values are
# supplied at `terraform init` time via -backend-config (from GitHub vars), so no
# secrets live in code. For local validate without a backend:
#   tofu init -backend=false && tofu validate
terraform {
  backend "azurerm" {}
}
