# CLAUDE.md

Guidance for AI assistants (and humans) working on `com.arc1.mcp` (the
Eclipse plugin in this repo). Read this first.

## Project goal

**Extend SAP's Model Context Protocol (MCP) server inside Eclipse-for-ABAP**
with extra read-only tools, so AI clients (Claude Code, GitHub Copilot,
Cursor, Claude Desktop) can read your ABAP system without any extra process.

Requires **ADT 3.60+**, where SAP ships the MCP server as a supported feature.
The user installs one JAR, turns SAP's server on once (the *ABAP Development →
MCP Server* preference + `-DadtMcpServerPrefEnabled=true` in `eclipse.ini`),
and restarts. From then on, this plugin's tools register on SAP's authenticated
MCP endpoint (default `http://localhost:2234/mcp`) whenever the server runs.

### Why this exists

SAP ships the MCP server inside ADT. In 3.58/3.59 it was dormant with no
activation surface; **as of ADT 3.60 it is a supported feature** with its own
preference page (*ABAP Development → MCP Server*) and a startup flag
(`-DadtMcpServerPrefEnabled=true`) — but it's **off by default** and, on its
own, only carries SAP's own tools. SAP exposes a public Eclipse extension point
`com.sap.adt.mcp.core.adtMcpTools` for contributing extra tools. This plugin
does exactly one thing:

1. **Contribute extra tools** via that documented extension point. SAP's own
   `ToolRegistrationService` registers them whenever the server starts.

(It also optionally pre-warms the ABAP project logon so tools work on the first
call — public API, not a hack.)

Earlier versions (≤ 0.3.x) also reflectively kickstarted the dormant 3.58
server. ADT 3.60 made that both unnecessary (SAP ships activation) and broken
(the `startMCPServer` signature changed to take a `FileSystemMode`), so v0.4.0
removed it — the plugin no longer touches the server lifecycle. See
`docs/decisions.md` D9 (supersedes D3).

### Non-goals

This plugin is intentionally **Eclipse-bound**. It is NOT trying to be:
- A standalone MCP server (that's [ARC-1](https://github.com/marianfoo/arc-1),
  in TypeScript, runs anywhere).
- A managed multi-user service with admin policy ceilings, audit, BTP
  deployment (also ARC-1 territory).
- A write/activate platform — mutating tools belong in SAP's own MCP
  surface (`abap_transport-create`, `abap_generators-generate_objects`),
  which SAP ships and registers itself.

If a task is "centralized management", "BTP", or "non-Eclipse" — point the
user at ARC-1 instead.

## Architecture in one screen

```
Eclipse workbench startup
  │
  ├─ OSGi resolves bundles (incl. com.arc1.mcp from dropins/)
  │     com.arc1.mcp requires com.sap.adt.* [3.60.0,4.0.0)
  │
  ├─ SAP's AdtMcpUIStartupHandler.earlyStartup() (via org.eclipse.ui.startup)
  │   └─ starts the server IF -DadtMcpServerPrefEnabled=true AND pref enabled
  │        → AdtMCPCorePlugin.startMCPServer(port, token, FileSystemMode.SFS)
  │
  ├─ Arc1Startup.earlyStartup()  (via org.eclipse.ui.startup)
  │   ├─ log guidance (how to enable the server)
  │   └─ schedule Arc1AutoLogin Job (2s delay), unless -Darc1.mcp.autologin=false
  │
  └─ On server start, SAP's ToolRegistrationService discovers all
       <mcpTool class="..."/> extension contributions (SAP's + ours) and
       addTool()s them on the McpSyncServer (Java MCP SDK).
```

Request flow when a client calls our tool:

```
Client → POST /mcp (Streamable HTTP) with Bearer token
   ↓ DNSRebindingProtectionFilter (Host: localhost?)
   ↓ TokenAuthenticationFilter
   ↓ Java MCP SDK servlet
   ↓ ToolRegistrationService routes by name
   ↓ Arc1Sap<X>Tool.execute(jsonInput)
   ↓ For HTTP-backed tools: AdtHttp.get/post(destinationId, uri, ...)
   ↓ Eclipse's IStatelessSystemSession handles auth/cookies/CSRF
   ↓ SAP ABAP backend
```

## Repo layout

```
arc1-mcp-ext/
├── build.sh                     javac + jar, ~50 lines, no Maven
├── plugin.xml                   extension contributions
├── META-INF/MANIFEST.MF         OSGi bundle headers
├── src/com/arc1/mcp/
│   ├── Arc1McpActivator         OSGi Plugin singleton + log
│   ├── Arc1Startup              IStartup; guidance log + autologin trigger
│   ├── Arc1AutoLogin            Background Job, ensureLoggedOn
│   ├── AdtHttp                  HTTP helper (GET + POST, 256KB cap)
│   ├── Json                     no-dep JSON helpers
│   └── Arc1Sap*Tool             one class per MCP tool
├── scripts/
│   ├── smoke-test.sh            end-to-end test of every tool
│   └── finalize-readme.sh       swap repo URL placeholders
├── docs/
│   ├── architecture.md          deeper than this file
│   ├── decisions.md             non-obvious design choices (D1–D9)
│   ├── plans/                   01–06; one per release
│   ├── research/                bytecode analysis pointers
│   └── release-readiness-review.md
├── .github/workflows/
│   ├── build.yml                structural validation per push
│   └── release.yml              creates GitHub Release on tag push
└── README.md                    end-user docs
```

## Conventions to follow

### Tool naming
- Tool name: `arc1_sap_<verb>` snake_case. SAP's validator regex is
  `[A-Za-z0-9_-]`. Anything else gets silently dropped at registration.
- Class name: `Arc1Sap<Verb>Tool` (CamelCase).
- File: one tool per class, named after the class.

### Tool implementation template

Every tool follows the same shape — copy an existing one as the starting
point (`Arc1SapObjectInfoTool` is a good template):

```java
public class Arc1SapXxxTool implements IAdtMCPTool {
    public String getName()         { return "arc1_sap_xxx"; }
    public String getDescription()  { return "..."; }
    public String getInputSchema()  { return "{...JSON schema as String...}"; }
    public IAdtMcpToolCallResult execute(String jsonInput) {
        try {
            // 1. Read fields with Json.readString/readInt/readBoolean/readStringArray
            // 2. Validate required fields → return error("Missing required field: x") on miss
            // 3. Call SAP API or AdtHttp.get/post
            // 4. Build JSON output with Json.str(...) and StringBuilder
            // 5. Return AdtMcpToolCallResultBuilder
        } catch (Throwable t) {
            return error("arc1_sap_xxx failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
```

**MUST**:
- Wrap `execute()` in `try { } catch (Throwable t) { return error(...); }` —
  MCP tool calls must never throw to the SDK.
- Bound output size. Search tools cap `maxResults`. HTTP tools cap body at
  `AdtHttp.MAX_BODY_BYTES` (256 KB) and return `truncated: true`.
- Validate required fields explicitly — return `error("Missing required
  field: X")` with a clear name.

**MUST NOT**:
- Add third-party dependencies (no Jackson, no Gson, no slf4j). Use `Json`.
- Use packages named `*.internal.*`, or reflect into SAP internals at all. As
  of v0.4.0 the plugin touches zero SAP-internal API — keep it that way; use
  the documented extension point and public APIs only.
- Forget to update `plugin.xml` with the new `<mcpTool class="..."/>` line.
  A tool not in `plugin.xml` is invisible to the extension registry.
- Forget to update `scripts/smoke-test.sh` to cover the new tool.

### Adding a new tool — checklist

1. Create `src/com/arc1/mcp/Arc1SapXxxTool.java` (use template above).
2. Add `<mcpTool class="com.arc1.mcp.Arc1SapXxxTool"/>` to `plugin.xml`.
3. If the tool needs an OSGi bundle we don't already depend on:
   - Add to `Require-Bundle:` in `META-INF/MANIFEST.MF`
   - Add to `BUNDLES` array in `build.sh`
4. Add a `TEST N` block to `scripts/smoke-test.sh`.
5. `./build.sh` — verify compile.
6. `INSTALL=yes ./build.sh` — drop into local Eclipse.
7. `-clean` restart Eclipse.
8. Run smoke test.

### Build + release

```bash
# bump Bundle-Version in META-INF/MANIFEST.MF (semver)
# add a new [X.Y.Z] section to CHANGELOG.md
git add -A && git commit -m "feat(vX.Y.Z): ..."
git push origin main
git tag -a vX.Y.Z -m "vX.Y.Z — ..."
git push origin vX.Y.Z
# release workflow creates the GitHub Release; then:
gh release upload vX.Y.Z com.arc1.mcp_X.Y.Z.jar --repo marianfoo/arc1-adt-abap-mcp-ext
```

The release workflow does NOT build the JAR in CI — SAP ADT JARs aren't
redistributable, so the CI runner can't compile against them. We build
locally and upload manually. See `docs/plans/03-publishing.md` for the
"option c" path if you ever want CI to compile (stub class files).

## CI / git rules

- **Commits**: use `13335743+marianfoo@users.noreply.github.com` for
  author email (avoid leaking real email).
- **Branches**: `main` is protected by CI but accepts direct push for
  solo development. Use PRs once there are contributors.
- **Tags**: `vX.Y.Z` matching `Bundle-Version`. The release workflow
  pattern-matches `v*.*.*`.
- **The build CI** (`build.yml`) does structural validation only:
  - `javac` parse check
  - `xmllint` plugin.xml well-formedness + class-ref consistency
  - MANIFEST.MF required headers
  - `bash -n` on shell scripts
  It does NOT attempt to compile against SAP JARs.

## Forward-compat principles

These are not optional — break them and the plugin will eventually break
silently:

1. **Never start, stop, or reconfigure the MCP server.** As of 3.60 SAP owns
   the server lifecycle (preference page + `AdtMcpUIStartupHandler`). This
   plugin only contributes tools and pre-warms logon. Re-introducing a
   kickstart would fight SAP over the port/token — `ADTMCPServer.start()`
   stops and re-binds if asked to start on a different port than the one
   already running.
2. **No reflection into SAP internals.** v0.4.0 removed the last reflective
   call. Don't reintroduce `*.internal.*` access (or use of the now
   `x-friends`-scoped `com.sap.adt.mcp.core` package beyond the compile-time
   `IAdtMCPTool` tool contract) without strong justification.
3. **`Require-Bundle: com.sap.adt.*;bundle-version="[3.60.0,4.0.0)"`** — keep
   the floor at the supported ADT version (3.60) and scoped to the major.
   When ADT 4.x ships, internal packages will likely move; we want OSGi to
   refuse to load us rather than crash at runtime.

## Where each design choice lives

When in doubt, check `docs/decisions.md` first. Highlights:

- **D1**: Wrap Eclipse Java APIs, don't reimplement ADT REST clients (in
  v0.2+ we *did* add an HTTP layer, but it uses Eclipse's `ISystemSession`
  for auth — still inside-Eclipse).
- **D3**: Reflectively kickstart the dormant server (history; **superseded by D9**).
- **D4**: Tools take `destination` per-call, not via `setDestination(...)` —
  works around the early-return bug + supports multi-destination clients.
- **D8**: No third-party deps. `Json.java` is hand-rolled.
- **D9**: ADT 3.60 ships supported activation → drop the kickstart, become a
  pure tool-provider (zero reflection into SAP internals).

## Roadmap (not commitments)

### Shipped in v0.4.0
- **ADT 3.60 migration**: dropped the reflective kickstart; pure tool-provider
  on SAP's now-supported server. This is what the "v0.4" milestone became — it
  pre-empted the tool additions originally sketched below.

### Next (the original "v0.4" tool ideas, still planned)
- `arc1_sap_where_used` — needs one Eclipse HTTP trace capture to confirm
  the XML body shape of `/sap/bc/adt/repository/informationsystem/whereused`.
- `arc1_sap_object_structure` — same situation, URI literal not cleanly
  extractable from bytecode.

### v0.5+ (further out)
- Workspace-`IFile` sync helper — unlocks `arc1_sap_check_syntax`,
  `arc1_sap_object_revisions`, eventually `arc1_sap_run_unit_tests`.
  Bigger investment (~60 lines for the sync helper + UI thread handling
  + IFile lifecycle).
- Optional Eclipse Marketplace listing (currently GitHub Releases only).

### Out of scope (will not ship)
- Mutating tools that overlap with SAP's built-ins
  (`abap_transport-create`, `abap_generators-generate_objects` already
  cover the common workflows).
- Write/activate/delete that needs lock+transport ceremony — that's
  Eclipse editor + ARC-1 territory.
- Free-form SQL (`SAPQuery` equivalent) — no server-side safety gates
  make sense in a single-user desktop plugin.
- Anything requiring SAP backend code to be installed.

## Useful pointers

- **SAP ADT MCP deep dive** (bytecode + plugin.xml of SAP's own bundles):
  in the ARC-1 repo at `docs/research/adt-eclipse-mcp-deep-dive-2026-05-22.md`.
- **Forced-activation probe** (historical; how the ≤0.3.x kickstart was proven
  on ADT 3.58): ARC-1 `docs/research/adt-mcp-forced-activation-2026-05-22.md`.
- **Local Eclipse install** for testing:
  `~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/`
- **p2 plugin pool** (where SAP/Eclipse JARs live):
  `~/.p2/pool/plugins/`
- **Build outputs** (gitignored): `build/`, `*.jar`
- **MCP server token + port**: SAP's *ABAP Development → MCP Server* preference
  page (stored in the `com.sap.adt.mcp.core.ui` preference node). This plugin
  no longer writes a token file.

## When debugging

- Server doesn't start: it's SAP's now — confirm the *ABAP Development → MCP
  Server* preference is enabled and `-DadtMcpServerPrefEnabled=true` is set,
  then check `Error Log` for `com.sap.adt.mcp.core` entries. Our `Arc1Startup`
  only logs guidance + autologin under `com.arc1.mcp`.
- Tool returns `isError: true`: the message inside the error is the
  Java exception class + message. Cross-reference with bytecode if a
  SAP API changed shape.
- MCP client gets 401: token mismatch. The token lives on SAP's *ABAP
  Development → MCP Server* preference page — copy it into the client's
  `Authorization: Bearer` header.
- Tool doesn't appear in `tools/list`: extension registration failed.
  Check Error Log for `Skipping MCP tool ... with invalid name/schema/...`.

## Final note

This codebase optimizes for **predictability and small surface area** over
features. Each tool is ~80 lines that look like every other tool. The
`AdtHttp` helper is the single place auth and HTTP live. Adding a new
tool should never require reading code outside `src/com/arc1/mcp/`.

Keep it that way.
