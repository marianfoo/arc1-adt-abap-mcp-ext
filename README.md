# arc1-adt-abap-mcp-ext

[![build](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/actions/workflows/build.yml/badge.svg)](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/marianfoo/arc1-adt-abap-mcp-ext?display_name=tag&sort=semver)](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/releases)
[![license](https://img.shields.io/github/license/marianfoo/arc1-adt-abap-mcp-ext)](LICENSE)

**Use Claude, GitHub Copilot, Cursor, or any MCP-capable AI client to read your ABAP system — without leaving Eclipse.**

Drop one JAR into your Eclipse `dropins/`, turn on SAP's built-in MCP server,
restart, point your AI client at `http://localhost:2234/mcp` — done. Your AI
can now search ABAP repositories, read source code, list transports, inspect
service bindings, and more.

> [!IMPORTANT]
> **Requires ABAP Development Tools (ADT) 3.60 or newer.** Version 0.5.0 builds
> on SAP's supported MCP server, which first shipped an activation surface in
> ADT 3.60. For ADT 3.58 / 3.59 use plugin version ≤ 0.3.x instead (those
> reflectively woke the then-dormant server).

---

## What this actually does

As of **ADT 3.60**, SAP ships a *supported* MCP server inside Eclipse-for-ABAP.
It's **off by default** and, on its own, only exposes SAP's own MCP tools. This
plugin:

1. **Adds 18 read-only ABAP tools** via SAP's documented Eclipse extension
   point `com.sap.adt.mcp.core.adtMcpTools` — search, read source, object
   metadata, where-used, package/object structure, syntax check, unit tests,
   ATC, transports, and authenticated HTTP escape hatches.
2. **Logs you in** automatically to your ABAP project (optional) so the AI can
   reach the backend on the first call.

You enable SAP's server once (a preference toggle + one `eclipse.ini` line);
this plugin's tools then register on it automatically every time it starts.
**No reflection, no internal hacks** — just a standard tool contribution.

> [!NOTE]
> This is a community plugin, not an official SAP product. It uses only the
> documented extension point. SAP owns the server lifecycle (start/stop, port,
> token); this plugin only contributes tools.

---

## Quick install (5 minutes)

### What you need first

- **Eclipse for ABAP with ADT 3.60+** (check via *Help → Installation Details*)
- **JDK 21** — comes bundled with Eclipse-for-ABAP, no separate install needed
- An **ABAP project** that you can log into (this plugin connects through it)

### Step 1: Download the plugin JAR

From the [latest release](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/releases/latest),
grab `com.arc1.mcp_0.5.0.jar`.

### Step 2: Drop it into Eclipse's `dropins/` folder

| Platform | Path |
|---|---|
| macOS | `~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/dropins/` |
| Linux | `~/eclipse/java-2025-09/eclipse/dropins/` |
| Windows | `C:\eclipse\java-2025-09\eclipse\dropins\` |

> If your Eclipse is somewhere else, look for the `dropins/` folder **next to
> your Eclipse executable** (or one level up on macOS — inside the `.app`).

### Step 3: Add one line to `eclipse.ini`

Find `eclipse.ini` (next to the Eclipse executable, or inside the macOS app
bundle at `Contents/Eclipse/eclipse.ini`). Add this line at the **very bottom**,
after the existing `-vmargs` block:

```ini
-DadtMcpServerPrefEnabled=true
```

This is SAP's flag that makes the MCP server **auto-start on every boot**.
Without it, the server only runs while you keep the preference applied in the
current session.

*(Optional)* if you want this plugin to auto-log-into a specific ABAP project,
also add:

```ini
-Darc1.mcp.destination=YOUR_DESTINATION_ID
```

See [how to find your destination ID](#how-to-find-your-destination-id) below.

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

### Step 5: Turn on the MCP server (SAP preference page)

In Eclipse: **Preferences → ABAP Development → MCP Server**.

1. Tick **Enable ADT MCP Server**.
2. Leave **Token** blank and click **Generate** (or type your own long string).
3. **Port** defaults to `2234` — change it only if that port is taken.
4. Click **Apply**. You should see *"ADT MCP Server started successfully."*

**Copy the Token value** — you'll paste it into your AI client in the next
section. (SAP shows it right there on the page; this plugin does not write a
token file.)

### Step 6: Verify it started

**Window → Show View → Error Log**. You should see (from SAP and from us):

```
ADT MCP Server started successfully.
ARC-1 MCP extension: tool contributions registered. The ADT MCP server is started by SAP ...
ARC-1 MCP extension: Auto-login succeeded for destination: YOUR_DESTINATION_ID
```

If you see those, **you're done with the plugin install**. Now connect a client.

---

## Connect your AI client

Use the **port and token from the preference page** (default port `2234`).
Pick **one** client below:

<details>
<summary><b>GitHub Copilot (inside Eclipse or VS Code)</b></summary>

**Preferences → GitHub Copilot → Model Context Protocol (MCP)**, paste:

```json
{
  "servers": {
    "mcp-abap-server": {
      "url": "http://localhost:2234/mcp",
      "requestInit": {
        "headers": {
          "Authorization": "Bearer PASTE-TOKEN-FROM-PREFERENCE-PAGE"
        }
      }
    }
  }
}
```

Click *Apply*. Test it: open Copilot Chat, ask "use abap mcp server to search for ZARC1*".

**Auto-approve the read-only tools (optional).** Recent GitHub Copilot for Eclipse
can pre-approve MCP tool calls so you aren't prompted on every call. In
**Preferences → GitHub Copilot → (MCP settings)**:

- **MCP Server and Tool Approval** *(recommended)* — expand the `abap-mcp` server
  and tick the individual `arc1_sap_*` tools you want pre-approved. **Every
  `arc1_sap_*` tool this plugin adds is read-only**, so it's safe to auto-approve
  here. This works regardless of whether a tool advertises an annotation.
- **Trust MCP tool annotations** — auto-approves tools that advertise a read-only
  hint, without confirmation. Convenient, but it only covers tools that set the
  hint, so the per-tool list above is the reliable way to cover the `arc1_sap_*`
  tools.
- **Global Auto Approve → "Auto approve all tool calls"** — approves *everything*
  (terminal commands, file edits, **all** MCP tools) with no confirmation. SAP's
  own MCP surface alongside these read tools includes mutating ones
  (`abap_transport-create`, `abap_generators-generate_objects`,
  `abap_activate_objects`), so leave this **off** and prefer per-tool approval.

> Auto-approving read-only tools still lets the AI read your ABAP and send it to
> the model on its own initiative — the same data flow you opt into by using the
> server at all, just without the per-call prompt. It does not enable any writes.
</details>

<details>
<summary><b>Claude Code (CLI)</b></summary>

```bash
claude mcp add abap http://localhost:2234/mcp \
  --header "Authorization: Bearer PASTE-TOKEN-FROM-PREFERENCE-PAGE" \
  --transport http
```

Test it: `claude` and ask "list my ABAP destinations".
</details>

<details>
<summary><b>Cursor</b></summary>

**Settings → MCP Servers → Add Server**:
- Transport: **HTTP**
- URL: `http://localhost:2234/mcp`
- Headers: `Authorization: Bearer PASTE-TOKEN-FROM-PREFERENCE-PAGE`
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
        "mcp-remote", "http://localhost:2234/mcp",
        "--header", "Authorization: Bearer PASTE-TOKEN-FROM-PREFERENCE-PAGE"
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
- Once the server is running, call `abap_list_destinations` (SAP's built-in
  tool) from your AI client and read the IDs back

If you skip `-Darc1.mcp.destination=...` entirely, the plugin auto-picks the
first available ABAP project. Set it explicitly when you have multiple
projects.

---

## Troubleshooting

### The MCP server never starts

- Make sure you ticked **Enable ADT MCP Server** under *Preferences → ABAP
  Development → MCP Server* and clicked **Apply**.
- For auto-start on boot, confirm `-DadtMcpServerPrefEnabled=true` is in
  `eclipse.ini` (under `-vmargs`) and you restarted. Without that flag, the
  server does not come back after an Eclipse restart.
- **Port already taken** by another process: change the port on the preference
  page (e.g. `2235`) and update the URL in your AI client config.

### `arc1_sap_*` tools don't show up in `tools/list` (only SAP's tools do)

The plugin bundle didn't load. Check **Help → About Eclipse → Installation
Details → Plug-ins** for `com.arc1.mcp`. If it's missing:
- Confirm the JAR is in `dropins/` (not a subfolder), then restart with `-clean`.
- Confirm your ADT version is **3.60+** — this plugin refuses to load on older
  ADT (the OSGi requirement is `[3.60.0,4.0.0)`).

### Tool calls return HTTP 401

The token your AI client sent doesn't match the one on the preference page.
Open *Preferences → ABAP Development → MCP Server*, copy the **Token** value,
and update the `Authorization: Bearer ...` header in your client config.
Restart your AI client after changing its config. If the header is missing
entirely, your client isn't sending `Authorization` at all — it must be named
exactly `Authorization` with a value starting `Bearer ` (with a space).

### `arc1_sap_search` returns "Unable to initialize the ADT Discovery"

You're not logged into the ABAP project yet. In **Project Explorer**,
expand your ABAP project — Eclipse will prompt for the password. Tick "Save
password" so future restarts log in silently. (Or set
`-Darc1.mcp.destination=...` so this plugin pre-logs-in for you.)

---

## What you get — the tools

### Plugin tools (18, all read-only)

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

**Navigation + structure + quality** (v0.5.0)

| Tool | What it does |
|---|---|
| `arc1_sap_where_used` | Where-used / find references for an object (impact analysis). |
| `arc1_sap_package_contents` | List the objects in a package (top-down exploration). |
| `arc1_sap_object_structure` | Outline of an object: methods, attributes, events, includes. |
| `arc1_sap_list_inactive` | List inactive (un-activated) objects on the system. |
| `arc1_sap_check_syntax` | ABAP syntax check (no activation); can check proposed source transiently. |
| `arc1_sap_run_unit_tests` | Run an object's ABAP Unit tests (executes them server-side). |
| `arc1_sap_atc_check` | Run ABAP Test Cockpit static analysis and return findings. |

### SAP's own MCP tools (alongside these)

When the server is running, SAP also contributes its own tools (the exact set
depends on which ADT features are installed). On a typical S/4 ADT install that
includes:

`abap_list_destinations`, `abap_generators-list_generators`,
`abap_generators-get_schema`, `abap_generators-generate_objects`,
`abap_transport-get`, `abap_transport-create`,
`abap_business_services-fetch_services`,
`abap_business_services-fetch_service_information`.

These are SAP's, not this plugin's — they ship and register on their own.

---

## Verify everything works (full smoke test)

If you have the source checked out, pass the server URL + token from the
preference page via environment variables:

```bash
git clone https://github.com/marianfoo/arc1-adt-abap-mcp-ext.git
cd arc1-adt-abap-mcp-ext

export ARC1_MCP_URL=http://localhost:2234/mcp     # port from the preference page
export ARC1_MCP_TOKEN=PASTE-TOKEN-FROM-PREFERENCE-PAGE
./scripts/smoke-test.sh A4H_001_marian_en_1       # use your destination ID
```

This exercises all 18 plugin tools through the MCP protocol end-to-end and
prints the responses.

---

## Configuration reference

The MCP server's **port, token, and on/off** live in SAP's preference page
(*Preferences → ABAP Development → MCP Server*) and the `eclipse.ini` flag
`-DadtMcpServerPrefEnabled=true`. This plugin adds only two optional knobs,
set via JVM `-D` flags in `eclipse.ini`:

| Property | Default | Purpose |
|---|---|---|
| `arc1.mcp.destination` | first ABAP project | Which destination to auto-login. |
| `arc1.mcp.autologin` | `true` | Set `false` to skip auto-login. |

---

## Build from source

```bash
git clone https://github.com/marianfoo/arc1-adt-abap-mcp-ext.git
cd arc1-adt-abap-mcp-ext
./build.sh                # produces com.arc1.mcp_<version>.jar
INSTALL=yes ./build.sh    # also copies to your local Eclipse dropins/
```

No Maven, no Tycho. `build.sh` uses the JDK and SAP ADT JARs (3.60+) from your
local Eclipse install (`~/.p2/pool/plugins/`). Build time: ~3 seconds.

---

## How it works (deep dive)

- **Architecture overview**: [docs/architecture.md](docs/architecture.md)
- **Why each design decision**: [docs/decisions.md](docs/decisions.md)
- **Underlying research** (SAP bytecode analysis, ADT API maps): [docs/research/](docs/research/)
- **Implementation plans** (one per release): [docs/plans/](docs/plans/)

TL;DR: SAP designed the MCP server to be extended via the standard Eclipse
extension point `com.sap.adt.mcp.core.adtMcpTools`. This plugin contributes
18 `<mcpTool class="..."/>` entries, which SAP's own `ToolRegistrationService`
picks up whenever the server starts. SAP owns activation (the preference page
+ `-DadtMcpServerPrefEnabled=true`); this plugin never touches the server
lifecycle.

---

## FAQ

### Is this an official SAP product?
No. It's a community plugin. See the note at the top.

### Why do I need ADT 3.60?
ADT 3.60 is the first release where SAP shipped a way to turn the MCP server on
(a preference page + startup flag) and a stable `startMCPServer` signature.
Earlier releases (3.58/3.59) had the server but no activation surface — plugin
versions ≤ 0.3.x handled those by reflectively waking it. 0.4.0 drops that and
relies on SAP's supported activation, so it's 3.60+ only.

### Does this read or modify my source code?
The 18 tools this plugin adds are **read-only with respect to your repository** —
none create, change, or delete source. Two *execute* code on the backend without
changing it: `arc1_sap_run_unit_tests` runs an object's ABAP Unit tests and
`arc1_sap_atc_check` runs ATC static analysis (the same as the corresponding ADT
menu actions). `arc1_sap_check_syntax` can validate *proposed* source transiently
without saving it. SAP's own MCP tools may include genuinely mutating workflows
(`abap_generators-generate_objects`, `abap_transport-create`). All of these use
your normal SAP authorizations — same as the equivalent ADT UI action.

### What about credentials / security?
- The MCP server binds to `localhost` only (not reachable from the network).
- Every request requires the bearer token set on SAP's preference page.
- SAP backend calls use your existing ABAP-project authentication (cookies
  / SSO / saved password).
- No telemetry, no outbound HTTP from this plugin.

### How do I add my own tool?
Implement `com.sap.adt.mcp.core.IAdtMCPTool` in your own bundle and contribute
via `plugin.xml`. See [CONTRIBUTING.md](CONTRIBUTING.md) for the recipe.

### What's the relationship to ARC-1?
**[ARC-1](https://github.com/marianfoo/arc-1)** is the standalone TypeScript
MCP server for SAP ABAP — runs outside Eclipse, has admin policy ceiling,
audit logging, multi-client governance, BTP-native deployment. Use ARC-1
when you want a centralized managed service. Use this plugin when you want
"one developer, inside Eclipse, zero extra processes".

### What about `adt-ls`? Can this plugin use it?
**[`adt-ls`](https://github.com/marianfoo/adt-ls)** is a TypeScript/Node SDK
that spawns and drives SAP's *headless* `adt-ls` language server over LSP + MCP.
It's the out-of-Eclipse cousin of this plugin: a programmatic API for
repository, source, **activate**, **unit tests**, **ATC**, syntax check, and
transports — great for scripting and CI.

This plugin does **not** depend on it, by design. We already run *inside*
Eclipse-for-ABAP, which is the full ADT — pulling in `adt-ls` would spawn a
second, headless ADT (Java → Node → another JVM) to reach a backend we already
reach in-process, and would add a third-party runtime plus an extra process
(both ruled out — see [decision D10](docs/decisions.md#d10-adt-ls-is-a-sibling-project-not-a-dependency)).

Pick by where you run:

| You want… | Use |
|---|---|
| MCP tools **inside Eclipse**, zero extra processes | **this plugin** |
| **Headless / CI / scripting** ABAP automation from Node | [`adt-ls`](https://github.com/marianfoo/adt-ls) |
| **Centralized, audited, multi-user** MCP (BTP) | [ARC-1](https://github.com/marianfoo/arc-1) |

### Where do I report bugs?
[GitHub Issues](https://github.com/marianfoo/arc1-adt-abap-mcp-ext/issues).
Include your Eclipse + ADT versions, plugin version (the JAR filename), and
the relevant excerpt from the Error Log (filter by `com.arc1.mcp`).

---

## License

[MIT](LICENSE) — use it, modify it, ship it.
