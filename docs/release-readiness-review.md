# v0.1.0 release-readiness review

**Date**: 2026-05-22
**Reviewer**: end-to-end self-review

## Verdict

**Ready to go public**, with three items that need maintainer action before the
first `git push`. None are blockers; all are 1-minute fixes.

## What's in v0.1.0

- 7 MCP tools wrapping SAP ADT Java APIs
- Auto-activation of the dormant SAP ADT MCP server
- Auto-login into the configured ABAP project
- Forward-compatible with SAP's eventual official activation
- Documentation: README, architecture, decisions, 3 implementation plans,
  research context
- CI build verification + release-on-tag GitHub workflows
- Smoke test script
- MIT license, CHANGELOG, CONTRIBUTING, SECURITY

## Quality checks

| Check | Result |
|---|---|
| Compiles clean | ✓ 30KB JAR, 12 classes, 7 mcpTool contributions |
| Manifest version matches Bundle-Version | ✓ 0.1.0 |
| All tool classes implement `IAdtMCPTool` | ✓ verified via javap |
| No hardcoded credentials in source | ✓ token comes from system property at runtime |
| No third-party dependencies | ✓ pure JDK + Eclipse/SAP bundles |
| No `internal` SAP API usage | ✓ only reflective call is to `AdtMCPCorePlugin` (single, well-documented) |
| Output bounded for all tools | ✓ maxResults / typeFilter / include arrays |
| Errors don't propagate to MCP runtime | ✓ every `execute()` wrapped in catch-all |
| Forward-compat with future SAP activation | ✓ `peekRunningPort` no-ops if server is already running |
| Git history clean (no built artifacts tracked) | ✓ `*.jar` gitignored, only sources committed |
| Smoke test script covers all 7 tools | ✓ |

## Things you (maintainer) need to do before publishing

### 1. Replace `<your-user>` placeholders

The README and `docs/plans/03-publishing.md` reference your future GitHub
username/org as a placeholder. Run:

```bash
cd ~/DEV/arc1-mcp-ext
./scripts/finalize-readme.sh <your-github-username>
git add -A && git commit -m "docs: set repo URL"
```

### 2. Confirm the license author

`LICENSE` currently reads `Copyright (c) 2026 Marian Zeis`. Verify or
update.

### 3. Verify the CI's Eclipse-install URL

`.github/workflows/build.yml` downloads Eclipse 2025-09 and installs ADT
from `https://tools.hana.ondemand.com/2025-09`. I haven't actually run
this in CI — the URL is the documented SAP ADT update site URL pattern,
but it may need adjustment on first run. Watch the first GitHub Actions
run after pushing and fix any download / install errors.

The fallback if SAP's update site isn't directly fetchable in CI: precompile
the ADT bundles locally, upload them to a private S3 bucket / GitHub Release
asset, and download them in CI. Documented in `docs/plans/03-publishing.md`.

## Recommended publishing flow

```bash
cd ~/DEV/arc1-mcp-ext

# 1. finalize URLs
./scripts/finalize-readme.sh marianfoo

# 2. create the GitHub repo (replace marianfoo with your GitHub user/org)
gh repo create marianfoo/arc1-mcp-ext --public --source=. --remote=origin --push

# 3. cut v0.1.0
git tag v0.1.0
git push origin v0.1.0
# → triggers .github/workflows/release.yml → builds JAR → attaches to GitHub Release
```

After that, users can install with:

```bash
curl -L -o ~/eclipse/.../dropins/com.arc1.mcp_0.1.0.jar \
  https://github.com/marianfoo/arc1-mcp-ext/releases/download/v0.1.0/com.arc1.mcp_0.1.0.jar
```

## Known limitations of v0.1.0

These are not blockers but worth noting in your release announcement:

- **Eclipse 2025-09 / ADT 3.58 only**. Strict `[3.58.0,4.0.0)` version
  range. Earlier or major-newer Eclipse-for-ABAP versions won't load the
  plugin and will surface a clear error in the Error Log.
- **Read-only tools**. No write/activate/delete. Use SAP's built-ins
  (`abap_transport-create`, `abap_generators-generate_objects`) for those —
  they're activated by this plugin too.
- **Source-code reading not yet shipped**. Documented in
  `docs/plans/02-feature-parity-push.md`; requires a workspace-IFile sync
  helper. Planned for v0.2.
- **macOS aarch64 build script paths**. `build.sh` references the
  `org.eclipse.justj.openjdk.hotspot.jre.full.macosx.aarch64` JDK. On Linux
  / Windows / Intel-Mac the path will be different but the glob in
  `build.sh` should find it. If it doesn't, contributors should adjust.

## Post-launch follow-ups

1. **Plan 04: workspace-IFile sync helper + source-read tool**. Unlocks
   `arc1_sap_read`, `arc1_sap_check_syntax`, `arc1_sap_object_revisions`.
2. **Eclipse Marketplace listing**. Requires p2 update site.
3. **Tests against ADT 3.60+** when SAP ships the next version, especially
   to see whether SAP's official activation switch makes our kickstart
   redundant (it should — that's the design intent).
