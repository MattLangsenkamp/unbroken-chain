# Adding a New Operator

Operators provide CRDs that the infra chart's templates depend on. They are installed exclusively by `bin/deploy-local.sh` step 2 — never bundled as Helm chart dependencies.

---

## 1. Add the operator install step to `bin/deploy-local.sh`

Find step 2 and add a new install block. Use `helm upgrade --install` for Helm-distributed operators:

```bash
echo "  My Operator..."
helm upgrade --install my-operator my-operator-chart \
  --repo https://my-operator.example.com/charts \
  --namespace my-operator-system \
  --create-namespace \
  --wait
```

Use `kubectl apply -f` for operators distributed as raw manifests (e.g. RabbitMQ Cluster Operator, whose bitnami images are unavailable on Docker Hub):

```bash
echo "  My Operator..."
kubectl apply -f "https://github.com/myorg/my-operator/releases/latest/download/operator.yml"
kubectl rollout status deployment/my-operator -n my-operator-system --timeout=5m
```

Always block until the operator is ready before proceeding.

> **Webhook cert note:** If the operator uses auto-generated admission webhook certs (no cert-manager), add a rollout restart after the helm install to force cert regeneration on upgrades:
> ```bash
> kubectl rollout restart deployment/my-operator -n my-operator-system
> kubectl rollout status deployment/my-operator -n my-operator-system --timeout=2m
> ```
> Without this, subsequent `make deploy-app` runs will fail with TLS cert errors from the webhook.

If the operator's CRDs need to be available before the infra chart deploys, add `kubectl wait` after install:

```bash
kubectl wait --for=condition=Established crd/myresources.my-operator.io --timeout=60s
```

> **API version:** Always check `kubectl api-resources | grep my-operator` after install to confirm the served API version. Templates must use the version actually served — not whatever the docs say — or Helm will fail with "resource mapping not found".

---

## 2. Add the operator's CR to the infra chart templates

Create a new template file in `umbrella/templates/` for the operator's Custom Resource:

```yaml
# umbrella/templates/my-resource.yaml
apiVersion: my-operator.io/v1
kind: MyResource
metadata:
  name: unbroken-chain-my-resource
  namespace: {{ .Release.Namespace }}
spec:
  # driven by values from umbrella/values.yaml
```

Add corresponding values to `umbrella/values.yaml`:

```yaml
myResource:
  someOption: value
```

---

## 3. Update the SKILL.md tables

Add a row to the operator table and API versions table in `SKILL.md`.

---

## Checklist

- [ ] Install block added to `bin/deploy-local.sh` step 2 (blocks until ready)
- [ ] Webhook cert restart added if operator uses auto-generated certs
- [ ] `kubectl wait --for=condition=Established` added for CRDs needed by infra chart
- [ ] Verified served API version with `kubectl api-resources`
- [ ] CR template added to `umbrella/templates/`
- [ ] Values added to `umbrella/values.yaml`
- [ ] Operator table and API versions table updated in `SKILL.md`
