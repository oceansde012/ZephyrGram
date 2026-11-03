# Native cloud-memory bridge

This is the real Android/Python host implementation, not a plugin-side HTTP substitute. Python never receives the host token. The existing AI URL/token are read only inside the native transport. These additions do not enable collection, scan history or call an AI provider.

`client.MEMORY_API_VERSION == 1` is advertised only when the matching native runtime is present. Every method requires a declared `ai.memory` permission in the plugin manifest and a worker thread. The runtime checks permission again natively and invalidates in-flight operations across plugin unload/reload. Both settings_changed dispatch methods invalidate the epoch synchronously before queueing Python callbacks. ai_memory_cancel() is a local synchronous helper for the plugin’s own consent/off/delete handlers; it does not revoke server consent or fake deletion success. The plugin manager's existing permission approval applies; new permission is not automatically granted to older plugin manifests.

## Python API

All `account`, `dialog_id` and `topic_id` arguments are explicit. A missing forum topic cannot default silently to0. Ordinary non-forum chats use topic0. Every data operation requires `expected_binding` obtained from status **when the owner accepted that endpoint/account for memory**.

```python
status = client.ai_memory_status(account, timeout=5)
# Ask the owner for consent for this exact account/chat/topic before calling consent.
binding = status["binding"]
client.ai_memory_consent(account, dialog_id, topic_id, retention_days=30,
                         timeout=5, expected_binding=binding)
client.ai_memory_write(account, dialog_id, topic_id, messages,
                       timeout=5, expected_binding=binding)
context = client.ai_memory_context(account, dialog_id, topic_id, query="",
                                   timeout=5, expected_binding=binding)
client.ai_memory_cancel()  # Local no-I/O cancellation before off/delete/settings changes.
client.ai_memory_delete(account, dialog_id, topic_id,
                        timeout=5, expected_binding=binding)
client.ai_memory_import(account, private_dialog_id, 0, legacy_record,
                        timeout=5, expected_binding=binding)
```

Native status verifies `protocolVersion:1` and that server `ownerId` equals `UserConfig.getClientUserId(account)`. It returns whitelisted `protocolVersion`, verified `ownerId`, `ownerMatch:true`, `storage`, `collectionEnabled`, `limits`, optional `features.legacyImport`, and opaque `binding`. The latter is a length-separated SHA256 assertion over version, normalized endpoint, token, slot and owner; it is not accepted for authentication. Do not log it or treat it as a reusable credential.

The bridge validates that binding against current configuration before even a fresh handshake. It rechecks configuration, token, owner, selected account and plugin lifecycle before headers/body chunks and after responses. A changed binding requires fresh owner consent, not an automatic replacement. All requests use captured credentials, fixed `/v1/memory` paths, HTTPS only, no redirects and `X-Zephyr-Account-Id`. The timeout (1–30seconds) is one shared deadline for handshake plus action, with connection/read limits and a disconnect watchdog. No automatic retries/fallbacks occur. Already-transmitted bytes cannot be recalled; callers must serialize consent/deletion and keep cancellation guards too.

Collection-disabled status remains valid and DELETE remains possible when storage is ready. Missing storage or network failure is an error, not a false deletion success. Limits are enforced both before networking and against the server's advertised smaller bounds. Error bodies, tokens, HTTP exception messages and arbitrary server fields are never returned/logged by this bridge. Errors become `AiError` with fixed `memory_*` messages and integer code; permission failures become `PermissionError`.

Additional codes:9 unsupported,10 account mismatch/inactive,11 cancelled,12 invalid/bounded data,13 binding changed,14 collection disabled,15 consent required. Existing AI codes1–6 remain applicable. The plugin owns the local opt-in UI, offline deletion state and history selection; the bridge itself does not change them.

## Message and legacy provenance

Write records have exactly `messageId`, decimal-string `senderId`, `speaker` (`owner|other`), `text`, timezone-bearing `date`. Native validation checks roles against the actual owner, private-chat sender against dialog ID, source ID bounds, duplicates and sizes. Context is whitelisted and checked against the requested dialog/topic. Returned texts remain untrusted historical data; participants stay separate.

Legacy import is only for one explicitly selected existing private-chat summary: `{summary,throughId,revision,updatedAt}` with summary<=8000, throughId>=0, revision>=1. Groups and nonzero topics are rejected before networking. Server must advertise `features.legacyImport` and `maxLegacySummaryChars`. Stored notes remain a separate bounded `notes` array with exact `untrusted_legacy_summary`, `local_autoreply_summary`, `mixed_unknown` labels; they are never manufactured into authored messages. Import response preserves the stored revision/boundary/date and allowed unchanged/stale/conflict reason.

## Verification

Python wrapper cases run without Android or network:

```
python tools/test_memory_bridge.py
```

JVM transport tests compile the actual production `ZephyrMemoryClient.java` against real JSON-java and synthetic account/config/connection fixtures. They do not compile the whole Android app or contact any endpoint. Download the test-only `org.json:json:20240303` JAR from Maven Central outside the repository, then run with JDK17:

```
powershell -File tools/run_memory_jvm_tests.ps1 -JsonJar <json-20240303.jar> -BuildDirectory <temporary-output-directory>
```

Full APK compilation and phone/native lifecycle verification are still required. The fixtures under `tools/memory_bridge_test` are not part of Gradle's production source set and contain only synthetic data.
