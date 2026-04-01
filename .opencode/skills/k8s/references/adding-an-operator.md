# Adding a New Operator

Operators provide Custom Resource Definitions (CRDs) that the umbrella chart's templates depend on. They are managed exclusively by `bin/deploy-local.sh` — never bundled as Helm chart dependencies in `Chart.yaml`.

---

## 1. Add the operator install step to `bin/deploy-local.sh`

Find the operator install section (step 2) and add a new `helm upgrade --install` block:

```bash
echo "  My Operator..."
helm upgrade --install my-operator my-operator-chart \
  --repo https://my-operator.example.com/charts \
  --namespace my-operator-system \
  --create-namespace \
  --wait
```

Use `oci://` prefix for OCI-registry-hosted charts:

```bash
helm upgrade --install my-operator \
  oci://registry-1.docker.io/someorg/my-operator-chart \
  --namespace my-operator-system \
  --create-namespace \
  --wait
```

Always include `--wait` so the script blocks until the operator is ready before proceeding to the helm install of the umbrella chart.

---

## 2. Add the CRD check to `umbrella/templates/pre-install-check.yaml`

The pre-install hook job validates that all required CRDs are present before templates render. Add a `check_crd` call for each CRD introduced by the new operator:

```bash
check_crd myresources.my-operator.io
```

Find the CRD name by running (after installing the operator manually):

```bash
kubectl get crds | grep my-operator
```

---

## 3. Add the operator's CR to the umbrella templates

Create a new template file in `umbrella/templates/` for the operator's Custom Resource:

```yaml
# umbrella/templates/my-resource.yaml
apiVersion: my-operator.io/v1
kind: MyResource
metadata:
  name: unbroken-chain-my-resource
  namespace: {{ .Release.Namespace }}
spec:
  # ... driven by values from umbrella/values.yaml
```

Add corresponding values to `umbrella/values.yaml` under a descriptive top-level key:

```yaml
myResource:
  someOption: value
```

---

## 4. Update the SKILL.md operator table

Add a row to the operator table in `.opencode/skills/k8s/SKILL.md`:

```markdown
| My Operator | `my-operator-chart` | `https://my-operator.example.com/charts` |
```

---

## Checklist

- [ ] `helm upgrade --install` block added to `bin/deploy-local.sh` (step 2, with `--wait`)
- [ ] CRD name(s) added to `pre-install-check.yaml`
- [ ] CR template added to `umbrella/templates/`
- [ ] Values added to `umbrella/values.yaml`
- [ ] Operator table in `SKILL.md` updated
