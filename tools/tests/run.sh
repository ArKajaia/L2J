#!/bin/bash
# Unit tests of the pure fake player fight logic (java/org/l2jmobius/gameserver/ai/fight/), which imports only java.*.
# Compiles those classes (without the server adapters) and tools/tests/src/*.java, then runs every *Test class.
#
#   tools/tests/run.sh            all tests
#   tools/tests/run.sh FightRead  the tests whose class name contains FightRead
set -e
REPO=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SOURCES=$(find "$REPO/java/org/l2jmobius/gameserver/ai/fight" -name '*.java' ! -name 'FightReadFeed.java' ! -name 'SkillFactsReader.java' 2>/dev/null || true)
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -d "$OUT" $SOURCES "$REPO"/tools/tests/src/*.java 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f "$OUT/Check.class" ] || { echo "Compilation failed."; exit 1; }
FAILED=0
for test in "$REPO"/tools/tests/src/*Test.java; do
	name=$(basename "$test" .java)
	[ -n "$1" ] && [[ "$name" != *"$1"* ]] && continue
	if "$JAVA_HOME/bin/java" -ea -cp "$OUT" "$name" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'; [ "${PIPESTATUS[0]}" -eq 0 ]; then
		:
	else
		FAILED=1
	fi
done
exit $FAILED
