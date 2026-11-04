#!/bin/sh
# Compiles the real ZephyrAiSetupParser (pure Java) with its host test and runs it.
set -e
root=$(cd "$(dirname "$0")/.." && pwd)
out=$(mktemp -d)
javac -encoding UTF-8 -d "$out" \
  "$root/TMessagesProj/src/main/java/org/telegram/messenger/zephyr/ZephyrAiSetupParser.java" \
  "$root/tools/ai_setup_parser_test/org/telegram/messenger/zephyr/SetupParserHostTest.java"
java -cp "$out" org.telegram.messenger.zephyr.SetupParserHostTest
