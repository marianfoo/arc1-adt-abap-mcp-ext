# Contributing

Thanks for considering a contribution. This is a small project and PRs are
welcome.

## Dev environment

You need:
- Eclipse for ABAP 2025-09 (4.39) with ADT 3.58 installed
- JDK 21 (the one bundled with Eclipse works)
- Bash

The build script reads the SAP ADT JARs directly from your local Eclipse
p2 pool at `~/.p2/pool/plugins/`. No Maven, no Tycho, no network access
required for building.

## Build

```bash
./build.sh
# produces com.arc1.mcp_<version>.jar
```

To also install into your local Eclipse for testing:

```bash
INSTALL=yes ./build.sh
# then restart Eclipse with -clean
```

## Test

```bash
./scripts/smoke-test.sh A4H_001_marian_en_1
# replace A4H_... with your destination ID
```

The smoke test exercises every plugin tool end-to-end against your running
Eclipse MCP server.

## Add a new tool

1. Create `src/com/arc1/mcp/Arc1SapXxxTool.java` implementing
   `com.sap.adt.mcp.core.IAdtMCPTool`. Copy the structure of an existing tool
   (e.g. `Arc1SapObjectInfoTool`).
2. Add `<mcpTool class="com.arc1.mcp.Arc1SapXxxTool"/>` to `plugin.xml`.
3. If you use a new ADT bundle, add it to:
   - `META-INF/MANIFEST.MF` `Require-Bundle`
   - `build.sh` `BUNDLES` array
4. Add a corresponding test case to `scripts/smoke-test.sh`.
5. Build, install, restart Eclipse, run the smoke test.

## Code style

- 4-space indent (consistent with existing files).
- Tool input fields use camelCase. Tool names use `arc1_sap_<verb>` snake_case
  (SAP's validator requires `[A-Za-z0-9_-]`).
- Wrap every `execute()` in `try { ... } catch (Throwable t) { return error(...); }`.
  MCP tool calls must never throw to the runtime.
- Bound all output sizes (`maxResults`, `typeFilter`, etc.) to avoid huge
  responses.
- No third-party libraries. Use the no-dep `Json` helper.

## Commit messages

Use imperative present tense (Conventional Commits style is welcome but
not required):

```
add arc1_sap_xxx tool
fix wildcard handling in repository search
docs: clarify auto-login flow
```

## Reporting bugs

Include:
- Eclipse version (`Help → About`)
- ADT version (`Help → Installation Details → ADT`)
- Plugin version (the JAR filename in `dropins/`)
- Excerpt from the Error Log (Window → Show View → Error Log → filter by `com.arc1.mcp`)

## License

By contributing, you agree your contributions are licensed under
[MIT](LICENSE) (same as the project).
