#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac --release 21 -Xlint:all -Werror -d build/classes src/main/java/dev/recover402/*.java
jar --create --file build/recover402.jar --main-class dev.recover402.Main -C build/classes .
