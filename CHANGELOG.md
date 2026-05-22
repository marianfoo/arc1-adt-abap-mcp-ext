# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-05-22

Extends the HTTP foundation from v0.2 with POST support and ships one typed
endpoint wrapper on top.

### Added
- `AdtHttp.post(...)` — POST counterpart to `AdtHttp.get(...)`, accepts a
  body and Content-Type. Uses `com.sap.adt.communication.message.ByteArrayMessageBody`.
- `arc1_sap_http_post` — generic POST escape hatch. UTF-8 string body.
- `arc1_sap_list_transports` — list ABAP transport requests via
  `/sap/bc/adt/cts/transportrequests` with filters (username, status,
  requestType). Optional `parse=true` returns a minimal structured list.

### Deferred to v0.4
- `arc1_sap_where_used` — endpoint URI is verified but XML request body
  shape needs an Eclipse HTTP trace to confirm.
- `arc1_sap_object_structure` — URI literal not cleanly extractable from
  bytecode; also needs trace capture.

## [0.2.0] - 2026-05-22

Adds an HTTP foundation that bypasses Eclipse's workspace-`IFile` model and
unblocks any future tool that maps to an ADT REST endpoint.

### Added
- `AdtHttp` internal helper: thin facade over `AdtSystemSessionFactory` /
  `AdtRequestFactory` / `HeadersFactory` for stateless GET-style calls.
  Caps response bodies at 256 KB.
- `arc1_sap_http_get` — generic ADT GET escape hatch. Authenticated by
  Eclipse's destination machinery. Use for any endpoint without a typed
  wrapper yet.
- `arc1_sap_read_source` — fetch source code for an ABAP object URI (or
  source URI directly). For CLAS, supports include segments
  (definitions / implementations / testclasses / macros).

### Dependencies
- `Require-Bundle` now includes `com.sap.adt.communication;[3.58.0,4.0.0)`.

## [0.1.0] - 2026-05-22

Initial release. Wakes the dormant SAP ADT MCP server in Eclipse 2025-09 / ADT 3.58
and contributes 7 read-only tools via the `com.sap.adt.mcp.core.adtMcpTools`
extension point.

### Added
- `Arc1Startup` (IStartup) that reflectively calls `AdtMCPCorePlugin.startMCPServer`
  on workbench startup, with forward-compat detection if SAP later enables it natively.
- `Arc1AutoLogin` background Job that calls `IAdtLogonService.ensureLoggedOn` on the
  configured or first-available ABAP project, populating the destination registry.
- Tools:
  - `arc1_sap_search` — quick repository search (RIS)
  - `arc1_sap_repository_search` — parameterized search with type/package/user filters
  - `arc1_sap_object_info` — object metadata via `IAdtRisVfsObjectPropertiesService`
  - `arc1_sap_find_definition` — go-to-definition via `IAbapNavigationServices`
  - `arc1_sap_list_projects` — workspace ABAP projects with login state
  - `arc1_sap_system_info` — software components, servers, status, clients
  - `arc1_sap_object_types` — workbench type registry catalog
- Configuration via JVM system properties in `eclipse.ini`.
- Token file at `~/.config/arc1/mcp-token.txt` for client config convenience.
- Smoke test script.
