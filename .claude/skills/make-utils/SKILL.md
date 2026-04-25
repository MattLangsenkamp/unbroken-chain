---
name: make-utils
description: Conventions for the Makefile and bin/ scripts in this project. All logic lives in bin/ shell scripts — the Makefile is a thin wrapper that maps targets to scripts and centralizes configuration variables. Consult this before adding any new make targets or bin/ scripts.
allowed-tools: Bash
---

# Make / bin Conventions

The rule is simple: **the Makefile holds configuration variables and target names. All logic lives in self-contained bash scripts under `bin/`.**

Never put logic directly in a Makefile target. If a target needs more than one line, it belongs in `bin/`.

---

## Makefile Structure

```makefile
CLUSTER_NAME ?= unbroken-chain   # ← configurable via environment or CLI

SCRIPT_DIR := bin

.PHONY: my-target

## my-target : short description shown by `make help`
my-target:
	@$(SCRIPT_DIR)/my-target.sh $(CLUSTER_NAME)
```

- Use `?=` for overridable variables (e.g. `make start CLUSTER_NAME=foo`)
- Use `:=` for fixed variables (e.g. `SCRIPT_DIR := bin`)
- Every target must be in `.PHONY`
- Every target must have a `## target : description` comment — this is what `make help` surfaces

### help target

Always maintain a `help` target using this pattern so `make help` self-documents the file:

```makefile
help:
	@grep -E '^## ' Makefile | sed 's/## //'
```

---

## bin/ Script Conventions

### Header block

Every script starts with a comment block documenting its purpose and arguments:

```bash
#!/bin/bash
# script-name.sh [arg1] [arg2]
#
# One-line summary of what this script does.
# More detail if needed.
#
# Args:
#   $1  arg1 : description, defaults to "default-value"
#   $2  arg2 : description (required)
```

### Required boilerplate

```bash
set -e   # exit immediately on any error — always the first line after the header
```

### SCRIPT_DIR pattern

Any script that calls another script must resolve its own directory so calls work regardless of where the script is invoked from:

```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/other-script.sh" "$ARG"
```

### Argument defaults

Use shell parameter expansion consistently:

```bash
# Optional argument with default
CLUSTER_NAME="${1:-unbroken-chain}"

# Required argument — fails immediately with usage message if missing
ENV="${1:?Usage: script.sh <env> [cluster_name]}"
```

Never use positional args without assigning them to a named variable first.

### Output style

- `✅` for success
- `⚠️` for non-fatal warnings
- `❌` for fatal errors (always followed by `exit 1`)
- `echo ""` between logical sections for readability

### Composing scripts

Composite scripts call other scripts via `$SCRIPT_DIR`, never via `make`:

```bash
"$SCRIPT_DIR/check-deps.sh"     "$CLUSTER_NAME"
"$SCRIPT_DIR/start-local-env.sh" "$CLUSTER_NAME"
```

### Permissions

All scripts in `bin/` must be executable (`chmod +x`). When adding a new script, run:

```bash
chmod +x bin/my-script.sh
git add bin/my-script.sh
```

---

## Adding a New Target

1. Write the logic in `bin/my-script.sh` following the conventions above
2. Make it executable: `chmod +x bin/my-script.sh`
3. Add a `.PHONY` target and `##` comment to the Makefile:
   ```makefile
   .PHONY: my-target
   
   ## my-target : what it does
   my-target:
   	@$(SCRIPT_DIR)/my-script.sh $(CLUSTER_NAME)
   ```
4. Verify `make help` lists it correctly

---

## Current Targets

| Target | Script | Description |
|---|---|---|
| `make check-deps` | `bin/check-deps.sh` | Verify all required tools are installed |
| `make start` | `bin/start-local-env.sh` | Create and start the local k3d cluster |
| `make stop` | `bin/stop-local-env.sh` | Delete the local k3d cluster |
| `make kubeconfig` | `bin/kubeconfig.sh` | Set kubectl context to the local cluster |
| `make k9s` | `bin/k9s.sh` | Launch k9s for the local cluster |
| `make build-images` | _(inline + scripts)_ | Build all JVM and presentation Docker images |
| `make build-presentations` | `bin/build-presentation.sh` | Build all presentation nginx images |
| `make build-ubc-presentation` | `bin/build-presentation.sh` | Build ubc-control-plane presentation image |
| `make build-github-gateway-presentation` | `bin/build-presentation.sh` | Build github-gateway presentation image |
| `make load-images` | `bin/load-images.sh` | Import all images into k3d |
| `make deploy-images` | _(build-images + load-images)_ | Build and import all images |
| `make deploy-app` | `bin/deploy-local.sh` | Deploy full stack to local k3d cluster |
| `make prepare-migrations` | `bin/build-migrations.sh` | Build and load all migration images |
| `make psql-github-gateway` | `bin/psql.sh` | Open psql for github_gateway database |
| `make psql-control-plane` | `bin/psql.sh` | Open psql for ubc_control_plane database |
| `make help` | _(inline)_ | List all available targets |

## Makefile Variables

| Variable | Default | Override example |
|---|---|---|
| `CLUSTER_NAME` | `unbroken-chain` | `make start CLUSTER_NAME=my-cluster` |
| `UBC_PRESENTATION_IMAGE` | `unbrokenchain/ubc-control-plane-presentation:latest` | `make build-ubc-presentation UBC_PRESENTATION_IMAGE=myrepo/img:v1` |
| `GITHUB_GATEWAY_PRESENTATION_IMAGE` | `unbrokenchain/github-gateway-presentation:latest` | `make build-github-gateway-presentation GITHUB_GATEWAY_PRESENTATION_IMAGE=myrepo/img:v1` |

Presentation targets pass `$(CLUSTER_NAME)` to the script, so `make build-ubc-presentation CLUSTER_NAME=my-k3d` will build **and** import into k3d in one step.
