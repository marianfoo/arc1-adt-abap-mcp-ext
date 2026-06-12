#!/usr/bin/env bash
#
# Build the current Bundle-Version, create/push the matching tag, and upload the
# JAR to the GitHub Release created by .github/workflows/release.yml.
#
# This runs from the maintainer machine because SAP ADT bundles are resolved from
# the local Eclipse installation and cannot be redistributed through public CI.
set -euo pipefail

cd "$(dirname "$0")/.."

REMOTE=${REMOTE:-origin}
REPO=${REPO:-marianfoo/arc1-adt-abap-mcp-ext}
VERSION=$(awk '/^Bundle-Version:/ {print $2}' META-INF/MANIFEST.MF | tr -d '\r')
TAG="v${VERSION}"
ARTIFACT="com.arc1.mcp_${VERSION}.jar"

if [[ -z "$VERSION" ]]; then
    echo "ERROR: could not read Bundle-Version from META-INF/MANIFEST.MF" >&2
    exit 1
fi

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "ERROR: commit or stash tracked changes before publishing." >&2
    git status --short
    exit 1
fi

gh auth status >/dev/null

echo "Building ${ARTIFACT}..."
./build.sh

if [[ ! -f "$ARTIFACT" ]]; then
    echo "ERROR: build did not produce ${ARTIFACT}" >&2
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    tag_sha=$(git rev-list -n 1 "$TAG")
    head_sha=$(git rev-parse HEAD)
    if [[ "$tag_sha" != "$head_sha" ]]; then
        echo "ERROR: local tag ${TAG} points at ${tag_sha}, not HEAD ${head_sha}" >&2
        exit 1
    fi
else
    git tag -a "$TAG" -m "$TAG"
fi

if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/${TAG}" >/dev/null 2>&1; then
    echo "Remote tag ${TAG} already exists."
else
    git push "$REMOTE" "$TAG"
fi

echo "Waiting for GitHub Release ${TAG}..."
for _ in {1..30}; do
    if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
        break
    fi
    sleep 5
done

if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "Release workflow did not create ${TAG}; creating it with generated notes."
    gh release create "$TAG" --repo "$REPO" --title "$TAG" --generate-notes
fi

echo "Uploading ${ARTIFACT}..."
gh release upload "$TAG" "$ARTIFACT" --repo "$REPO" --clobber
gh release view "$TAG" --repo "$REPO" --json url --jq .url
