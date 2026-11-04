#!/usr/bin/env bash
# Checks that the current cluster and an overlay are ready before kubectl apply -k.
#
#   scripts/aks-preflight.sh dev                  # checks and a local render
#   scripts/aks-preflight.sh prod --server-dry-run
#   scripts/aks-preflight.sh dev --render-only    # no cluster needed, as in CI
#
# The namespace comes from the overlay; -n overrides it. Exits 1 if any check fails.
set -euo pipefail

usage() {
  echo "usage: $0 <dev|prod> [-n namespace] [--context name] [--server-dry-run] [--render-only]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
overlay_name=$1
shift
namespace=""
expected_context=""
server_dry_run=false
render_only=false
while [[ $# -gt 0 ]]; do
  case $1 in
    -n | --namespace) namespace=${2:?}; shift 2 ;;
    --context) expected_context=${2:?}; shift 2 ;;
    --server-dry-run) server_dry_run=true; shift ;;
    --render-only) render_only=true; shift ;;
    *) usage ;;
  esac
done

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
overlay="$repo_root/deploy/k8s/overlays/$overlay_name"
[[ -f "$overlay/kustomization.yaml" ]] || { echo "no overlay at $overlay" >&2; exit 2; }

failures=0
ok() { printf '  ok    %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; failures=$((failures + 1)); }
warn() { printf '  warn  %s\n' "$*"; }

echo "Tools"
if command -v kubectl >/dev/null 2>&1; then
  ok "kubectl $(kubectl version --client -o json 2>/dev/null | sed -n 's/.*"gitVersion": *"\([^"]*\)".*/\1/p' | head -n 1)"
else
  fail "kubectl not found"
  echo "1 or more checks failed"
  exit 1
fi
if command -v kustomize >/dev/null 2>&1; then
  ok "kustomize $(kustomize version 2>/dev/null)"
else
  ok "kustomize not installed, using kubectl kustomize"
fi

echo "Render overlays/$overlay_name"
rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT
if kubectl kustomize "$overlay" >"$rendered"; then
  ok "$(grep -c '^kind:' "$rendered") resources"
else
  fail "kubectl kustomize failed"
fi
if grep -q 'REPLACE_ME' "$rendered"; then
  placeholders=$(grep -c 'REPLACE_ME' "$rendered")
  if [[ $render_only == true ]]; then
    warn "$placeholders REPLACE_ME placeholders left (fill them in before deploying)"
  else
    fail "$placeholders REPLACE_ME placeholders left in the overlay"
  fi
else
  ok "no placeholders left"
fi
if [[ -z $namespace ]]; then
  namespace=$(sed -n 's/^namespace: *//p' "$overlay/kustomization.yaml" | head -n 1)
fi

if [[ $render_only == true ]]; then
  [[ $failures -eq 0 ]] || { echo "$failures check(s) failed"; exit 1; }
  echo "Render checks passed"
  exit 0
fi

echo "Cluster"
context=$(kubectl config current-context 2>/dev/null || true)
if [[ -z $context ]]; then
  fail "no current kubectl context"
elif [[ -n $expected_context && $context != "$expected_context" ]]; then
  fail "current context is $context, expected $expected_context"
else
  ok "context $context"
fi
if kubectl get --raw /readyz >/dev/null 2>&1; then
  ok "API server reachable"
else
  fail "API server not reachable"
fi
if kubectl get namespace "$namespace" >/dev/null 2>&1; then
  ok "namespace $namespace exists"
else
  fail "namespace $namespace not found (kubectl create namespace $namespace)"
fi

echo "Azure add-ons"
if kubectl get mutatingwebhookconfiguration azure-wi-webhook-mutating-webhook-configuration >/dev/null 2>&1; then
  ok "workload identity webhook"
else
  fail "workload identity webhook not found (az aks update --enable-oidc-issuer --enable-workload-identity)"
fi
if kubectl get crd secretproviderclasses.secrets-store.csi.x-k8s.io >/dev/null 2>&1; then
  ok "SecretProviderClass CRD"
else
  fail "SecretProviderClass CRD not found (az aks enable-addons --addons azure-keyvault-secrets-provider)"
fi
if kubectl get csidriver secrets-store.csi.k8s.io >/dev/null 2>&1; then
  ok "Secrets Store CSI driver"
else
  fail "Secrets Store CSI driver not registered"
fi
if [[ -n $(kubectl get pods -n kube-system -l app=csi-secrets-store-provider-azure -o name 2>/dev/null) ]]; then
  ok "Key Vault provider pods"
else
  fail "Key Vault provider pods not found in kube-system"
fi

if [[ $server_dry_run == true ]]; then
  echo "Server dry run"
  if kubectl apply -k "$overlay" -n "$namespace" --dry-run=server >/dev/null; then
    ok "kubectl apply -k --dry-run=server"
  else
    fail "server dry run rejected the manifests"
  fi
fi

if [[ $failures -gt 0 ]]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "Preflight passed for overlays/$overlay_name in $namespace"
