# arc1-adt-abap-mcp-ext

[![build](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/actions/workflows/build.yml/badge.svg)](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/marianfoo/arc1-adt-abap-mcp-ext?display_name=tag&sort=semver)](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/releases)
[![license](https://img.shields.io/github/license/marianfoo/arc1-adt-abap-mcp-ext)](LICENSE)

**Use Claude, GitHub Copilot, Cursor, or any MCP-capable AI client to read your ABAP system — without leaving Eclipse.**

Drop one JAR into your Eclipse `dropins/`, add 3 lines to `eclipse.ini`,
restart, point your AI client at `http://localhost:54322/mcp` — done. Your AI
can now search ABAP repositories, read source code, list transports, inspect
service bindings, and more.

---

## What this actually does

SAP shipped a **hidden** MCP server inside ABAP Development Tools 3.58+ and
hasn't turned it on. This plugin:

1. **Turns it on** (auto-starts the dormant SAP MCP server when Eclipse boots).
2. **Adds 11 extra tools** so the server can do more than SAP's 8 built-ins.
3. **Logs you in** automatically to your ABAP project so the AI can reach the
   backend.

You get a working `http://localhost:54322/mcp` endpoint that AI clients
connect to. **19 tools total** (8 from SAP + 11 from this plugin).

> [!NOTE]
> This is a community plugin, not an official SAP product. SAP's MCP support
> is officially "disabled and cannot be activated" — we activate it locally,
> on your own machine, via the documented Eclipse extension point. When SAP
> ships their own activation switch, this plugin keeps working unchanged.

---

## Quick install (5 minutes)

### What you need first

- **Eclipse 2025-09 (4.39)** with **ADT 3.58** installed
- **JDK 21** — comes bundled with Eclipse-for-ABAP, no separate install needed
- An **ABAP project** that you can log into (this plugin connects through it)

### Step 1: Download the plugin JAR

From the [latest release](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/releases/latest),
grab `com.arc1.mcp_0.3.0.jar` (45 KB).

### Step 2: Drop it into Eclipse's `dropins/` folder

| Platform | Path |
|---|---|
| macOS | `~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/dropins/` |
| Linux | `~/eclipse/java-2025-09/eclipse/dropins/` |
| Windows | `C:\eclipse\java-2025-09\eclipse\dropins\` |

> If your Eclipse is somewhere else, look for the `dropins/` folder **next to
> your Eclipse executable** (or one level up on macOS — inside the `.app`).

### Step 3: Edit `eclipse.ini`

Find `eclipse.ini` (it sits next to the Eclipse executable, or inside the
macOS app bundle at `Contents/Eclipse/eclipse.ini`). Add **3 lines at the
very bottom**, after the existing `-vmargs` block:

```ini
-Darc1.mcp.token=PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN
-Darc1.mcp.destination=YOUR_DESTINATION_ID
-Darc1.mcp.port=54322
```

Replace:
- `PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN` → anything you invent. This is a
  password between Eclipse and your AI client. Examples: `arc1-mybirthday42`,
  `7Hs2KqRpL9vWaN6T`. **Don't share it.**
- `YOUR_DESTINATION_ID` → the name of your ABAP project. See [how to find
  it](#how-to-find-your-destination-id) just below.

### Step 4: Restart Eclipse with `-clean`

```bash
# macOS
pkill -f Eclipse
~/eclipse/java-2025-09/Eclipse.app/Contents/MacOS/eclipse -clean &

# Linux
pkill -f eclipse
~/eclipse/java-2025-09/eclipse/eclipse -clean &
```

> Windows: close Eclipse normally, then run `eclipse.exe -clean` from a
> command prompt. The `-clean` flag is only needed **once** per plugin update.

### Step 5: Verify it started

In Eclipse: **Window → Show View → Error Log**. You should see:

```
ARC-1 MCP extension: MCP server started on http://localhost:54322/mcp
ARC-1 MCP extension: Auto-login succeeded for destination: YOUR_DESTINATION_ID
```

If you see those, **you're done with the plugin install**. Now connect a
client.

---

## Connect your AI client

Pick **one** based on what you use:

<details>
<summary><b>GitHub Copilot (inside Eclipse or VS Code)</b></summary>

**Preferences → GitHub Copilot → Model Context Protocol (MCP)**, paste:

```json
{
  "servers": {
    "mcp-abap-server": {
      "url": "http://localhost:54322/mcp",
      "requestInit": {
        "headers": {
          "Authorization": "Bearer PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN"
        }
      }
    }
  }
}
```

Use the **same token** you put in `eclipse.ini`. Click *Apply*.

Test it: open Copilot Chat, ask "use abap mcp server to search for ZARC1*".
</details>

<details>
<summary><b>Claude Code (CLI)</b></summary>

```bash
claude mcp add abap http://localhost:54322/mcp \
  --header "Authorization: Bearer PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN" \
  --transport http
```

Test it: `claude` and ask "list my ABAP destinations".
</details>

<details>
<summary><b>Cursor</b></summary>

**Settings → MCP Servers → Add Server**:
- Transport: **HTTP**
- URL: `http://localhost:54322/mcp`
- Headers: `Authorization: Bearer PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN`
</details>

<details>
<summary><b>Claude Desktop</b></summary>

Claude Desktop only supports stdio MCP servers natively. Bridge it with
[mcp-remote](https://www.npmjs.com/package/mcp-remote):

```json
{
  "mcpServers": {
    "abap": {
      "command": "npx",
      "args": [
        "mcp-remote", "http://localhost:54322/mcp",
        "--header", "Authorization: Bearer PICK-A-LONG-RANDOM-STRING-OF-YOUR-OWN"
      ]
    }
  }
}
```
</details>

---

## How to find your destination ID

The destination ID is the **internal name** of your ABAP project's
connection. It's not the SID; it looks like `A4H_001_marian_en_1`
(`<SID>_<client>_<user>_<lang>_<seq>`).

**Easiest**: open ADT's **Project Explorer**. The name shown for your ABAP
project **is** the destination ID. Copy it.

Other ways:
- Right-click ABAP project → *Properties → ABAP Project*
- Once the plugin is running, call `abap_list_destinations` (SAP's built-in
  tool) from your AI client and read the IDs back

If you skip `-Darc1.mcp.destination=...` entirely, the plugin auto-picks the
first available ABAP project. Set it explicitly when you have multiple
projects.

---

## Troubleshooting

### "Add Client Registration Details" dialog pops up on Eclipse startup

Your AI client's saved token doesn't match what Eclipse is serving. Cancel
the dialog. Update the `Authorization: Bearer ...` header in your client's
MCP config to match the value of `-Darc1.mcp.token=...` in `eclipse.ini`.

### Error Log shows "ARC-1 MCP extension: failed to start MCP server"

Possible causes, in likely order:
- **Port 54322 already taken** by another process. Change `-Darc1.mcp.port`
  to something else (e.g. `54330`) in `eclipse.ini`, and update the URL in
  your AI client config.
- **Multiple plugin versions** in `dropins/`. Delete all `com.arc1.mcp_*.jar`
  files except the newest one, restart Eclipse with `-clean`.
- **Eclipse / ADT version mismatch**. This plugin is built for ADT 3.58.
  Check `Help → Installation Details` for your ADT version.

### `arc1_sap_search` returns "Unable to initialize the ADT Discovery"

You're not logged into the ABAP project yet. In **Project Explorer**,
expand your ABAP project — Eclipse will prompt for the password. Tick "Save
password" so future restarts log in silently.

### Tool calls return HTTP 401 "Authentication failed: Invalid token"

The token your AI client sent doesn't match `-Darc1.mcp.token`. Two cases:
1. You set the token in `eclipse.ini` but forgot to update the client config.
2. You changed the token in `eclipse.ini` but the client cached the old one.

Restart your AI client after updating its config.

### Tool calls return HTTP 401 "Missing or invalid Authorization header"

Your client isn't sending the `Authorization: Bearer ...` header at all.
Re-check the client config — the header has to be named exactly
`Authorization` and the value starts with `Bearer ` (with a space).

### Nothing in Error Log, no `~/.config/arc1/mcp-token.txt` file

The plugin didn't load at all. Check **Help → About Eclipse → Installation
Details → Plug-ins** tab — search for `com.arc1.mcp`. If it's missing:
- Confirm the JAR is in `dropins/` (not in a subfolder)
- Restart Eclipse with `-clean` (not just normal restart)

---

## What you get — the 19 tools

### Plugin tools (11)

**Search + metadata** (v0.1.0)

| Tool | What it does |
|---|---|
| `arc1_sap_search` | Quick search by name pattern. Wildcards `*` `+`. |
| `arc1_sap_repository_search` | Search with filters: object types, packages, users, release states. |
| `arc1_sap_object_info` | Metadata for one URI: name, type, package, description. |
| `arc1_sap_find_definition` | Go-to-definition for an identifier in source. |
| `arc1_sap_list_projects` | Workspace ABAP projects with login state. |
| `arc1_sap_system_info` | Installed software components (SAP_BASIS release etc.), servers, clients. |
| `arc1_sap_object_types` | Workbench object type catalog with URI templates and capabilities. |

**Source reading + HTTP** (v0.2.0)

| Tool | What it does |
|---|---|
| `arc1_sap_read_source` | Fetch ABAP source code for an object. CLAS includes supported. |
| `arc1_sap_http_get` | Authenticated GET to any `/sap/bc/adt/...` endpoint (escape hatch). |

**Transports + POST** (v0.3.0)

| Tool | What it does |
|---|---|
| `arc1_sap_list_transports` | List transports with username / status / type filters. |
| `arc1_sap_http_post` | Authenticated POST to any `/sap/bc/adt/...` endpoint (escape hatch). |

### SAP built-ins this plugin activates (8)

`abap_list_destinations`, `abap_generators-list_generators`,
`abap_generators-get_schema`, `abap_generators-generate_objects`,
`abap_transport-get`, `abap_transport-create`,
`abap_business_services-fetch_services`,
`abap_business_services-fetch_service_information`.

---

## Verify everything works (full smoke test)

If you have the source checked out:

```bash
git clone https://github.com/marianfoo/arc1-adt-abap-mcp-ext.git
cd arc1-adt-abap-mcp-ext
./scripts/smoke-test.sh A4H_001_marian_en_1   # use your destination ID
```

This exercises all 11 plugin tools through the MCP protocol end-to-end and
prints the responses.

---

## Configuration reference

All set via JVM `-D` flags in `eclipse.ini`:

| Property | Default | Purpose |
|---|---|---|
| `arc1.mcp.token` | random per restart | Bearer token. Pin it once so client configs stay valid. |
| `arc1.mcp.port` | `54322` | Port for the local MCP server. |
| `arc1.mcp.destination` | first ABAP project | Which destination to auto-login. |
| `arc1.mcp.autologin` | `true` | Set `false` to skip auto-login. |
| `arc1.mcp.kickstart` | `true` | Set `false` to wait for SAP's future activation switch. |

---

## Build from source

```bash
git clone https://github.com/marianfoo/arc1-adt-abap-mcp-ext.git
cd arc1-adt-abap-mcp-ext
./build.sh                # produces com.arc1.mcp_<version>.jar
INSTALL=yes ./build.sh    # also copies to your local Eclipse dropins/
```

No Maven, no Tycho. `build.sh` uses the JDK and SAP ADT JARs from your local
Eclipse install (`~/.p2/pool/plugins/`). Build time: ~3 seconds.

---

## How it works (deep dive)

- **Architecture overview**: [docs/architecture.md](docs/architecture.md)
- **Why each design decision**: [docs/decisions.md](docs/decisions.md)
- **Underlying research** (SAP bytecode analysis, ADT API maps): [docs/research/](docs/research/)
- **Implementation plans** (one per release): [docs/plans/](docs/plans/)

TL;DR: SAP designed the MCP server to be extended via the standard Eclipse
extension point `com.sap.adt.mcp.core.adtMcpTools`. Our plugin contributes
11 `<mcpTool class="..."/>` entries. We also reflectively call
`AdtMCPCorePlugin.startMCPServer(port, token)` to wake the dormant server —
the only "unsupported" part of the plugin, and one that auto-no-ops once
SAP ships their own activation switch.

---

## FAQ

### Is this an official SAP product?
No. It's a community plugin. See the note at the top.

### Does this read or modify my source code?
The 11 tools this plugin adds are **read-only**. The 8 SAP-shipped tools
that the plugin activates *do* include mutating workflows
(`abap_generators-generate_objects`, `abap_transport-create`). Those use
your normal SAP authorizations — same as if you used the equivalent ADT UI
action.

### What about credentials / security?
- The MCP server binds to `localhost` only (not reachable from the network).
- Every request requires the bearer token you set in `eclipse.ini`.
- SAP backend calls use your existing ABAP-project authentication (cookies
  / SSO / saved password).
- No telemetry, no outbound HTTP from this plugin.

### Will this break when SAP ships official MCP activation?
No. The kickstart code detects an already-running server and skips. The
`<mcpTool>` contributions stay valid — same extension point either way.

### How do I add my own tool?
Implement `com.sap.adt.mcp.core.IAdtMCPTool` in your own bundle and contribute
via `plugin.xml`. See [CONTRIBUTING.md](CONTRIBUTING.md) for the recipe.

### What's the relationship to ARC-1?
**[ARC-1](https://github.com/marianfoo/arc-1)** is the standalone TypeScript
MCP server for SAP ABAP — runs outside Eclipse, has admin policy ceiling,
audit logging, multi-client governance, BTP-native deployment. Use ARC-1
when you want a centralized managed service. Use this plugin when you want
"one developer, inside Eclipse, zero extra processes".

### Where do I report bugs?
[GitHub Issues](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/issues).
Include your Eclipse + ADT versions, plugin version (the JAR filename), and
the relevant excerpt from the Error Log (filter by `com.arc1.mcp`).

---

## License

[MIT](LICENSE) — use it, modify it, ship it.
