#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
mkdir -p tools/target/classes
find tools/target/classes -type f -delete 2>/dev/null || true
javac -d tools/target/classes tools/src/com/northwind/oms/build/SmartBuild.java
exec java -cp tools/target/classes com.northwind.oms.build.SmartBuild "$@"
