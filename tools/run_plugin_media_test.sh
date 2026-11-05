#!/bin/sh
# Compiles the real PluginMediaPolicy (pure Java, rules behind client.send_media) with its JVM test and runs it.
set -e
root=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
javac -encoding UTF-8 -d "$out" \
  "$root/TMessagesProj/src/main/java/org/telegram/messenger/zephyr/plugins/PluginMediaPolicy.java" \
  "$root/tools/plugin_media_test/org/telegram/messenger/zephyr/plugins/PluginMediaPolicyTest.java"
java -cp "$out" org.telegram.messenger.zephyr.plugins.PluginMediaPolicyTest
