#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
javac --release 21 -Xlint:all -Werror -cp build/classes -d build/classes src/test/java/dev/recover402/*.java
python3 scripts/test_replay_stress.py
python3 benchmarks/run.py
