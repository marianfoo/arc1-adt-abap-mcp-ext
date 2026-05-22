# Plan 03: publishing

**Status**: ready to execute (some steps require you, the maintainer, to act in GitHub UI)
**Phase**: 3 (after Plan 02 lands; the plugin is feature-ready for v0.1)

## What "publishing" means here

Three deliverables:

1. **Public GitHub repository** with the source, build script, docs, CI.
2. **Automated build verification on every push** (CI green tick).
3. **Release artifacts** — pre-built `com.arc1.mcp_<version>.jar` attached to a
   GitHub Release, so users don't have to build from source.

Out of scope for v0.1: Eclipse Marketplace listing, p2 update site,
Maven Central. These are valuable longer-term but each is a multi-day setup;
GitHub Releases is enough for v0.1.

## What I can do for you (automated)

- Write `.github/workflows/build.yml` for CI build verification on every push.
- Write `.github/workflows/release.yml` to attach a JAR to each GitHub release
  on tag push.
- Write `CHANGELOG.md` starter with semver guidance.
- Write `CONTRIBUTING.md` with build + testing instructions.
- Write `.gitignore`, `.editorconfig`, `SECURITY.md`.
- Initialize a local git repo with a clean commit history.

## What you (maintainer) need to do manually

1. **Create the GitHub repo**:
   ```bash
   cd ~/DEV/arc1-mcp-ext
   gh repo create marianfoo/arc1-adt-abap-mcp-ext --public --source=. --remote=origin --push
   ```
   (Or use the GitHub web UI to create an empty repo, then `git remote add origin ... && git push -u origin main`.)

2. **Replace `marianfoo` placeholders** in `README.md` with your actual
   GitHub username/org. (Script `scripts/finalize-readme.sh` does this.)

3. **Pick a license author name**. Currently set to "Marian Zeis" in
   `LICENSE` — change if needed.

4. **Cut the first tag**:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
   The release workflow will build the JAR and attach it to the auto-created
   GitHub Release.

5. **(Optional) Update the README screenshot** with one of your own.

6. **(Optional) Add a CODEOWNERS file** if you want anyone to be auto-pinged
   on PRs.

## CI build environment

The build script needs:
- JDK 21 (for `javac --release 21`)
- The SAP ADT JARs at runtime resolution

For **local development**, `build.sh` reads everything from
`~/.p2/pool/plugins`. That's developer-local and not committed.

For **CI**, we have a problem: SAP doesn't publish their ADT jars to a public
Maven repo. The CI build needs to either:

a. **Download ADT into the runner before building.** Easy via Eclipse's p2
   director CLI (`eclipsec.exe -application org.eclipse.equinox.p2.director
   -repository http://download.sap.com/.../adt/...`). Slow (~3 min per build)
   but reliable.

b. **Cache the resolved ADT jars in the repo.** Not viable — SAP's redistribution
   terms forbid this.

c. **Stub the imports for compilation only.** We could generate minimal
   "stub" class files that match the public API surface we use, just enough
   for `javac` to succeed. CI verifies it compiles. The actual runtime
   resolution happens on the user's machine against real SAP jars. ~150
   stub files, regeneratable from the published Javadoc.

Recommend **(a)** for v0.1 — proven, reliable, just slow. Workflow below
implements it. Move to (c) if build times become a problem.

## Recommended next steps after v0.1 ships

- **Eclipse Marketplace listing**: requires p2 update site, MPC plugin
  metadata. ~1 day of work. Big distribution win.
- **Auto-publish to p2 site**: GitHub Pages hosting + `tycho-p2-repository-plugin`
  to assemble the site. ~1 day.
- **Signed JAR**: only matters if Eclipse rolls out a "signed contributors only"
  policy (currently not enforced). Skip until needed.

## Risks

- **SAP version drift**: Eclipse for ABAP 3.60+ may rename internal classes
  we reflect into. CI doesn't catch this — only user runtime does. Mitigation:
  pin `Require-Bundle: [3.58.0,4.0.0)` so the plugin refuses to load on
  incompatible versions and the user gets a clear error in the Error Log.
- **License of SAP's `IAdtMCPTool` interface**: we *implement* it from the
  bundle we Require-Bundle against. This is the standard Eclipse plugin
  extension pattern and is the explicitly intended use of an exported
  extension point. No license risk. (We don't redistribute SAP's bytecode.)

## Self-review

- **Why MIT and not Apache 2.0?** Both work. MIT is shorter; some Eclipse
  ecosystem projects prefer EPL-2.0 to match Eclipse itself, but MIT is
  perfectly fine for a contributor plugin. Reconsider if upstream ever
  wants to merge changes from your fork.
- **Why no `pom.xml` / Maven build?** The build script is 50 lines, has zero
  external dependencies, and runs in 3 seconds. A Maven setup would add
  ~200 lines of XML and require contributors to install Maven. Not worth it
  for v0.1. Switch to Tycho if/when adding p2 site.
- **Why no unit tests?** Each tool is a thin wrapper around an SAP service we
  cannot mock without depending on the real SAP jars. Smoke tests against a
  running Eclipse cover integration; unit tests would be tautological
  mocks. Trade-off accepted.
