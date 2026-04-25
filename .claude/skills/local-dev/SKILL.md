---
name: local-dev
description: Local development environment setup for this project. Uses k3d for a local Kubernetes cluster. Covers cluster lifecycle, kubeconfig management, and k9s. Consult this when working on local env setup, writing new bin/ scripts for k8s tasks, or debugging the local cluster.
allowed-tools: Bash
---

# Local Development Environment

This project runs **k3d** for local Kubernetes development. All cluster management is handled via `bin/` scripts invoked through `make`.

> For bin/ and Makefile conventions, see the `make-utils` skill.

---

## Prerequisites

Run `make check-deps` to verify everything is installed. Required tools:

| Tool | Purpose | Install |
|---|---|---|
| `docker` | Container runtime (required by k3d) | https://docs.docker.com/engine/install/ |
| `k3d` | Local k3s cluster in Docker | `curl -s https://raw.githubusercontent.com/k3d-io/k3d/main/install.sh \| bash` |
| `kubectl` | Kubernetes CLI | https://kubernetes.io/docs/tasks/tools/ |
| `k9s` | Terminal UI for Kubernetes | https://k9scli.io/topics/install/ |
| `./mill` | Scala build tool | https://mill-build.org/mill/Installation_IDE_Support.html |

---

## Cluster Lifecycle

### Start

```bash
make start                          # create cluster named "unbroken-chain"
make start CLUSTER_NAME=my-cluster  # override the cluster name
```

`bin/start-local-env.sh` will:
1. Run `check-deps.sh` first — aborts if anything is missing
2. Create the k3d cluster if it doesn't exist
3. Mount the repo root at `/repo` inside all cluster nodes
4. Set the kubeconfig context to `k3d-<cluster-name>`

The repo mount at `/repo` is intentional — it allows future GitOps tooling (e.g. ArgoCD) to reference local manifests via `file:///repo`.

### Stop

```bash
make stop
make stop CLUSTER_NAME=my-cluster
```

Deletes the k3d cluster. Does not affect source code or any other local state.

### Check

```bash
make check-deps
```

Verifies tools are installed, Docker daemon is running, and the cluster exists. Safe to run at any time — read-only, no side effects.

---

## Kubeconfig

```bash
make kubeconfig                     # set context to k3d-unbroken-chain
```

Directly calls `kubectl config use-context k3d-<cluster-name>` and prints the active context and nodes to confirm the switch.

`bin/kubeconfig.sh` takes an `<env>` argument. Currently only `local` is supported. Future environments (dev, prod) will be added as cases in the same script.

---

## k9s

```bash
make k9s
```

Launches `k9s` with `--context k3d-<cluster-name>`. k9s is the primary tool for inspecting workloads, logs, and resources in the local cluster.

Useful k9s commands once inside:
- `:pods` — view all pods
- `:svc` — view services
- `:ns` — switch namespace
- `l` — stream logs for selected pod
- `d` — describe selected resource
- `ctrl-c` — quit

---

## Cluster Configuration

The k3d cluster is created with these settings:

| Setting | Value |
|---|---|
| API port | `6550` |
| HTTP ingress | `localhost:8080 → :80` |
| HTTPS ingress | `localhost:8443 → :443` |
| Repo mount | `<repo-root>:/repo@all` |

These are set in `bin/start-local-env.sh`. If the port mappings conflict with something on your machine, override them there.

---

## Extending the Local Environment

As the project grows (Helm charts, ArgoCD, etc.), new scripts should follow the `make-utils` conventions and be added to both `bin/` and the Makefile. The `kubeconfig.sh` env dispatch pattern (`case "$ENV" in local | dev | prod`) is designed to accommodate future environments without changing the interface.
