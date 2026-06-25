variable "subscription_id" {
  type        = string
  description = "Azure subscription ID (or set ARM_SUBSCRIPTION_ID)."
  default     = null
}

variable "name_prefix" {
  type        = string
  description = "Prefix for all resource names."
  default     = "bankmcp"
}

variable "location" {
  type        = string
  description = "Azure region."
  default     = "eastus"
}

variable "kubernetes_version" {
  type        = string
  description = "AKS Kubernetes version (must be >= 1.31 for enterprise-agentgateway). Null => let AKS choose its default supported version (avoids pinning an LTS-only patch like 1.31.13). Pin via `az aks get-versions --location <loc>` if needed."
  default     = null
  nullable    = true
}

variable "node_vm_size" {
  type = string
  # The enterprise stack (controller, ext-auth, rate-limiter, telemetry) + Istio
  # + the bank apps need real headroom — see docs/04 (the minikube starvation).
  description = "VM size for the system node pool."
  # D*s_v5 is not offered on this subscription in eastus; the region exposes the
  # v7 family. D4s_v7 = 4 vCPU general purpose (same shape as D4s_v5).
  default = "Standard_D4s_v7"
}

variable "node_count" {
  type        = number
  description = "Node count for the system node pool."
  default     = 3
}

variable "availability_zones" {
  type        = list(number)
  description = "AKS node pool availability zones. Leave empty on subscriptions/regions that do not support AKS zones (e.g. new Pay-As-You-Go subs in eastus)."
  default     = []
}

variable "istio_revision" {
  type = string
  # Pick a revision supported in your region/k8s version:
  #   az aks mesh get-revisions --location <location> -o table
  description = "Managed Istio (ASM) revision for the AKS mesh add-on."
  # eastus supports asm-1-28 / asm-1-29 (az aks mesh get-revisions). asm-1-29 is
  # compatible with k8s 1.31-1.36, covering whatever default version AKS selects.
  default = "asm-1-29"
}

variable "acr_sku" {
  type        = string
  description = "ACR SKU (Premium adds geo-replication, private link, etc.)."
  default     = "Standard"
}

variable "tags" {
  type        = map(string)
  description = "Tags applied to all resources."
  default = {
    project = "bank-mcp-platform"
    owner   = "platform-eng"
  }
}
