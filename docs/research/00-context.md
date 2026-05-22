# Research context

This plugin's design draws from three deep-dive analyses produced during the
ARC-1 project (May 2026). The originals live in the ARC-1 repository at
`docs/research/`; this file is a pointer + summary so the plugin's docs are
self-contained.

## Source documents

| Document | What it answers |
|---|---|
| `adt-eclipse-mcp-deep-dive-2026-05-22.md` | What does ADT 3.58 ship, bytecode-level? Which classes, which extension point, which security filters? |
| `adt-mcp-live-s4-api-probes-2026-05-22.md` | Which ADT REST endpoints are actually live on a real S/4 system? |
| `adt-mcp-arc1-implications-2026-05-22.md` | What should ARC-1 do with these findings? |
| `adt-mcp-forced-activation-2026-05-22.md` | Can the dormant MCP server be forced to start? (Yes, with two specific tricks.) |

## Key facts these documents established

1. **SAP ships a real but dormant local MCP server** in `com.sap.adt.mcp.core_3.58.0.jar`.
   - HTTP Streamable transport at `/mcp`
   - Localhost-bind + bearer token + Host-header validation
   - Java MCP SDK under the hood
   - 8 static tools registered via Eclipse extension point
2. **The activation path is `AdtMCPCorePlugin.startMCPServer(int, String)`** — a
   public method with no caller.
3. **Two pitfalls in the dormant code that affect anyone forcing it:**
   a. Equinox legacy servlet registration NPEs on null context classloader.
   b. `ADTMCPServer.setDestinationId(String)` early-returns on first call.
4. **The extension point `com.sap.adt.mcp.core.adtMcpTools` is fully open.**
   No bundle signing requirements, no friend-bundle restriction. Anyone can
   contribute an `IAdtMCPTool`.
5. **`AdtRisQuickSearchFactory` is the cleanest backend-search API** — fully
   public package, takes destination ID + query, returns
   `List<IAdtObjectReference>` with name/type/uri/package/description.
6. **Destination registry is populated by Eclipse-level login, not by
   `setDestination(...)`.** `IAdtLogonService.ensureLoggedOn(destData, ...)` is
   the canonical trigger.

## Tools SAP exposes today (built-in 8)

| Bundle | Tool |
|---|---|
| `com.sap.adt.mcp.core` | `abap_list_destinations` |
| `com.sap.adt.objectgenerator` | `abap_generators-list_generators` |
| `com.sap.adt.objectgenerator` | `abap_generators-get_schema` |
| `com.sap.adt.objectgenerator` | `abap_generators-generate_objects` |
| `com.sap.adt.tm.model` | `abap_transport-get` |
| `com.sap.adt.tm.model` | `abap_transport-create` |
| `com.sap.adt.cds.servicebinding` | `abap_business_services-fetch_services` |
| `com.sap.adt.cds.servicebinding` | `abap_business_services-fetch_service_information` |

SAP's existing surface is **mutating workflows** (generators, transports). Plus
service-binding inspection. There are large read-side gaps that this plugin
fills:

- repository search (we ship `arc1_sap_search` for this)
- source-code reading
- where-used / find references
- object metadata / structure
- package contents listing
- syntax check / ATC results
- unit test execution
- find definition / navigation

See `docs/plans/` for the concrete tool roadmap.
