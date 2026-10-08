#!/bin/sh
set -eu
TASK_PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TASK_PROJECT_DIR"
mkdir -p build/direct
find protocol/src/main/java protocol/src/test/java -name '*.java' > build/direct/sources.txt
javac --release 17 -Xlint:all -Werror -d build/direct @build/direct/sources.txt
java -Xmx256m -cp build/direct org.customvision.protocol.AllTests
python3 tools/generate_legacy_fixtures.py --check
