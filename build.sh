#!/usr/bin/env bash
#
# Build com.arc1.mcp_0.1.0.jar and copy it into Eclipse ADT's dropins folder.
#
# Prereqs: ADT 3.58 installed under ~/eclipse/java-2025-09/Eclipse.app and the
# corresponding bundles cached in ~/.p2/pool/plugins.
#
set -euo pipefail

cd "$(dirname "$0")"

P2=$HOME/.p2/pool/plugins
JDK_HOME=$(ls -d "$P2"/org.eclipse.justj.openjdk.hotspot.jre.full.macosx.aarch64_21.*/jre 2>/dev/null | sort -V | tail -1)
if [[ -z "$JDK_HOME" ]]; then
    echo "ERROR: no JustJ JDK 21 found under $P2" >&2
    exit 1
fi
JAVAC="$JDK_HOME/bin/javac"
JAR="$JDK_HOME/bin/jar"

pick() {
    # pick latest <prefix>_<version>.jar from $P2, excluding sub-prefixes
    local prefix=$1
    local hit
    hit=$(ls -1 "$P2"/${prefix}_*.jar 2>/dev/null | grep -E "^${P2}/${prefix}_[0-9]" | sort -V | tail -1 || true)
    if [[ -z "$hit" ]]; then
        echo "ERROR: bundle $prefix not found in $P2" >&2
        exit 1
    fi
    echo "$hit"
}

BUNDLES=(
    "com.sap.adt.mcp.core"
    "com.sap.adt.ris.search"
    "com.sap.adt.tools.core"
    "com.sap.adt.tools.core.base"
    "com.sap.adt.project"
    "com.sap.adt.destinations"
    "com.sap.adt.destinations.model"
    "org.eclipse.core.runtime"
    "org.eclipse.core.resources"
    "org.eclipse.core.jobs"
    "org.eclipse.equinox.common"
    "org.eclipse.osgi"
    "org.eclipse.ui"
    "org.eclipse.ui.workbench"
    "org.eclipse.emf.ecore"
    "org.eclipse.emf.common"
)

CP=""
for b in "${BUNDLES[@]}"; do
    jar=$(pick "$b")
    CP="${CP}${jar}:"
    echo "  cp += $(basename "$jar")"
done
CP="${CP%:}"

echo ""
echo "Compiling..."
rm -rf build
mkdir -p build
"$JAVAC" -d build --release 21 -cp "$CP" $(find src -name '*.java')

echo "Packaging..."
mkdir -p build/META-INF
cp META-INF/MANIFEST.MF build/META-INF/
cp plugin.xml build/

OUT="com.arc1.mcp_0.1.0.jar"
rm -f "$OUT"
"$JAR" cfm "$OUT" build/META-INF/MANIFEST.MF -C build .

DROPINS="$HOME/eclipse/java-2025-09/Eclipse.app/Contents/Eclipse/dropins"
INSTALL=${INSTALL:-no}

echo ""
echo "Built $OUT"
if [[ "$INSTALL" == "yes" ]]; then
    if [[ -d "$DROPINS" ]]; then
        cp "$OUT" "$DROPINS/"
        echo "Installed to: $DROPINS/$OUT"
        echo ""
        echo "Next: restart Eclipse with -clean (kills any prior MCP session):"
        echo "  pkill -f 'Eclipse.app/Contents/MacOS/eclipse'"
        echo "  '$HOME/eclipse/java-2025-09/Eclipse.app/Contents/MacOS/eclipse' -clean &"
        echo ""
        echo "Then check ~/.config/arc1/mcp-token.txt for the URL + bearer token."
    else
        echo "ERROR: Eclipse dropins folder not found at $DROPINS" >&2
        exit 1
    fi
else
    echo ""
    echo "Not installed. To install, either:"
    echo "  INSTALL=yes ./build.sh"
    echo "  # or"
    echo "  cp $OUT $DROPINS/"
fi
