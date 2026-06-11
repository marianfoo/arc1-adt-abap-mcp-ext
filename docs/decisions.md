# Decisions

Non-obvious technical choices and the reasoning behind them.

## D1. Wrap Eclipse's Java ADT services instead of reimplementing ADT REST clients

**Decision**: Tools call existing ADT Java APIs (`AdtRisQuickSearchFactory`,
`IAdtLogonService`, etc.) directly inside the Eclipse process. They do NOT
make raw HTTP calls to `/sap/bc/adt/...` themselves.

**Why**: Eclipse already manages auth, session cookies, CSRF tokens, destination
state, and connection pooling. Re-doing that work in this plugin would duplicate
~80% of what ARC-1's TypeScript code has to do (because ARC-1 runs out-of-process).
Inside Eclipse we get all of that for free.

**Implication**: This plugin is fundamentally Eclipse-bound. It can never run
outside Eclipse — that's the whole point. ARC-1 (separate Node.js MCP server)
remains the option for headless / BTP / multi-client-server deployments.

## D2. Use OSGi extension point, not reflection injection

**Decision**: Tools are contributed via the `com.sap.adt.mcp.core.adtMcpTools`
extension point in `plugin.xml`. We do not reflectively call `addTool` on
`McpSyncServer` ourselves.

**Why**: SAP designed the extension point as the public contract. Three other
SAP bundles already use it (objectgenerator, tm.model, cds.servicebinding), so
it's clearly the intended API. It also survives `registerExtensionTools()`
being called multiple times — Eclipse extension registry is the source of
truth.

**Trade-off**: Tools registered after the server cold-starts won't appear until
the next `registerExtensionTools()` call. We accept this — `-clean` restart
is acceptable for adding new tools during development.

## D3. Reflectively kickstart the dormant MCP server (instead of waiting for SAP)

> **Superseded by [D9](#d9-pure-tool-provider-on-adt-360-drop-the-kickstart) as of
> v0.4.0 / ADT 3.60.** SAP shipped the activation surface this decision was
> waiting for, so the reflective kickstart was removed. Kept here for history —
> it still describes how versions ≤ 0.3.x behave on ADT 3.58/3.59.

**Decision**: `Arc1Startup.earlyStartup()` reflectively calls
`AdtMCPCorePlugin.getInstance().startMCPServer(port, token)` to wake the dormant
server.

**Why**: SAP ships the server code but no activation surface (no UI command,
preference, or auto-start). Without our kickstart, the server stays asleep and
no MCP client can reach it.

**Forward-compat**: When SAP enables their own activation, `peekRunningPort()`
detects the running server and our code no-ops. The reflection path becomes
dead code that's harmless.

## D4. `arc1_sap_search` takes `destination` as input rather than relying on `setDestination`

**Decision**: Every backend-touching tool requires `destination` as an MCP input
argument. We do not call `AdtMCPCorePlugin.setDestination(...)` from
`Arc1Startup`.

**Why**: The bytecode of `ADTMCPServer.setDestinationId(String)` has an
early-return bug — the first call returns without setting anything. Codex's
forced-activation probe confirmed this. Working around it requires bootstrap
reflection into a private field. Passing destination per-call sidesteps the
issue entirely and matches SAP's own static-tool pattern (their RAP generator
tools all take `destination` as an argument too).

**Bonus**: Multi-destination MCP clients can use different destinations per
call. No per-server-instance destination lock-in.

## D5. Auto-login on a background `Job`, not on the startup thread

**Decision**: `Arc1AutoLogin.attempt(...)` schedules an Eclipse `Job` with a
2-second delay rather than running `ensureLoggedOn` directly in
`earlyStartup()`.

**Why**:
- `ensureLoggedOn` may pop up a password dialog. Blocking the workbench
  startup thread on a UI dialog is bad UX.
- ADT itself restores project state on startup. A 2s delay lets ADT finish
  its own bookkeeping before we ask for the destination's logon state.
- If the auto-login fails (saved creds stale, system unreachable), the user
  still has a working Eclipse — only our tool falls back to manual login.

## D6. Pinned bearer token via system property, not file-based

> **Superseded by [D9](#d9-pure-tool-provider-on-adt-360-drop-the-kickstart) as of
> v0.4.0 / ADT 3.60.** The token is now SAP's concern — it's set/generated on the
> *ABAP Development → MCP Server* preference page. This plugin no longer reads
> `-Darc1.mcp.token` or writes a token file. Kept for history (applies to ≤ 0.3.x).

**Decision**: `Arc1Startup` reads `-Darc1.mcp.token=...` first, generates a
random token only if not set.

**Why**: A file-based persistent token would either need to be readable by
random local processes (bad — any process with the token can call any tool)
or live in a strict-mode file the user manages. A system property in
`eclipse.ini` is set once by the user, isn't readable by other processes, and
is trivially rotatable.

## D7. Tool name prefix `arc1_sap_*`

**Decision**: Every tool name starts with `arc1_sap_`.

**Why**:
- Satisfies SAP's validator regex `[A-Za-z0-9_-]`.
- Makes it obvious in `tools/list` which tools come from this plugin vs.
  SAP's `abap_*` built-ins.
- Allows future tools without name collisions.

## D8. No third-party dependencies

**Decision**: No Jackson, Gson, slf4j, etc. JSON is built by hand in `Json.java`.

**Why**:
- Eclipse OSGi already loads multiple Jackson versions across SAP bundles.
  Pulling in another would risk version conflicts.
- Each MCP tool's I/O is tiny flat JSON. Hand-coding is ~80 lines and avoids
  the dependency.
- The build script becomes trivial: `javac` + `jar`, no Maven/Tycho/Ivy.

## D9. Pure tool-provider on ADT 3.60 (drop the kickstart)

**Decision**: As of v0.4.0 (ADT 3.60+), `Arc1Startup` no longer starts the MCP
server. The plugin contributes tools via the extension point and nothing else
touches the server lifecycle. All reflection into
`com.sap.adt.mcp.core.internal.AdtMCPCorePlugin` (the `startMCPServer` kickstart
and the `peekRunningPort` field-peek) is removed. This **supersedes [D3](#d3-reflectively-kickstart-the-dormant-mcp-server-instead-of-waiting-for-sap)**.

**Why**: ADT 3.60 ships the activation surface D3 was working around:

- A new bundle `com.sap.adt.mcp.core.ui` with an *ABAP Development → MCP Server*
  preference page (`AdtMcpPreferencePage`) — enable checkbox, port, token, a
  *Generate* button; ticking + Apply starts the server immediately.
- A startup handler `AdtMcpUIStartupHandler` (an `org.eclipse.ui.startup`
  contributor) that auto-starts the server on boot **iff** the VM flag
  `-DadtMcpServerPrefEnabled=true` is set **and** the enable preference is on.
- Defaults (`AdtMcpPreferences.setPreferenceDefaults`): enabled = `false`,
  port = `2234`, token = empty. So the server is supported but **off by default**,
  double-gated.

Two facts made keeping the kickstart untenable on 3.60:

1. **The signature changed.** `startMCPServer(int, String)` became
   `startMCPServer(int, String, IAdtMcpEnvironmentInfo.FileSystemMode)`. The old
   reflective call threw `NoSuchMethodException` at runtime (invisible to the
   compiler, since the call is reflective).
2. **The package was locked down.** `com.sap.adt.mcp.core` went from
   `x-internal:=true` to `x-friends:="com.sap.adt.atc.ui, …"` — a clear "external
   code should not bind this" signal (not runtime-enforced by default, but a
   standing risk if SAP turns on strict resolution).

**Alternatives considered**:
- *Fix the kickstart for 3.60* (3-arg call + `FileSystemMode.SFS`, the value
  SAP's own UI passes). Works, but keeps reflecting into a now-`x-friends`
  internal package to do something SAP now supports through a preference. Rejected
  in favor of going fully standard.
- *Standard-first hybrid* (auto-start only when SAP's native activation is off).
  More moving parts than the value justifies for a single-user desktop plugin.

**Cost we accepted**: the server no longer auto-starts from just dropping the
JAR. Users enable SAP's server once (preference toggle + `-DadtMcpServerPrefEnabled=true`).
In exchange the plugin is fully supported-surface-only: no reflection, smaller
attack/break surface, and it can't fight SAP over the port or token (D-note:
SAP's `ADTMCPServer.start()` stops and re-binds if asked to start on a different
port than the one already running — exactly the clobbering we now avoid by not
starting it at all).

**What did *not* change**: the `<mcpTool>` extension point, tool-name validation
(`^[A-Za-z0-9_-]+$`), and `IAdtMCPTool.execute(String)` are all unchanged on
3.60, so every tool keeps working as-is. See `docs/plans/06-v0.4-pure-tool-provider.md`.
