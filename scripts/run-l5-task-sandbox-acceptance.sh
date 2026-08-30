#!/usr/bin/env bash

set -euo pipefail

# This is an acceptance entry point, not a bootstrapper. It deliberately does
# not install images, Helm releases, a Control Plane, or any credentials.
KUBECTL="${KUBECTL:-kubectl}"
NAMESPACE="${NAMESPACE:-agentteams}"
TIMEOUT="${TIMEOUT:-300s}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="${SCRIPT_DIR}/../deploy/examples"

fail() {
  printf 'L5_ACCEPTANCE_FAIL: %s\n' "$1" >&2
  exit 1
}

if ! command -v "${KUBECTL}" >/dev/null 2>&1; then
  fail "kubectl was not found at '${KUBECTL}'; set KUBECTL to the kubectl executable"
fi
[[ "${NAMESPACE}" =~ ^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$ ]] \
  || fail "NAMESPACE must be a valid Kubernetes namespace name"
[[ -n "${TIMEOUT}" && "${TIMEOUT}" != -* && "${TIMEOUT}" != *[[:space:]]* ]] \
  || fail "TIMEOUT must be a positive kubectl duration such as 300s or 10m"

kube() {
  "${KUBECTL}" "$@"
}

require_resource() {
  local resource="$1"
  local description="$2"
  kube -n "${NAMESPACE}" get "${resource}" >/dev/null \
    || fail "${description} is unavailable in namespace '${NAMESPACE}'"
}

require_cluster_resource() {
  local resource="$1"
  local description="$2"
  kube get "${resource}" >/dev/null \
    || fail "${description} is unavailable"
}

printf 'L5 Linux/KVM TaskSandbox acceptance: namespace=%s timeout=%s\n' \
  "${NAMESPACE}" "${TIMEOUT}"
printf '%s\n' 'Scope: runtime acceptance only; no image, Control Plane, or production credential installation.'

ISOLATED_EXAMPLE="${EXAMPLES_DIR}/task-sandbox-isolated.yaml"
HARDENED_EXAMPLE="${EXAMPLES_DIR}/task-sandbox-hardened.yaml"
ISOLATED_NAME="task-sandbox-l5-isolated"
HARDENED_NAME="task-sandbox-l5-hardened"
ISOLATED_TASK_ID="00000000-0000-0000-0000-000000000101"
HARDENED_TASK_ID="00000000-0000-0000-0000-000000000102"
ISOLATED_ATTEMPT_ID="00000000-0000-0000-0000-000000000201"
HARDENED_ATTEMPT_ID="00000000-0000-0000-0000-000000000202"
SANDBOX_NAMES=()
SANDBOX_TASK_IDS=()

wait_for_delete() {
  local resource="$1"
  if ! kube -n "${NAMESPACE}" get "${resource}" >/dev/null 2>&1; then
    return 0
  fi
  kube -n "${NAMESPACE}" wait --for=delete "${resource}" --timeout="${TIMEOUT}" \
    >/dev/null
}

wait_for_selector_delete() {
  local selector="$1"
  local pod_names
  pod_names="$(kube -n "${NAMESPACE}" get pods -l "${selector}" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null)" || return 1
  [[ -z "${pod_names}" ]] && return 0
  while IFS= read -r pod_name; do
    [[ -z "${pod_name}" ]] && continue
    kube -n "${NAMESPACE}" wait --for=delete "pod/${pod_name}" --timeout="${TIMEOUT}" \
      >/dev/null || return 1
  done <<<"${pod_names}"
}

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e

  printf 'L5 cleanup: deleting TaskSandbox examples\n' >&2
  if (( ${#SANDBOX_NAMES[@]} > 0 )); then
    for index in "${!SANDBOX_NAMES[@]}"; do
      sandbox_name="${SANDBOX_NAMES[${index}]}"
      kube -n "${NAMESPACE}" delete "tasksandbox/${sandbox_name}" \
        --ignore-not-found --wait=true --timeout="${TIMEOUT}" >/dev/null 2>&1 || cleanup_status=1
    done
  fi

  # The Operator owns the Job and Service. Waiting for all three resource
  # types makes an interrupted run leave no acceptance workload behind.
  if (( ${#SANDBOX_NAMES[@]} > 0 )); then
    for sandbox_name in "${SANDBOX_NAMES[@]}"; do
      wait_for_delete "job/${sandbox_name}-job" || cleanup_status=1
      wait_for_delete "service/${sandbox_name}" || cleanup_status=1
    done
  fi
  if (( ${#SANDBOX_TASK_IDS[@]} > 0 )); then
    for task_id in "${SANDBOX_TASK_IDS[@]}"; do
      wait_for_selector_delete \
        "app.kubernetes.io/name=agentteams-task-sandbox,agentteams.io/task-id=${task_id}" \
        || cleanup_status=1
    done
  fi

  if (( cleanup_status == 0 )); then
    printf 'L5 cleanup: complete\n' >&2
  else
    printf 'L5_ACCEPTANCE_FAIL: cleanup did not confirm all generated resources were removed\n' >&2
  fi
  if (( original_status == 0 && cleanup_status != 0 )); then
    original_status=1
  fi
  exit "${original_status}"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

printf 'Checking Kubernetes access and L5 prerequisites...\n'
kube -n "${NAMESPACE}" get namespace "${NAMESPACE}" >/dev/null \
  || fail "cannot access Kubernetes namespace '${NAMESPACE}'"
require_resource runtimeclass/gvisor 'RuntimeClass gvisor'
require_resource runtimeclass/kata-qemu 'RuntimeClass kata-qemu'
require_cluster_resource crd/teams.agentteams.io 'Team CRD teams.agentteams.io'
require_cluster_resource crd/workers.agentteams.io 'Worker CRD workers.agentteams.io'
require_cluster_resource crd/tasksandboxes.agentteams.io 'TaskSandbox CRD tasksandboxes.agentteams.io'

operator_deployments="$(kube -n "${NAMESPACE}" get deployment \
  -l app.kubernetes.io/name=agentteams-operator -o name 2>/dev/null || true)"
if [[ -z "${operator_deployments}" ]]; then
  fail 'no Operator/controller Deployment matched label app.kubernetes.io/name=agentteams-operator; inspect the installed Operator labels'
fi
if ! kube -n "${NAMESPACE}" wait deployment \
  -l app.kubernetes.io/name=agentteams-operator \
  --for=condition=Available --timeout="${TIMEOUT}" >/dev/null; then
  fail 'Operator/controller Deployment matched the label but did not become Available'
fi
printf 'Prerequisites: RuntimeClass gvisor, RuntimeClass kata-qemu, Team/Worker/TaskSandbox CRDs, and labeled Operator/controller are ready.\n'

apply_example() {
  local example_path="$1"
  local name="$2"
  local task_id="$3"

  [[ -f "${example_path}" ]] \
    || fail "required TaskSandbox example is missing: ${example_path}"
  if kube -n "${NAMESPACE}" get "tasksandbox/${name}" >/dev/null 2>&1; then
    fail "TaskSandbox/${name} already exists; refusing to modify a pre-existing resource"
  fi

  # The repository examples target the default production namespace. Keep
  # NAMESPACE configurable without editing or creating another manifest.
  sed "s/^  namespace: agentteams$/  namespace: ${NAMESPACE}/" "${example_path}" \
    | kube -n "${NAMESPACE}" apply -f - >/dev/null
  SANDBOX_NAMES+=("${name}")
  SANDBOX_TASK_IDS+=("${task_id}")
  printf 'Applied TaskSandbox example: %s\n' "${example_path}"
}

apply_example "${ISOLATED_EXAMPLE}" "${ISOLATED_NAME}" "${ISOLATED_TASK_ID}"
apply_example "${HARDENED_EXAMPLE}" "${HARDENED_NAME}" "${HARDENED_TASK_ID}"

wait_for_ready() {
  local name="$1"
  if ! kube -n "${NAMESPACE}" wait --for=jsonpath='{.status.phase}'=READY \
    "tasksandbox/${name}" --timeout="${TIMEOUT}" >/dev/null; then
    local phase message
    phase="$(kube -n "${NAMESPACE}" get tasksandbox "${name}" \
      -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    message="$(kube -n "${NAMESPACE}" get tasksandbox "${name}" \
      -o jsonpath='{.status.message}' 2>/dev/null || true)"
    fail "TaskSandbox/${name} did not become READY (phase=${phase:-unknown}, message=${message:-unavailable})"
  fi
  printf 'TaskSandbox/%s status.phase=READY\n' "${name}"
}

wait_for_ready "${ISOLATED_NAME}"
wait_for_ready "${HARDENED_NAME}"

print_runtime_evidence() {
  local name="$1"
  local task_id="$2"
  local attempt_id="$3"
  local expected_runtime="$4"
  local selector="app.kubernetes.io/name=agentteams-task-sandbox,agentteams.io/task-id=${task_id},agentteams.io/attempt-id=${attempt_id}"
  local job_name pod_name job_runtime pod_runtime node_name guest_kernel host_kernel

  job_name="$(kube -n "${NAMESPACE}" get jobs -l "${selector}" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  [[ -n "${job_name}" ]] \
    || fail "no generated Job found for TaskSandbox/${name} using its Operator labels"
  job_runtime="$(kube -n "${NAMESPACE}" get job "${job_name}" \
    -o jsonpath='{.spec.template.spec.runtimeClassName}' 2>/dev/null || true)"
  [[ -n "${job_runtime}" ]] \
    || fail "cannot read generated Job/${job_name} runtimeClassName"
  [[ "${job_runtime}" == "${expected_runtime}" ]] \
    || fail "Job/${job_name} runtimeClassName=${job_runtime}, expected ${expected_runtime}"

  pod_name="$(kube -n "${NAMESPACE}" get pods -l "job-name=${job_name}" \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  [[ -n "${pod_name}" ]] \
    || fail "no generated Pod found for Job/${job_name}; cannot verify the effective runtime class"
  pod_runtime="$(kube -n "${NAMESPACE}" get pod "${pod_name}" \
    -o jsonpath='{.spec.runtimeClassName}' 2>/dev/null || true)"
  [[ -n "${pod_runtime}" ]] \
    || fail "cannot read generated Pod/${pod_name} runtimeClassName"
  [[ "${pod_runtime}" == "${expected_runtime}" ]] \
    || fail "Pod/${pod_name} runtimeClassName=${pod_runtime}, expected ${expected_runtime}"

  node_name="$(kube -n "${NAMESPACE}" get pod "${pod_name}" \
    -o jsonpath='{.spec.nodeName}' 2>/dev/null || true)"

  printf 'Evidence %s: Job/%s runtimeClassName=%s; Pod/%s runtimeClassName=%s\n' \
    "${name}" "${job_name}" "${job_runtime}" "${pod_name}" "${pod_runtime}"

  # uname is guest-visible evidence. It is optional because kubectl exec may
  # be denied by RBAC or the runner may not expose the command yet.
  if guest_kernel="$(kube -n "${NAMESPACE}" exec "${pod_name}" -c sandbox -- uname -a \
    2>/dev/null)" && [[ -n "${guest_kernel}" ]]; then
    printf 'Evidence %s: guest kernel (Pod exec)=%s\n' "${name}" "${guest_kernel}"
  else
    printf 'Evidence %s: guest kernel unavailable (kubectl exec permission or runner command unavailable)\n' \
      "${name}"
  fi

  # Node status is host-kernel evidence. Never substitute the guest value or a
  # guessed version if node metadata is not readable.
  if [[ -n "${node_name}" ]] \
    && host_kernel="$(kube get node "${node_name}" \
      -o jsonpath='{.status.nodeInfo.kernelVersion}' 2>/dev/null)" \
    && [[ -n "${host_kernel}" ]]; then
    printf 'Evidence %s: host kernel (Node/%s status.nodeInfo.kernelVersion)=%s\n' \
      "${name}" "${node_name}" "${host_kernel}"
  else
    printf 'Evidence %s: host kernel unavailable (Node metadata permission or field unavailable)\n' \
      "${name}"
  fi
}

print_runtime_evidence "${ISOLATED_NAME}" "${ISOLATED_TASK_ID}" \
  "${ISOLATED_ATTEMPT_ID}" gvisor
print_runtime_evidence "${HARDENED_NAME}" "${HARDENED_TASK_ID}" \
  "${HARDENED_ATTEMPT_ID}" kata-qemu

printf '%s\n' 'L5_LINUX_KVM_ACCEPTANCE_OK: both profiles reached READY with verified Job/Pod runtimeClassName values.'
