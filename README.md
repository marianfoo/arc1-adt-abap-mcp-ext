# arc1-adt-abap-mcp-ext

**Extend SAP's hidden ADT MCP server inside Eclipse with extra ABAP repository tools — no extra process to run, just one JAR in your `dropins/`.**

SAP ships a Model Context Protocol (MCP) server inside ADT 3.58+ that exposes
ABAP development functionality to AI clients (Claude Code, GitHub Copilot,
Cursor, Claude Desktop). The server is fully implemented but **dormant** — SAP
hasn't enabled it yet. This Eclipse plugin:

1. **Wakes the server up** automatically on Eclipse startup (reflection into
   `AdtMCPCorePlugin.startMCPServer`).
2. **Contributes 7 extra read-only tools** via the supported
   `com.sap.adt.mcp.core.adtMcpTools` extension point.
3. **Auto-logs into your ABAP project** so the destination registry is
   populated by the time the LLM asks for backend data.

Result: a working local MCP endpoint at `http://localhost:54322/mcp` that
serves both SAP's own 8 tools **and** our 7, ready to be added to any
MCP-capable AI client config.

## Tools added

| Tool | What it does |
|---|---|
| `arc1_sap_search` | Quick repository search by name pattern (wraps SAP's `IAdtRisQuickSearch`). Wildcards `*` `+`. |
| `arc1_sap_repository_search` | Parameterized search with type/package/user/release filters. |
| `arc1_sap_object_info` | Metadata for one object URI: name, type, package, description, version. |
| `arc1_sap_find_definition` | Go-to-definition for an identifier inside an ABAP source. |
| `arc1_sap_list_projects` | Eclipse ABAP projects with login state. |
| `arc1_sap_system_info` | Installed software components (SAP_BASIS release etc.), servers, clients, status. |
| `arc1_sap_object_types` | Workbench object type catalog with URI templates and capabilities. |

Plus SAP's built-ins which the plugin activates: `abap_list_destinations`,
`abap_generators-list_generators`, `abap_generators-get_schema`,
`abap_generators-generate_objects`, `abap_transport-get`,
`abap_transport-create`, `abap_business_services-fetch_services`,
`abap_business_services-fetch_service_information`.

## Requirements

- **Eclipse 2025-09 (4.39)** or compatible
- **SAP ABAP Development Tools 3.58** or compatible
- **JDK 21** (bundled with Eclipse-for-ABAP)
- **macOS, Linux, or Windows** — Eclipse-side only
- An **ABAP project** in your workspace (the plugin auto-logs into the first
  one or one you specify)

## Install

1. Download the latest `com.arc1.mcp_<version>.jar` from the
   [releases page](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/releases) (or
   build it yourself — see below).

2. Drop it into your Eclipse install's `dropins/` folder. Typical path on macOS:
   ```
   ~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/dropins/
   ```
   Linux:
   ```
   ~/eclipse/java-2025-09/eclipse/dropins/
   ```
   Windows:
   ```
   C:\eclipse\java-2025-09\eclipse\dropins\
   ```

3. Edit `eclipse.ini` (sibling of the `Eclipse` executable). Under the
   `-vmargs` line, add:
   ```
   -Darc1.mcp.token=any-long-random-string-you-pick-once
   -Darc1.mcp.destination=YOUR_DESTINATION_ID
   -Darc1.mcp.port=54322
   ```
   On macOS the path is `~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/eclipse.ini`.

   <details>
   <summary><b>How to find your destination ID</b></summary>

   In ADT, a *destination ID* is the internal name of an ABAP project's
   connection. It's not the SID alone — it usually looks like
   `A4H_001_marian_en_1` (`<SID>_<client>_<user>_<language>_<seq>`).

   Three ways to find it, easiest first:

   - **Project Explorer**: the project name shown for an ABAP project in
     ADT's Project Explorer view *is* the destination ID. Just copy it.
   - **Properties**: right-click an ABAP project → *Properties* → *ABAP
     Project* — the destination ID is shown there.
   - **From the running MCP server**: once the plugin is running, call
     SAP's built-in `abap_list_destinations` tool — it returns the set of
     destination IDs the registry knows about (only populated for projects
     you're currently logged into).

   If you skip this setting entirely, the plugin auto-picks the *first*
   available ABAP project in your workspace. Set it explicitly when you
   have more than one project and want a specific one.
   </details>

4. Restart Eclipse with `-clean` (one-time, so the new bundle is registered):
   ```bash
   pkill -f Eclipse
   ~/eclipse/java-2025-09/Eclipse.app/Contents/MacOS/eclipse -clean &
   ```

5. After Eclipse opens, watch the Error Log
   (Window → Show View → Error Log) for these messages:
   ```
   ARC-1 MCP extension: MCP server started on http://localhost:54322/mcp
   ARC-1 MCP extension: Attempting auto-login: YOUR_DESTINATION_ID
   ARC-1 MCP extension: Auto-login succeeded for destination: YOUR_DESTINATION_ID
   ```

   If the auto-login fails (saved credentials missing/stale), you'll get a
   password dialog. Tick "Save password" so subsequent restarts are silent.

## Connect a client

The MCP endpoint is `http://localhost:54322/mcp` (or whatever you put in
`arc1.mcp.port`). Authorization is `Bearer <token>` where the token is what
you put in `arc1.mcp.token`.

### GitHub Copilot (in Eclipse, in VS Code)

Preferences → GitHub Copilot → Model Context Protocol (MCP) → Server
Configurations:

```json
{
  "servers": {
    "mcp-abap-server": {
      "url": "http://localhost:54322/mcp",
      "requestInit": {
        "headers": {
          "Authorization": "Bearer your-token-here"
        }
      }
    }
  }
}
```

### Claude Code (CLI)

In `~/.claude/mcp_servers.json` (or `claude code mcp add`):

```json
{
  "mcpServers": {
    "abap": {
      "url": "http://localhost:54322/mcp",
      "headers": {
        "Authorization": "Bearer your-token-here"
      }
    }
  }
}
```

### Cursor

`Settings → MCP Servers → Add Server`:
- Transport: HTTP
- URL: `http://localhost:54322/mcp`
- Headers: `Authorization: Bearer your-token-here`

### Claude Desktop

`claude_desktop_config.json` — Claude Desktop currently only supports stdio
servers. Use [mcp-remote](https://www.npmjs.com/package/mcp-remote) as a
bridge.

## Verify

There's a smoke-test script bundled with the source:

```bash
./scripts/smoke-test.sh A4H_001_marian_en_1
```

It walks through `tools/list` + a call to each of the 7 plugin tools.

## Configuration knobs

All via `-D` JVM args in `eclipse.ini`:

| Property | Default | Purpose |
|---|---|---|
| `arc1.mcp.token` | random per restart | Pinned bearer token. Set this once so MCP client configs stay valid. |
| `arc1.mcp.port` | `54322` | Port for the local MCP HTTP server. |
| `arc1.mcp.destination` | first ABAP project | Destination to auto-login. |
| `arc1.mcp.autologin` | `true` | Set `false` to skip auto-login. |
| `arc1.mcp.kickstart` | `true` | Set `false` to wait for SAP's future official activation switch instead of forcing the server up. |

## Build from source

```bash
git clone https://github.com/marianfoo/arc1-adt-abap-mcp-ext.git
cd arc1-adt-abap-mcp-ext
./build.sh                # produces com.arc1.mcp_0.1.0.jar
INSTALL=yes ./build.sh    # also copies to your local Eclipse dropins/
```

`build.sh` uses the `javac` and `jar` shipped inside Eclipse-for-ABAP
(under `~/.p2/pool/plugins/org.eclipse.justj.openjdk.hotspot.jre.full.*/jre/bin/`)
and the SAP ADT jars cached in your local p2 pool. No Maven, no Tycho, no
network access required for building.

## How it works

See [docs/architecture.md](docs/architecture.md) for the full request flow,
extension-point mechanics, and forward-compatibility notes (TL;DR: when SAP
ships their own activation switch, our reflective kickstart auto-no-ops and
the extension contribution keeps working unchanged).

Design decisions are documented in
[docs/decisions.md](docs/decisions.md) and the underlying research in
[docs/research/](docs/research/).

## FAQ

### Is this an official SAP product?
No. It's a community plugin that uses SAP's documented Eclipse extension
point (`com.sap.adt.mcp.core.adtMcpTools`) and one piece of reflection to
wake the dormant MCP server. SAP's own help page says the MCP feature is
present but **"disabled and cannot be activated"** until a future release.
This plugin enables it locally, on your machine, at your own risk.

### Will it work when SAP officially enables MCP?
Yes. The kickstart code detects an already-running server and no-ops. The
tool extension contribution remains the canonical wiring path and is
forward-compatible.

### Does this read or modify my source code?
The plugin contributes **read-only** tools. None of them write, activate,
delete, or transport anything. The 7 SAP-shipped tools that the plugin
activates *do* include mutating workflows (`abap_generators-generate_objects`,
`abap_transport-create`) — those are SAP's, not ours, and use your own SAP
credentials.

### What about credentials / security?
- The MCP server binds to `localhost` only.
- Every request requires the bearer token you set in `eclipse.ini`.
- SAP backend calls use your existing Eclipse ABAP-project authentication
  (cookies / SSO / password from Eclipse keyring).
- No telemetry, no outbound calls from this plugin.

### How do I add my own tools?
Implement `com.sap.adt.mcp.core.IAdtMCPTool` in your own bundle and add a
`<mcpTool class="..."/>` contribution to your `plugin.xml`. See
[docs/architecture.md](docs/architecture.md) — the same mechanism this plugin
uses.

### Where do I report bugs?
[GitHub Issues](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/issues).

## License

MIT — see [LICENSE](LICENSE).

## Related

- **[ARC-1](https://github.com/marianfoo/arc-1)** — a managed/BTP-deployable
  MCP server for SAP ABAP, written in TypeScript. Lives outside Eclipse;
  has admin policy ceiling, audit, multi-client governance, principal
  propagation. This plugin is the *in-Eclipse* counterpart for the single-
  developer case.
- **[SAP ADT documentation](https://help.sap.com/docs/abap-cloud/abap-development-tools-user-guide/)**
- **[Model Context Protocol spec](https://modelcontextprotocol.io/)**
