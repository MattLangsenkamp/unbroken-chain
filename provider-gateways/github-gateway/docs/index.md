# GitHub Gateway

The GitHub Gateway is the provider gateway that connects a UBC deployment to GitHub. It creates
the deployment's own GitHub App, mirrors installed repos into Postgres, and keeps that mirror in
sync via webhooks and reconcile jobs.

A deployment owns its GitHub App rather than depending on a centrally-operated one. Bringing a
deployment online is therefore a two-stage handshake:

- **[App provisioning flow](./flows/app_provisioning_flow.md)** — a one-time, per-deployment
  bootstrap that creates and stores the deployment's GitHub App via GitHub's App Manifest flow.
  _Planned; not yet implemented._
- **[Linking flow](./flows/linking_flow.md)** — installs that app on an account or org and
  mirrors the selected repos. Runs once per linked installation, after provisioning.
