# Security

## Reporting vulnerabilities

If you discover a security issue, please **do not** open a public GitHub
issue. Email the maintainer directly. Coordinated disclosure preferred.

## Threat model

This plugin runs inside your Eclipse process and exposes an MCP server on
`localhost`. The threat model is:

- **In scope**: cross-process attacks against the local MCP endpoint
  (token bypass, DNS rebinding, etc.), accidental information disclosure
  from a tool, code-injection via malicious tool input.
- **Out of scope**: anything that requires access to your Eclipse workspace
  files (an attacker with file read on `eclipse.ini` already has the bearer
  token and your SAP credentials in the keyring).

## Existing mitigations (inherited from SAP's MCP server)

- Server binds to `localhost` only (never network-routable).
- Bearer-token authentication on every request.
- `Host` header validation against `localhost`/`127.0.0.1` (DNS rebinding).
- No CORS — browsers can't reach the endpoint cross-origin.

## What this plugin adds

- Configurable token via `arc1.mcp.token` so users can rotate or pin.
- No telemetry, no outbound HTTP from this plugin.

## What this plugin does NOT do

- It does not enforce any safety policy on the **SAP-shipped** tools we
  activate (`abap_generators-generate_objects`, `abap_transport-create`).
  Those go straight to your SAP backend using your own SAP authorizations.
- It does not run with elevated privileges. Everything happens as the user
  running Eclipse.

If you need admin-side policy ceiling (write gates, package allowlists,
audit logging), use **ARC-1** instead — that's the centralized managed
counterpart designed for that use case.
