#!/bin/sh
# Compiles the real ZephyrMemoryClient (v1 + style layer) with synthetic fixtures and runs both JVM test mains.
# Usage: tools/run_memory_jvm_tests.sh <json-20240303.jar>   (test-only org.json JAR, downloaded outside the repo)
set -e
[ -n "$1" ] || { echo "usage: $0 <json-20240303.jar>" >&2; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
javac -encoding UTF-8 -cp "$1" -d "$out" \
  "$root/TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrMemoryClient.java" \
  $(find "$root/tools/memory_bridge_test" -name '*.java')
java -cp "$out:$1" org.telegram.messenger.zephyr.MemoryBridgeTest
java -cp "$out:$1" org.telegram.messenger.zephyr.MemoryStyleBridgeTest
