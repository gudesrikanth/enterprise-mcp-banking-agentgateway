output "resource_group" {
  value = azurerm_resource_group.rg.name
}

output "acr_login_server" {
  value       = azurerm_container_registry.acr.login_server
  description = "Use for docker tag/push and the k8s image refs."
}

output "aks_name" {
  value = azurerm_kubernetes_cluster.aks.name
}

output "egress_ip" {
  value       = azurerm_public_ip.nat.ip_address
  description = "Stable egress IP — give this to vendors for allow-listing."
}

output "oidc_issuer_url" {
  value       = azurerm_kubernetes_cluster.aks.oidc_issuer_url
  description = "For Entra federated credentials (workload identity)."
}

output "get_credentials" {
  value       = "az aks get-credentials --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name}"
  description = "Run this to point kubectl at the new cluster."
}

output "acr_login" {
  value       = "az acr login --name ${azurerm_container_registry.acr.name}"
  description = "Run this before docker push."
}
