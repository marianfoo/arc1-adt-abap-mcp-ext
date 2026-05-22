#!/usr/bin/env bash
# Replace <your-user> placeholders in README.md with a real GitHub username/org.
#
# Usage:  ./scripts/finalize-readme.sh marianfoo
set -euo pipefail
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <github-user-or-org>" >&2
    exit 1
fi
USER=$1
cd "$(dirname "$0")/.."
sed -i.bak "s/<your-user>/${USER}/g" README.md docs/plans/03-publishing.md
rm -f README.md.bak docs/plans/03-publishing.md.bak
echo "Replaced <your-user> with ${USER} in README.md and 03-publishing.md"
echo ""
echo "Diff preview:"
grep -n "${USER}" README.md docs/plans/03-publishing.md | head -10
