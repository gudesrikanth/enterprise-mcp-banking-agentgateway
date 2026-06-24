# Azure Container Registry for the bank images, and an AcrPull grant so AKS can
# pull without imagePullSecrets (uses the cluster's kubelet managed identity).

resource "azurerm_container_registry" "acr" {
  name                = replace("${var.name_prefix}acr", "-", "") # ACR names: alphanumeric only
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  sku                 = var.acr_sku
  admin_enabled       = false
  tags                = var.tags
}

resource "azurerm_role_assignment" "aks_acr_pull" {
  scope                            = azurerm_container_registry.acr.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.aks.kubelet_identity[0].object_id
  skip_service_principal_aad_check = true
}
