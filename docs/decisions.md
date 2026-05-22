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
