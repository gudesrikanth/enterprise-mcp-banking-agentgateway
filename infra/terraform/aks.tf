# AKS cluster: Azure CNI, NAT-gateway egress, the MANAGED ISTIO add-on (mesh),
# OIDC issuer + workload identity (so in-cluster agents can use Entra workload
# identity instead of client secrets).

resource "azurerm_kubernetes_cluster" "aks" {
  name                = "${var.name_prefix}-aks"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  dns_prefix          = "${var.name_prefix}-aks"
  kubernetes_version  = var.kubernetes_version
  tags                = var.tags

  default_node_pool {
    name           = "system"
    node_count     = var.node_count
    vm_size        = var.node_vm_size
    vnet_subnet_id = azurerm_subnet.aks.id
    # spread across zones for HA
    zones = [1, 2, 3]
  }

  identity {
    type = "SystemAssigned"
  }

  # Azure CNI + NAT gateway egress (stable egress IP from network.tf)
  network_profile {
    network_plugin = "azure"
    network_policy = "azure"
    outbound_type  = "userAssignedNATGateway"
  }

  # Entra workload identity for in-cluster agents (no client secrets in pods)
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  # Managed Istio service mesh add-on (Envoy data plane). Enroll namespaces with
  # the istio.io/dataplane-mode=ambient label (see k8s/istio/). We do NOT enable
  # the Istio ingress gateways — agentgateway is our ingress.
  service_mesh_profile {
    mode                             = "Istio"
    revisions                        = [var.istio_revision]
    internal_ingress_gateway_enabled = false
    external_ingress_gateway_enabled = false
  }

  depends_on = [azurerm_subnet_nat_gateway_association.aks]
}
