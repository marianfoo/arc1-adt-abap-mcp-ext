# CLAUDE.md

Guidance for AI assistants (and humans) working on `com.arc1.mcp` (the
Eclipse plugin in this repo). Read this first.

## Project goal

**Activate and extend SAP's dormant Model Context Protocol (MCP) server
inside Eclipse-for-ABAP**, so AI clients (Claude Code, GitHub Copilot,
Cursor, Claude Desktop) can read your ABAP system without any extra process.

The user installs one JAR, edits 3 lines in `eclipse.ini`, restarts Eclipse
once. From then on, an authenticated MCP endpoint is live at
`http://localhost:54322/mcp` whenever Eclipse is running.

### Why this exists

SAP ships an MCP server in ADT 3.58+ but hasn't turned it on
(officially "disabled and cannot be activated"). The implementation is
complete, just dormant. SAP also exposes a public Eclipse extension point
`com.sap.adt.mcp.core.adtMcpTools` for contributing extra tools. This
plugin does two things:

1. **Reflectively kickstart** `AdtMCPCorePlugin.startMCPServer(port, token)`
   on Eclipse workbench startup, waking the dormant server.
2. **Contribute extra tools** via the documented extension point.

When SAP eventually ships their own activation switch, our reflective
kickstart auto-no-ops (we detect `mcpServer.httpServer != null` and skip).
The extension contributions stay valid — same extension point, same MCP SDK.

### Non-goals

This plugin is intentionally **Eclipse-bound**. It is NOT trying to be:
- A standalone MCP server (that's [ARC-1](https://github.com/marianfoo/arc-1),
  in TypeScript, runs anywhere).
- A managed multi-user service with admin policy ceilings, audit, BTP
  deployment (also ARC-1 territory).
- A write/activate platform — mutating tools belong in SAP's own MCP
  surface (`abap_transport-create`, `abap_generators-generate_objects`)
  which this plugin already activates.

If a task is "centralized management", "BTP", or "non-Eclipse" — point the
user at ARC-1 instead.

## Architecture in one screen

```
Eclipse workbench startup
  │
  ├─ OSGi resolves bundles (incl. com.arc1.mcp from dropins/)
  │
  ├─ Arc1Startup.earlyStartup()  (via org.eclipse.ui.startup)
  │   ├─ if mcpServer.httpServer != null → no-op (SAP started it first)
  │   ├─ else: reflection → AdtMCPCorePlugin.startMCPServer(port, token)
  │   └─ schedule Arc1AutoLogin Job (2s delay)
  │
  └─ SAP's ToolRegistrationService discovers all
       <mcpTool class="..."/> extension contributions and addTool()s them
       on the McpSyncServer (Java MCP SDK).
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
│   ├── Arc1Startup              IStartup; kickstart + autologin trigger
│   ├── Arc1AutoLogin            Background Job, ensureLoggedOn
│   ├── AdtHttp                  HTTP helper (GET + POST, 256KB cap)
│   ├── Json                     no-dep JSON helpers
│   └── Arc1Sap*Tool             one class per MCP tool
├── scripts/
│   ├── smoke-test.sh            end-to-end test of every tool
│   └── finalize-readme.sh       swap repo URL placeholders
├── docs/
│   ├── architecture.md          deeper than this file
│   ├── decisions.md             non-obvious design choices (D1–D8)
│   ├── plans/                   01–05; one per release
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
- Use packages named `*.internal.*` without isolating via reflection in one
  central place (currently only `Arc1Startup` does this, for the kickstart).
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

1. **`Arc1Startup.peekRunningPort` must no-op if the MCP server is
   already running.** When SAP ships their own activation switch,
   their code will likely run first. Clobbering their token or
   restarting the Jetty server would break SAP's intended UX.
2. **`x-friends`/`x-internal` packages**: only `com.sap.adt.mcp.core.internal`
   is touched, and only via reflection in `Arc1Startup.kickstartMcpServer`.
   Don't add more reflective entries without strong justification.
3. **`Require-Bundle: ...;bundle-version="[3.58.0,4.0.0)"`** — keep the
   version range scoped to the major. When ADT 4.x ships, internal
   packages will likely move; we want OSGi to refuse to load us rather
   than crash at runtime.

## Where each design choice lives

When in doubt, check `docs/decisions.md` first. Highlights:

- **D1**: Wrap Eclipse Java APIs, don't reimplement ADT REST clients (in
  v0.2+ we *did* add an HTTP layer, but it uses Eclipse's `ISystemSession`
  for auth — still inside-Eclipse).
- **D3**: Reflectively kickstart the dormant server vs. waiting for SAP.
- **D4**: Tools take `destination` per-call, not via `setDestination(...)` —
  works around the early-return bug + supports multi-destination clients.
- **D8**: No third-party deps. `Json.java` is hand-rolled.

## Roadmap (not commitments)

### v0.4 (planned)
- `arc1_sap_where_used` — needs one Eclipse HTTP trace capture to confirm
  the XML body shape of `/sap/bc/adt/repository/informationsystem/whereused`.
- `arc1_sap_object_structure` — same situation, URI literal not cleanly
  extractable from bytecode.

### v0.5+ (further out)
- Workspace-`IFile` sync helper — unlocks `arc1_sap_check_syntax`,
  `arc1_sap_object_revisions`, eventually `arc1_sap_run_unit_tests`.
  Bigger investment (~60 lines for the sync helper + UI thread handling
  + IFile lifecycle).
- Compatibility with ADT 3.60+ when SAP ships it.
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
- **Forced-activation probe** (proves the kickstart works end-to-end):
  ARC-1 `docs/research/adt-mcp-forced-activation-2026-05-22.md`.
- **Local Eclipse install** for testing:
  `~/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/`
- **p2 plugin pool** (where SAP/Eclipse JARs live):
  `~/.p2/pool/plugins/`
- **Build outputs** (gitignored): `build/`, `*.jar`
- **Runtime token file**: `~/.config/arc1/mcp-token.txt`

## When debugging

- Server doesn't start: `Window → Show View → Error Log`, filter by
  `com.arc1.mcp`. The `Arc1Startup` activator logs the URL + a stack
  trace on failure.
- Tool returns `isError: true`: the message inside the error is the
  Java exception class + message. Cross-reference with bytecode if a
  SAP API changed shape.
- MCP client gets 401: token mismatch between `eclipse.ini` and client
  config. Re-check both.
- Tool doesn't appear in `tools/list`: extension registration failed.
  Check Error Log for `Skipping MCP tool ... with invalid name/schema/...`.

## Final note

This codebase optimizes for **predictability and small surface area** over
features. Each tool is ~80 lines that look like every other tool. The
`AdtHttp` helper is the single place auth and HTTP live. Adding a new
tool should never require reading code outside `src/com/arc1/mcp/`.

Keep it that way.
