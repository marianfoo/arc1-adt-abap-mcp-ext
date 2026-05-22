# Architecture

`com.arc1.mcp` is a single OSGi bundle that plugs into the ADT MCP server that SAP
ships inside Eclipse-for-ABAP (ADT 3.58+). It contributes additional MCP tools without
modifying any SAP code.

## High-level flow

```
Eclipse Workbench startup
    │
    ├── OSGi resolves bundles (incl. com.arc1.mcp from dropins/)
    │
    ├── Arc1Startup.earlyStartup()  ← invoked via org.eclipse.ui.startup
    │     ├── (a) Detect SAP MCP server already running → no-op kickstart
    │     │       Reflection: AdtMCPCorePlugin#mcpServer.httpServer != null
    │     ├── (b) Otherwise: reflectively call
    │     │       AdtMCPCorePlugin.getInstance().startMCPServer(port, token)
    │     │       Token: -Darc1.mcp.token if set, else SecureRandom 24 bytes
    │     │       Port:  -Darc1.mcp.port  if set, else 54322
    │     │       Writes ~/.config/arc1/mcp-token.txt for the human user
    │     └── (c) Schedule Arc1AutoLogin Job (2s delay)
    │             AdtLogonServiceFactory.createLogonService()
    │               .ensureLoggedOn(destData, null, monitor)
    │
    └── SAP's ToolRegistrationService discovers extension contributions
          ├── 8 SAP tools (from 4 SAP bundles)
          └── arc1_sap_* tools (from com.arc1.mcp via plugin.xml)
                   ↓
          All registered with McpSyncServer.addTool(...)

Runtime request:
    Copilot / Claude / Cursor
        ↓ POST http://localhost:54322/mcp
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
}
```

This is the **same** mechanism SAP uses for their own 8 tools (which sit in 4
different bundles outside `com.sap.adt.mcp.core`). We are simply bundle #5.

## Kickstart and forward-compat

`AdtMCPCorePlugin.startMCPServer(int port, String token)` exists in ADT 3.58 but
SAP has not shipped a UI command, preference, or auto-start that calls it. We
work around this by reflectively invoking it from `IStartup.earlyStartup()`.

When SAP eventually ships their own activation switch, our kickstart path
detects the already-running state and no-ops. The `mcpTool` extension
contribution remains the canonical wiring path and is forward-compatible.

Two known SAP-side quirks that the live forced-activation probe surfaced:

1. **Thread context classloader NPE** — Equinox's legacy servlet registration
   stores `Thread.currentThread().getContextClassLoader()` in a `Hashtable`,
   which NPEs if it's null. We defensively pin the CL to the MCP bundle's CL
   before calling `startMCPServer`.
2. **`setDestinationId` early-return bug** — first call returns without
   actually setting the destination. We bypass this entirely by:
   - having `arc1_sap_search` take `destination` as a per-call argument,
   - letting Eclipse's own destination registry get populated by
     `IAdtLogonService.ensureLoggedOn(...)` rather than by `setDestination`.

See `docs/research/` for the bytecode-level analysis behind these decisions.

## Auto-login

After kickstart, `Arc1AutoLogin.attempt(...)` schedules an Eclipse `Job` (2s
delay so ADT finishes restoring projects) that:

1. Calls `AdtProjectServiceFactory.createProjectService().getAvailableAbapProjects()`.
2. Picks the project whose destination ID matches `-Darc1.mcp.destination`,
   else the first available.
3. If `IAdtLogonService.isLoggedOn(destinationId)` is already true → done.
4. Otherwise, fetches `IDestinationData` via `project.getAdapter(IAdtCoreProject.class).getDestinationData()`.
5. Calls `IAdtLogonService.ensureLoggedOn(destData, null, monitor)`.
   - Saved credentials in Eclipse keyring → silent login.
   - No saved credentials → standard ADT password dialog pops once.

Disable via `-Darc1.mcp.autologin=false`.

## Configuration knobs

All via `-D` JVM args (typically added to `eclipse.ini` under `-vmargs`):

| Property | Default | Purpose |
|---|---|---|
| `arc1.mcp.token` | random 24-byte base64 per restart | Pinned bearer token. Set this so MCP client configs don't need updating after restart. |
| `arc1.mcp.port` | `54322` | Localhost port for the MCP server. |
| `arc1.mcp.destination` | first available ABAP project | Destination ID to auto-login. |
| `arc1.mcp.autologin` | `true` | Set to `false` to disable auto-login. |
| `arc1.mcp.kickstart` | `true` | Set to `false` to wait for SAP's future activation switch instead of forcing the server up. |

## File layout

```
com.arc1.mcp_0.1.0.jar
├── META-INF/MANIFEST.MF         OSGi headers (Require-Bundle list)
├── plugin.xml                   Extension contributions
├── com/arc1/mcp/
│   ├── Arc1McpActivator.class   Plugin singleton + log accessor
│   ├── Arc1Startup.class        IStartup: kickstart + token + autologin trigger
│   ├── Arc1AutoLogin.class      Background Job that calls ensureLoggedOn
│   ├── Arc1SapSearchTool.class  arc1_sap_search — RIS quick search
│   └── Json.class               no-dep JSON helpers
```

## Dependencies

Compile-time and runtime via OSGi `Require-Bundle`:

| Bundle | Why |
|---|---|
| `com.sap.adt.mcp.core` | `IAdtMCPTool`, `AdtMcpToolCallResultBuilder`, and (via reflection) `AdtMCPCorePlugin` |
| `com.sap.adt.ris.search` | `AdtRisQuickSearchFactory`, `IAdtRisQuickSearch` |
| `com.sap.adt.tools.core` | `IAdtObjectReference` (EMF model type) |
| `com.sap.adt.tools.core.base` | `AdtProjectServiceFactory`, `IAbapProjectService` |
| `com.sap.adt.project` | `IAdtCoreProject`, destination data accessor |
| `com.sap.adt.destinations` | `AdtLogonServiceFactory`, `IAdtLogonService` |
| `com.sap.adt.destinations.model` | `IDestinationData` |
| `org.eclipse.core.runtime` | `Plugin` base, `Platform`, `IProgressMonitor`, status |
| `org.eclipse.core.resources` | `IProject` (used by Eclipse adapter framework) |
| `org.eclipse.core.jobs` | `Job` (auto-login runs async) |
| `org.eclipse.equinox.common` | `NullProgressMonitor` |
| `org.eclipse.ui` | `IStartup` |

No third-party libraries. Build artifact is ~12 KB.
