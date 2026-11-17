# shellcheck shell=bash disable=SC2034
# (SC2034: globals set here are read by the other sourced modules.)
# Read-only cluster detection used by doctor, up, status and down. Every function here only
# issues get/list/auth can-i requests.

crd_exists()       { "$KC" get crd "$1" >/dev/null 2>&1; }
namespace_exists() { "$KC" get namespace "$1" >/dev/null 2>&1; }

# ----------------------------------------------------------------------------- KEDA / CMA
# Sets KEDA_STATE to one of:
#   cma        OpenShift Custom Metrics Autoscaler operator present (openshift-keda namespace or
#              kedacontrollers.keda.sh CRD)
#   upstream   keda.sh CRDs + an upstream keda-operator Deployment outside openshift-keda
#   orphan     keda.sh CRDs without a recognised operator
#   none       nothing
# and KEDA_DETAIL to a human-readable explanation. KEDA_CMA_READY=true when a KedaController
# instance exists in openshift-keda.
detect_keda() {
  KEDA_STATE=none; KEDA_DETAIL="no keda.sh CRDs, no CMA operator"; KEDA_CMA_READY=false
  local has_crd=false has_cma=false upstream_ns=""
  crd_exists scaledobjects.keda.sh && has_crd=true
  if namespace_exists openshift-keda || crd_exists kedacontrollers.keda.sh; then has_cma=true; fi
  if [[ "$has_cma" == "true" ]]; then
    KEDA_STATE=cma
    if "$KC" get kedacontrollers.keda.sh -n openshift-keda -o name 2>/dev/null | grep -q .; then
      KEDA_CMA_READY=true
      KEDA_DETAIL="Custom Metrics Autoscaler operator with a KedaController in openshift-keda"
    else
      KEDA_DETAIL="Custom Metrics Autoscaler operator present but no KedaController instance in openshift-keda"
    fi
    return 0
  fi
  upstream_ns="$("$KC" get deploy -A -l app.kubernetes.io/name=keda-operator \
    -o jsonpath='{range .items[*]}{.metadata.namespace}{"\n"}{end}' 2>/dev/null | head -n1 || true)"
  if [[ "$has_crd" == "true" && -n "$upstream_ns" ]]; then
    KEDA_STATE=upstream
    local img
    img="$("$KC" get deploy -n "$upstream_ns" -l app.kubernetes.io/name=keda-operator \
      -o jsonpath='{.items[0].spec.template.spec.containers[0].image}' 2>/dev/null || true)"
    KEDA_DETAIL="upstream KEDA in namespace ${upstream_ns} (${img:-image unknown})"
  elif [[ "$has_crd" == "true" ]]; then
    KEDA_STATE=orphan
    KEDA_DETAIL="CRD scaledobjects.keda.sh exists but no keda-operator Deployment was found"
  fi
}

# ----------------------------------------------------------------------------- SPIRE
# Sets SPIRE_STATE (existing|none), SPIRE_DETAIL, SPIRE_DETECTED_TRUST_DOMAIN,
# SPIRE_DETECTED_CLASS_NAME. Trust domain/class detection is best effort.
detect_spire() {
  SPIRE_STATE=none; SPIRE_DETAIL=""; SPIRE_DETECTED_TRUST_DOMAIN=""; SPIRE_DETECTED_CLASS_NAME=""
  local parts=()
  "$KC" get csidriver csi.spiffe.io >/dev/null 2>&1 && parts+=("CSIDriver csi.spiffe.io")
  crd_exists clusterspiffeids.spire.spiffe.io && parts+=("CRD clusterspiffeids.spire.spiffe.io")
  namespace_exists zero-trust-workload-identity-manager && parts+=("ZTWIM operator namespace")
  if ((${#parts[@]} > 0)); then
    SPIRE_STATE=existing
    local IFS=", "
    SPIRE_DETAIL="${parts[*]}"
    # A SPIRE release that this project started but that did not finish (status failed/pending) is
    # repaired rather than reused.
    local rel_status
    rel_status="$(helm status "${SPIRE_RELEASE:-spire}" -n "${SPIRE_RELEASE_NAMESPACE:-spire-mgmt}" -o json 2>/dev/null \
      | jq -r '.info.status // empty' 2>/dev/null || true)"
    if [[ -n "$rel_status" && "$rel_status" != "deployed" ]]; then
      SPIRE_STATE=incomplete
      SPIRE_DETAIL="project SPIRE release ${SPIRE_RELEASE:-spire} is '${rel_status}' (${SPIRE_DETAIL})"
      return 0
    fi
  else
    SPIRE_DETAIL="no SPIFFE CSI driver, no ClusterSPIFFEID CRD, no ZTWIM operator"
    return 0
  fi

  # Class name used by the existing controller manager: take it from existing ClusterSPIFFEIDs
  # (excluding the ones this project created).
  if crd_exists clusterspiffeids.spire.spiffe.io; then
    SPIRE_DETECTED_CLASS_NAME="$("$KC" get clusterspiffeids.spire.spiffe.io \
      -l "app.kubernetes.io/managed-by!=${MAOP_MANAGED_BY}" \
      -o jsonpath='{range .items[*]}{.spec.className}{"\n"}{end}' 2>/dev/null | grep -v '^$' | head -n1 || true)"
  fi

  # Trust domain, in order: Zero Trust Workload Identity Manager CR, SPIRE controller-manager
  # config, SPIRE server config (upstream chart layout).
  local td=""
  td="$("$KC" get zerotrustworkloadidentitymanagers.operator.openshift.io cluster \
        -o jsonpath='{.spec.trustDomain}' 2>/dev/null || true)"
  if [[ -z "$td" ]]; then
    local ns cm
    for ns in spire-server spire-mgmt spire-system zero-trust-workload-identity-manager; do
      namespace_exists "$ns" || continue
      for cm in $("$KC" get configmap -n "$ns" -o name 2>/dev/null | grep -E 'controller-manager|spire-server' || true); do
        td="$("$KC" get -n "$ns" "$cm" -o json 2>/dev/null \
          | jq -r '[.data // {} | to_entries[] | .value
                    | (capture("trustDomain:\\s*\"?(?<t>[A-Za-z0-9._-]+)") // capture("\"trust_domain\"\\s*:\\s*\"(?<t>[^\"]+)\"") // empty) | .t][0] // empty' \
          2>/dev/null || true)"
        [[ -n "$td" ]] && break 2
      done
    done
  fi
  SPIRE_DETECTED_TRUST_DOMAIN="$td"
}

# The project's install record: ConfigMap maop-installed-components in the app namespace. Not
# labelled part-of=maop, so `down` without --uninstall-platform-components keeps it.
installed_components_get() {
  "$KC" get configmap maop-installed-components -n "$MAOP_NAMESPACE" -o json 2>/dev/null \
    | jq -r --arg k "$1" '.data[$k] // empty' 2>/dev/null || true
}

openshift_apps_domain() {
  "$KC" get ingresses.config.openshift.io cluster -o jsonpath='{.spec.domain}' 2>/dev/null || true
}

openshift_infra_name() {
  "$KC" get infrastructure cluster -o jsonpath='{.status.infrastructureName}' 2>/dev/null || true
}

# ----------------------------------------------------------------------------- decision
# Decides what `up` will do, given cluster state and flags. Sets:
#   PLAN_SCALER          keda | cma
#   PLAN_KEDA_ACTION     reuse-cma | reuse-upstream | install | blocked
#   PLAN_SPIRE_ACTION    reuse | install | blocked
#   PLAN_TRUST_DOMAIN    resolved trust domain or empty (blocked)
#   PLAN_CLASS_NAME      ClusterSPIFFEID className (may be empty for classless controllers)
#   PLAN_PROBLEMS        newline-separated actionable messages
plan_platform() {
  local install="${OPT_INSTALL_PLATFORM:-false}"
  PLAN_PROBLEMS=""
  detect_keda
  case "$KEDA_STATE" in
    cma)
      PLAN_SCALER=cma; PLAN_KEDA_ACTION=reuse-cma
      if [[ "$MAOP_PLATFORM" != "openshift" ]]; then
        PLAN_KEDA_ACTION=blocked
        PLAN_PROBLEMS+="CMA detected but --platform is not openshift; use --platform openshift."$'\n'
      elif [[ "$KEDA_CMA_READY" != "true" ]]; then
        PLAN_KEDA_ACTION=blocked
        PLAN_PROBLEMS+="CMA operator is installed but no KedaController exists in openshift-keda. Create one (OpenShift docs: 'Creating the KedaController custom resource'); this project never installs upstream KEDA next to CMA."$'\n'
      fi;;
    upstream) PLAN_SCALER=keda; PLAN_KEDA_ACTION=reuse-upstream;;
    orphan)
      PLAN_SCALER=keda; PLAN_KEDA_ACTION=blocked
      PLAN_PROBLEMS+="keda.sh CRDs exist without a keda-operator Deployment. Repair or remove the previous KEDA install before running up."$'\n';;
    none)
      PLAN_SCALER=keda
      if [[ "$install" == "true" ]]; then PLAN_KEDA_ACTION=install; else
        PLAN_KEDA_ACTION=blocked
        PLAN_PROBLEMS+="No KEDA/CMA found. Rerun with --install-platform-components to install KEDA chart ${KEDA_CHART_VERSION} into namespace ${KEDA_NAMESPACE}, or install it yourself."$'\n'
      fi;;
  esac

  detect_spire
  PLAN_CLASS_NAME="${MAOP_SPIRE_CLASS_NAME:-}"
  if [[ "$SPIRE_STATE" == "incomplete" ]]; then
    PLAN_CLASS_NAME="${PLAN_CLASS_NAME:-$SPIRE_CLASS_NAME}"
    PLAN_TRUST_DOMAIN="${MAOP_SPIRE_TRUST_DOMAIN:-$(installed_components_get spire-trust-domain)}"
    if [[ -z "$PLAN_TRUST_DOMAIN" ]]; then
      if [[ "$MAOP_PLATFORM" == "openshift" ]]; then PLAN_TRUST_DOMAIN="$(openshift_apps_domain)"; else PLAN_TRUST_DOMAIN="maop.example"; fi
    fi
    if [[ "${OPT_INSTALL_PLATFORM:-false}" == "true" && -n "$PLAN_TRUST_DOMAIN" ]]; then
      PLAN_SPIRE_ACTION=install
    else
      PLAN_SPIRE_ACTION=blocked
      PLAN_PROBLEMS+="${SPIRE_DETAIL}; rerun with --install-platform-components to repair it."$'\n'
    fi
  elif [[ "$SPIRE_STATE" == "existing" ]]; then
    PLAN_SPIRE_ACTION=reuse
    # A previous run of this project installed it: we know the class name.
    if [[ -n "$(installed_components_get spire)" ]]; then
      PLAN_CLASS_NAME="${PLAN_CLASS_NAME:-$SPIRE_CLASS_NAME}"
    fi
    PLAN_CLASS_NAME="${PLAN_CLASS_NAME:-$SPIRE_DETECTED_CLASS_NAME}"
    # Detected from the running SPIRE, else what this project recorded when it installed SPIRE.
    PLAN_TRUST_DOMAIN="${MAOP_SPIRE_TRUST_DOMAIN:-${SPIRE_DETECTED_TRUST_DOMAIN:-$(installed_components_get spire-trust-domain)}}"
    if [[ -n "$MAOP_SPIRE_TRUST_DOMAIN" && -n "$SPIRE_DETECTED_TRUST_DOMAIN" && "$MAOP_SPIRE_TRUST_DOMAIN" != "$SPIRE_DETECTED_TRUST_DOMAIN" ]]; then
      PLAN_PROBLEMS+="MAOP_SPIRE_TRUST_DOMAIN=${MAOP_SPIRE_TRUST_DOMAIN} differs from the existing SPIRE trust domain ${SPIRE_DETECTED_TRUST_DOMAIN}."$'\n'
    fi
    if [[ -z "$PLAN_TRUST_DOMAIN" ]]; then
      PLAN_SPIRE_ACTION=blocked
      PLAN_PROBLEMS+="Existing SPIRE found (${SPIRE_DETAIL}) but its trust domain could not be detected. Set MAOP_SPIRE_TRUST_DOMAIN in .env.local."$'\n'
    fi
  else
    if [[ "$install" == "true" ]]; then
      PLAN_SPIRE_ACTION=install
      PLAN_CLASS_NAME="${PLAN_CLASS_NAME:-$SPIRE_CLASS_NAME}"
      PLAN_TRUST_DOMAIN="$MAOP_SPIRE_TRUST_DOMAIN"
      if [[ -z "$PLAN_TRUST_DOMAIN" ]]; then
        if [[ "$MAOP_PLATFORM" == "openshift" ]]; then
          # docs/security.md §3.1: default to the cluster's apps domain.
          PLAN_TRUST_DOMAIN="$(openshift_apps_domain)"
        else
          PLAN_TRUST_DOMAIN="maop.example"
        fi
      fi
      [[ -n "$PLAN_TRUST_DOMAIN" ]] || { PLAN_SPIRE_ACTION=blocked; PLAN_PROBLEMS+="Could not derive a trust domain; set MAOP_SPIRE_TRUST_DOMAIN."$'\n'; }
    else
      PLAN_SPIRE_ACTION=blocked
      PLAN_TRUST_DOMAIN=""
      PLAN_PROBLEMS+="No SPIRE found. Rerun with --install-platform-components to install the SPIRE charts (${SPIRE_CRDS_CHART} ${SPIRE_CRDS_CHART_VERSION} + ${SPIRE_CHART} ${SPIRE_CHART_VERSION}), or install SPIRE / the Zero Trust Workload Identity Manager yourself."$'\n'
    fi
  fi
  # A release this project installed follows the pin in deploy/platform/versions.env: when the pin moved
  # (upgrade-compat or a manual bump), --install-platform-components upgrades it in place. Installs made by
  # anyone else are only ever reused.
  if [[ "$install" == "true" ]]; then
    local rec
    rec="$(installed_components_get keda)"
    if [[ "$PLAN_KEDA_ACTION" == "reuse-upstream" && -n "$rec" && "${rec##*@}" != "$KEDA_CHART_VERSION" ]]; then
      PLAN_KEDA_ACTION=upgrade; PLAN_KEDA_FROM="${rec##*@}"
    fi
    rec="$(installed_components_get spire)"
    local crds
    crds="$(installed_components_get spire-crds)"
    if [[ "$PLAN_SPIRE_ACTION" == "reuse" && -n "$rec" ]] \
      && { [[ "${rec##*@}" != "$SPIRE_CHART_VERSION" ]] || [[ -n "$crds" && "${crds##*@}" != "$SPIRE_CRDS_CHART_VERSION" ]]; }; then
      PLAN_SPIRE_ACTION=upgrade; PLAN_SPIRE_FROM="${rec##*@}"
    fi
  fi
}
