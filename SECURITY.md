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

- Extra **read-only** MCP tools (search, read source, metadata, transports,
  authenticated HTTP escape hatches). They run with your existing
  ABAP-project authorizations.
- No telemetry, no outbound HTTP from this plugin.

The server's bearer token and port are managed by SAP (the *ABAP Development →
MCP Server* preference page, as of ADT 3.60); this plugin does not handle,
store, or write the token.

## What this plugin does NOT do

- It does not enforce any safety policy on the **SAP-shipped** tools
  (`abap_generators-generate_objects`, `abap_transport-create`), which SAP
  ships and registers itself. Those go straight to your SAP backend using your
  own SAP authorizations.
- It does not run with elevated privileges. Everything happens as the user
  running Eclipse.

If you need admin-side policy ceiling (write gates, package allowlists,
audit logging), use **ARC-1** instead — that's the centralized managed
counterpart designed for that use case.
