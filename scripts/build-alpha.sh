#!/usr/bin/env bash
#
# Build a local alpha artifact, run the full automated gate, and write a
# checksum. Publishing is a separate, credential-bearing step:
#   MODRINTH_TOKEN=... MODRINTH_PROJECT_ID=... ./gradlew modrinth
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/^version=//p' gradle.properties | head -n1)"
if [ -z "$VERSION" ]; then
	echo "missing version in gradle.properties" >&2
	exit 1
fi

JAR="build/libs/railway-${VERSION}.jar"

echo "[alpha] version $VERSION"
./gradlew clean build --stacktrace
./gradlew runGameTest --stacktrace

if [ ! -f "$JAR" ]; then
	echo "[alpha] expected jar not found: $JAR" >&2
	find build/libs -maxdepth 1 -type f -name '*.jar' -print >&2 || true
	exit 1
fi

if ! unzip -l "$JAR" | grep -q 'assets/railway/icon.png'; then
	echo "[alpha] jar is missing assets/railway/icon.png" >&2
	exit 1
fi

if unzip -l "$JAR" | grep -qE '(^|/)(world|run|fixtures?)/'; then
	echo "[alpha] jar unexpectedly contains runtime/world data" >&2
	exit 1
fi

mkdir -p dist
cp "$JAR" "dist/railway-${VERSION}.jar"
sha256sum "dist/railway-${VERSION}.jar" > "dist/railway-${VERSION}.jar.sha256"

echo "[alpha] artifact: dist/railway-${VERSION}.jar"
echo "[alpha] checksum: dist/railway-${VERSION}.jar.sha256"
ls -lh "dist/railway-${VERSION}.jar" "dist/railway-${VERSION}.jar.sha256"
