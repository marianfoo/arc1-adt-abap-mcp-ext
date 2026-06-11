# Architecture

`com.arc1.mcp` is a single OSGi bundle that plugs into the ADT MCP server that
SAP ships inside Eclipse-for-ABAP (**ADT 3.60+**). It contributes additional MCP
tools without modifying any SAP code, and without touching the server lifecycle —
SAP owns starting/stopping the server.

> **Version note**: This describes v0.4.0+ (ADT 3.60). Versions ≤ 0.3.x targeted
> ADT 3.58/3.59 and reflectively started the then-dormant server themselves; see
> `docs/decisions.md` D3 (superseded by D9) for that history.

## High-level flow

```
Eclipse Workbench startup
    │
    ├── OSGi resolves bundles (incl. com.arc1.mcp from dropins/)
    │     requires com.sap.adt.* [3.60.0,4.0.0) — refuses to load on older ADT
    │
    ├── SAP: AdtMcpUIStartupHandler.earlyStartup()  ← org.eclipse.ui.startup
    │     starts the server IFF  -DadtMcpServerPrefEnabled=true
    │                       AND  preference "Enable ADT MCP Server" = true
    │     → AdtMCPCorePlugin.startMCPServer(port, token, FileSystemMode.SFS)
    │       (port/token from the ABAP → MCP Server preference page; default 2234)
    │
    ├── arc1: Arc1Startup.earlyStartup()  ← org.eclipse.ui.startup
    │     ├── logs guidance (how to enable the server)
    │     └── schedules Arc1AutoLogin Job (2s delay), unless -Darc1.mcp.autologin=false
    │             AdtLogonServiceFactory.createLogonService()
    │               .ensureLoggedOn(destData, null, monitor)
    │
    └── On server start, SAP's ToolRegistrationService.registerStaticTools()
          reads the adtMcpTools extension registry:
          ├── SAP's own tools (from SAP bundles)
          └── arc1_sap_* tools (from com.arc1.mcp via plugin.xml)
                   ↓
          All registered with McpSyncServer.addTool(...)

Runtime request:
    Copilot / Claude / Cursor
        ↓ POST http://localhost:2234/mcp   (port = whatever the preference says)
    DNSRebindingProtectionFilter → TokenAuthenticationFilter
        ↓
    HttpServletStreamableServerTransportProvider (Java MCP SDK)
        ↓
    ToolRegistrationService routes by tool name
        ↓
    Arc1SapSearchTool.execute(jsonInput)  ← our code
        ↓
    AdtRisQuickSearchFactory.createQuickSearch(destinationId, monitor)
        .execute(query, maxResults)       ← same Java API ADT UI uses
        ↓
    List<IAdtObjectReference>
        ↓
    JSON-serialize → MCP tool result
```

Note the two `org.eclipse.ui.startup` contributors (SAP's and ours) run in
non-deterministic order — that's fine, because they're independent: SAP starts
the server; we only contribute tools (read from the extension registry whenever
the server starts) and pre-warm logon.

## Extension point

SAP's `com.sap.adt.mcp.core` declares one Eclipse extension point in its `plugin.xml`:

```xml
<extension-point id="adtMcpTools" name="adtMcpTools" schema="schema/adtMcpTools.exsd"/>
```

Any Eclipse bundle on the install can contribute via:

```xml
<extension point="com.sap.adt.mcp.core.adtMcpTools">
  <mcpTool class="<your.fqn.Tool>"/>
</extension>
```

The contributing class must implement `com.sap.adt.mcp.core.IAdtMCPTool`:

```java
public interface IAdtMCPTool {
    String getName();          // [A-Za-z0-9_-] only
    String getDescription();   // no unescaped quotes
    String getInputSchema();   // valid JSON Schema string
    default String getOutputSchema();
    IAdtMcpToolCallResult execute(String jsonInput);
    // 3.60 adds: default execute(String, IProgressMonitor) — delegates to execute(String)
}
```

This is the **same** mechanism SAP uses for its own tools. We are simply another
contributing bundle. On 3.60, SAP's `ToolRegistrationService` invokes
`execute(String, IProgressMonitor)`; its default delegates to our
`execute(String)`, so our tools work unchanged.

## Server activation (SAP-owned)

As of ADT 3.60 the server is a supported feature with its own activation surface
in the new `com.sap.adt.mcp.core.ui` bundle:

- **Preference page** `AdtMcpPreferencePage` (*Preferences → ABAP Development →
  MCP Server*): an *Enable ADT MCP Server* checkbox, a port field (default
  `2234`), and a token field with a *Generate* button. Ticking the box and
  clicking *Apply* calls `startMCPServer(port, token, SFS)` immediately
  (auto-generating a token if the field is blank).
- **Startup handler** `AdtMcpUIStartupHandler`: on boot, auto-starts the server
  **only if** the VM flag `-DadtMcpServerPrefEnabled=true` is set **and** the
  enable preference is on.
- **Defaults** (`AdtMcpPreferences`): enabled = `false`, port = `2234`,
  token = empty.

So the user enables the server once (preference + the VM flag for boot
auto-start). This plugin does **not** call any of this — no reflection, no
kickstart. See `docs/decisions.md` D9.

## Auto-login

Independently of the server, `Arc1AutoLogin.attempt(...)` schedules an Eclipse
`Job` (2s delay so ADT finishes restoring projects) that:

1. Calls `AdtProjectServiceFactory.createProjectService().getAvailableAbapProjects()`.
2. Picks the project whose destination ID matches `-Darc1.mcp.destination`,
   else the first available.
3. If `IAdtLogonService.isLoggedOn(destinationId)` is already true → done.
4. Otherwise, fetches `IDestinationData` via `project.getAdapter(IAdtCoreProject.class).getDestinationData()`.
5. Calls `IAdtLogonService.ensureLoggedOn(destData, null, monitor)`.
   - Saved credentials in Eclipse keyring → silent login.
   - No saved credentials → standard ADT password dialog pops once.

This pre-warms the destination so backend-touching tools succeed on the first
call. It uses public ADT APIs only. Disable via `-Darc1.mcp.autologin=false`.

## Configuration knobs

The server's **port, token, and on/off** are SAP's (preference page +
`-DadtMcpServerPrefEnabled=true`). This plugin adds only:

| Property | Default | Purpose |
|---|---|---|
| `arc1.mcp.destination` | first available ABAP project | Destination ID to auto-login. |
| `arc1.mcp.autologin` | `true` | Set to `false` to disable auto-login. |

## File layout

```
com.arc1.mcp_<version>.jar
├── META-INF/MANIFEST.MF         OSGi headers (Require-Bundle: com.sap.adt.* [3.60.0,4.0.0))
├── plugin.xml                   Extension contributions (11 mcpTool + startup hook)
├── com/arc1/mcp/
│   ├── Arc1McpActivator.class   Plugin singleton + log accessor
│   ├── Arc1Startup.class        IStartup: guidance log + autologin trigger (no reflection)
│   ├── Arc1AutoLogin.class      Background Job that calls ensureLoggedOn
│   ├── AdtHttp.class            HTTP helper (GET + POST, 256 KB cap)
│   ├── Arc1Sap*Tool.class       one class per MCP tool
│   └── Json.class               no-dep JSON helpers
```

## Dependencies

Compile-time and runtime via OSGi `Require-Bundle` (SAP bundles pinned to
`[3.60.0,4.0.0)`):

| Bundle | Why |
|---|---|
| `com.sap.adt.mcp.core` | `IAdtMCPTool`, `IAdtMcpToolCallResult`, `AdtMcpToolCallResultBuilder` (the tool contract) |
| `com.sap.adt.ris.search` | `AdtRisQuickSearchFactory`, `IAdtRisQuickSearch` |
| `com.sap.adt.tools.core` | `AbapCore`, system info, `IAdtObjectReference` |
| `com.sap.adt.tools.core.base` | `AdtProjectServiceFactory`, `IAbapProjectService` |
| `com.sap.adt.project` | `IAdtCoreProject`, destination data accessor |
| `com.sap.adt.destinations` | `AdtLogonServiceFactory`, `IAdtLogonService` |
| `com.sap.adt.destinations.model` | `IDestinationData` |
| `com.sap.adt.communication` | `AdtSystemSessionFactory`, request/response/message types (the HTTP layer) |
| `org.eclipse.core.runtime` | `Plugin` base, `IStatus`, status |
| `org.eclipse.core.resources` | `IProject` (used by Eclipse adapter framework) |
| `org.eclipse.core.jobs` | `Job` (auto-login runs async) |
| `org.eclipse.equinox.common` | `NullProgressMonitor` |
| `org.eclipse.ui` | `IStartup` |

Note: as of 3.60, `com.sap.adt.mcp.core` exports the tool-contract package with
`x-friends` rather than `x-internal`. We are not on the friends list, but Eclipse
does not enforce that at runtime in the default (non-strict) resolver mode, and
`javac` ignores it — so the plugin resolves and compiles. It's a signal worth
tracking, not a current breakage.

No third-party libraries.
