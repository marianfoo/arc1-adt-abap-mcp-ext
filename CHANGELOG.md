# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Documentation
- Document the relationship to [`adt-ls`](https://github.com/marianfoo/adt-ls)
  (the TypeScript SDK over SAP's headless ABAP language server) and record why
  this plugin does **not** consume it as a dependency: decision **D10** in
  `docs/decisions.md`, a "What about `adt-ls`?" FAQ entry plus a pick-by-where-
  you-run table in `README.md`, a Non-goals pointer in `CLAUDE.md`, and a note
  in `docs/architecture.md`. No code changes.

## [0.5.0] - 2026-06-12

Adds seven read-only navigation / structure / quality tools (11 → 18) on top of
SAP's MCP server, plus a JDK-only `Xml` parsing helper that backs them. No new
third-party dependencies; no server-lifecycle changes.

### Added
- **`arc1_sap_where_used`** — where-used / find references (impact analysis) via
  `repository/informationsystem/usageReferences`.
- **`arc1_sap_package_contents`** — list a package's objects via
  `repository/nodestructure`.
- **`arc1_sap_object_structure`** — object outline (methods/attributes/events/
  includes) via `…/objectstructure`.
- **`arc1_sap_list_inactive`** — inactive (un-activated) objects via
  `activation/inactiveobjects`.
- **`arc1_sap_check_syntax`** — syntax check without activation via `checkruns`;
  an optional inline `source` checks proposed code transiently (nothing written).
- **`arc1_sap_run_unit_tests`** — run an object's ABAP Unit tests via
  `abapunit/testruns` (executes tests server-side; no repository change).
- **`arc1_sap_atc_check`** — ABAP Test Cockpit static analysis via the
  `atc/worklists` + `atc/runs` flow (system-default check variant when none given).
- **`Xml`** helper — minimal `javax.xml` DOM reader (by local name, namespace-
  agnostic) so XML-returning tools emit structured JSON. JDK-only, so D8 (no
  third-party deps) still holds.

### Changed
- `META-INF/MANIFEST.MF`: `Bundle-Version` 0.4.0 → 0.5.0; add `Import-Package`
  for `javax.xml`, `javax.xml.parsers`, `org.w3c.dom`, `org.xml.sax` (used by the
  new `Xml` helper).
- `AdtHttp` Javadoc reworded (GET+POST are current; dropped the "POST comes in
  v0.3" note).

### Notes
- All seven tools are read-only with respect to the repository. `run_unit_tests`
  and `atc_check` execute checks/tests on the backend (same as the corresponding
  ADT menu actions) — their descriptions state this.
- Each REST tool degrades gracefully: it guards the 256 KB body cap, returns a
  best-effort structured parse, and includes `rawXml` when parsing is empty — so
  it stays useful even where a backend's exact XML layout differs by release.
- `arc1_sap_find_occurrences` (the Java-API local-occurrences tool from plan 02)
  is intentionally deferred: its SAP factory-instantiation path is unverified and
  it shares the compile unit with these tools. Tracked in
  `docs/plans/07-v0.5-read-tools-expansion.md`.

## [0.4.0] - 2026-06-11

**Requires ADT 3.60+.** ADT 3.60 ships SAP's MCP server as a supported feature
with its own activation surface (a *Preferences → ABAP Development → MCP Server*
page plus the `-DadtMcpServerPrefEnabled=true` startup flag). This release drops
the reflective server-kickstart that earlier versions used to wake the dormant
3.58/3.59 server, and turns the plugin into a **pure tool-provider**: its
`<mcpTool>` contributions register on SAP's server automatically, with no
reflection into SAP internals.

### Changed
- **`Arc1Startup`** no longer starts the MCP server. It only logs guidance and
  (optionally) pre-warms the destination logon. All reflection into
  `com.sap.adt.mcp.core.internal.AdtMCPCorePlugin` (`startMCPServer`,
  `peekRunningPort`) is removed.
- **`META-INF/MANIFEST.MF`**: SAP bundle requirements bumped from
  `[3.58.0,4.0.0)` to `[3.60.0,4.0.0)`. The plugin now refuses to load on ADT
  < 3.60 (where the supported activation does not exist) rather than failing at
  runtime.
- **Server lifecycle is now SAP's**: port, token, and on/off live on SAP's
  preference page. Default port is **2234** (SAP's default), not 54322.
- `scripts/smoke-test.sh` now reads the endpoint + token from `ARC1_MCP_URL` /
  `ARC1_MCP_TOKEN` env vars instead of `~/.config/arc1/mcp-token.txt`.

### Removed
- The reflective kickstart of `AdtMCPCorePlugin.startMCPServer(...)` (rationale:
  superseded by SAP's 3.60 activation — see `docs/decisions.md` D9, which
  supersedes D3).
- Writing `~/.config/arc1/mcp-token.txt` — the token is shown on SAP's
  preference page; the plugin no longer manages tokens.
- Configuration knobs that controlled the server: `arc1.mcp.token`,
  `arc1.mcp.port`, `arc1.mcp.kickstart`. Only `arc1.mcp.destination` and
  `arc1.mcp.autologin` remain.

### Compatibility
- Tools are unchanged and source-compatible: `IAdtMCPTool.execute(String)` is
  still honored on 3.60 (SAP's `execute(String, IProgressMonitor)` default
  delegates to it). The `adtMcpTools` extension point and tool-name validation
  (`^[A-Za-z0-9_-]+$`) are byte-identical to 3.58.

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
