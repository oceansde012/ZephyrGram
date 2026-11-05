"""Helpers execute as the current plugin; async work retains its owner."""
import ipaddress
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request
from . import _state
from ._bridge import ChatApi, Runtime, require

# True when the app can open ui.Page rows as sub-screens (create_settings(page)); older apps lack this name.
SETTINGS_PAGES = True

# This capability is advertised only when the native transport is present too.
try:
    MEMORY_API_VERSION = int(Runtime.memoryApiVersion())
except Exception:
    MEMORY_API_VERSION = 0

# Memory stage 2 (style examples, reply feedback, facts, digests; permission memory.style). Older apps lack
# these names: check getattr(client, "MEMORY_STYLE", False) before using the memory_* style functions.
try:
    MEMORY_STYLE_API_VERSION = int(Runtime.memoryStyleApiVersion())
except Exception:
    MEMORY_STYLE_API_VERSION = 0
MEMORY_STYLE = MEMORY_STYLE_API_VERSION == 1

try:
    PICKER_CONTEXT_API_VERSION = int(Runtime.pickerContextApiVersion())
except Exception:
    PICKER_CONTEXT_API_VERSION = 0


def _account(account):
    return Runtime.selectedAccount() if account is None else int(account)


def send_request(request, callback=None, account=None):
    plugin = _state.owner()
    require(plugin, "network.requests")
    cb = None if callback is None else _state.bound_callback(callback)
    return Runtime.sendRequest(plugin.id, request, cb, _account(account))


def send_text(dialog_id, text, reply_to=None, account=None):
    """Send a text message (messages.send). Ghost mode still applies at the network layer."""
    plugin = _state.owner()
    require(plugin, "messages.send")
    if reply_to is None:
        Runtime.sendText(plugin.id, int(dialog_id), str(text), _account(account))
    else:
        _call(ChatApi.sendText, plugin.id, int(dialog_id), str(text), int(reply_to), _account(account))



def set_typing(dialog_id, account=None, cancel=False):
    """Send one typing indicator, or cancel it (network.requests). Non-blocking.
    Returns True if queued, False if blocked by ghost mode or unsupported (secret
    chats, unknown cached peers, Saved Messages, broadcast-channel typing).
    Queued does not mean acknowledged by Telegram; normal network ghost checks apply.
    """
    plugin = _state.owner()
    require(plugin, "network.requests")
    return bool(_call(ChatApi.setTyping, plugin.id, int(dialog_id), _account(account), bool(cancel)))


def mark_read(dialog_id, max_id, account=None):
    """Explicitly send a read receipt through positive max_id (network.requests).
    Returns True if queued, False for ghost suppression, secret chats or unknown
    cached peers. Does not change local unread counters. Media/voice helpers never
    call this. Zero/negative max_id is rejected, so this cannot mean 'read all'.
    """
    plugin = _state.owner()
    require(plugin, "network.requests")
    max_id = int(max_id)
    if max_id <= 0 or max_id > 2147483647:
        raise ValueError("max_id must be a positive 32-bit message id")
    return bool(_call(ChatApi.markRead, plugin.id, int(dialog_id), max_id, _account(account)))


# Compatibility spellings used by older autoreply plugins.
send_typing = set_typing
read_history = mark_read


def get_recent_stickers(limit=30, account=None):
    """Recent stickers (favorites as fallback), newest first (chat.read). Served from the in-memory cache,
    never blocks on the network; if not loaded yet a background load starts and the list may be empty
    (retry shortly). Returns [{id: int, emoji: str, set_title: str, mime: str, is_animated: bool}];
    limit is clamped to 1..100. The id can be passed to send_sticker."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    raw = _call(ChatApi.getRecentStickers, plugin.id, int(limit), _account(account))
    return [{"id": int(s["id"]), "emoji": s.get("emoji") or "", "set_title": s.get("set_title") or "",
             "mime": s.get("mime") or "", "is_animated": bool(s.get("is_animated"))}
            for s in json.loads(str(raw))]


def send_sticker(dialog_id, sticker_id, account=None, reply_to=None):
    """Send a sticker by document id (messages.send) from recent/favorite/installed stickers.
    Returns True if queued, False if the sticker was not found or the chat is secret. Like send_text it
    skips plugin hooks (ghost mode still applies) and returns no message id. Worker thread only."""
    plugin = _state.owner()
    require(plugin, "messages.send")
    return bool(_call(ChatApi.sendSticker, plugin.id, int(dialog_id), int(sticker_id),
                      0 if reply_to is None else int(reply_to), _account(account)))


def set_draft(dialog_id, text, account=None):
    """Put text into the chat input draft (messages.send). Empty text clears the draft."""
    plugin = _state.owner()
    require(plugin, "messages.send")
    _call(ChatApi.setDraft, plugin.id, int(dialog_id), str(text), _account(account))


class AiError(RuntimeError):
    """ai_complete failure. `code`: 1 not configured, 2 unauthorized, 3 rate limit,
    4 server starting, 5 network/timeout, 6 server error, 0 other."""
    def __init__(self, message, code=0):
        super().__init__(message)
        self.code = code


def _call(fn, *args):
    try:
        return fn(*args)
    except Exception as exc:
        text = str(exc)
        if text.startswith("java.lang.SecurityException:"):
            raise PermissionError(text.split(":", 1)[1].strip()) from exc
        raise


def ai_complete(messages, system=None, model=None, temperature=None, timeout=60, images=None):
    """Blocking, non-streaming chat completion via the user's configured ZephyrGram AI server (ai.complete).
    Call from a worker thread (client.run_in_bg), never directly from hooks/UI callbacks. Returns the reply text.
    images: optional list of base64 JPEG strings (no data: prefix, at most 4, ~1.6 MB total); they are attached to
    the LAST user message, whose content becomes [{type:text}, {type:image_url, data:image/jpeg;base64,...}]."""
    plugin = _state.owner()
    require(plugin, "ai.complete")
    if isinstance(messages, str):
        messages = [{"role": "user", "content": messages}]
    items = []
    for m in messages:
        role = str(m.get("role", "user"))
        if role not in ("system", "user", "assistant"):
            raise ValueError("Invalid message role: " + role)
        items.append({"role": role, "content": str(m.get("content", ""))})
    if not items:
        raise ValueError("messages must not be empty")
    temp = float("nan") if temperature is None else min(max(float(temperature), 0.0), 2.0)
    imgs = [str(i) for i in (images or []) if i]
    try:
        if imgs:
            return str(ChatApi.aiComplete(plugin.id, json.dumps(items, ensure_ascii=False),
                                          None if system is None else str(system),
                                          None if model is None else str(model),
                                          temp, int(min(max(float(timeout), 1), 300)), json.dumps(imgs)))
        return str(ChatApi.aiComplete(plugin.id, json.dumps(items, ensure_ascii=False),
                                      None if system is None else str(system),
                                      None if model is None else str(model),
                                      temp, int(min(max(float(timeout), 1), 300))))
    except Exception as exc:
        getter = getattr(exc, "getMessage", None)
        text = str(getter() if callable(getter) else exc)
        if "SecurityException" in str(exc):
            raise PermissionError(text) from exc
        code = int(getattr(exc, "code", 0) or 0)
        lowered = text.lower()
        if not code and "not configured" in lowered:
            code = 1
        elif not code and "timed out" in lowered:
            code = 5
        raise AiError(text, code) from exc



def _memory_account(account):
    if isinstance(account, bool) or not isinstance(account, int) or account < 0:
        raise AiError("memory_invalid_account", 10)
    return account


def _memory_scope(dialog_id, topic_id):
    if (isinstance(dialog_id, bool) or not isinstance(dialog_id, int) or not dialog_id
            or not -(1 << 63) <= dialog_id < (1 << 63)
            or isinstance(topic_id, bool) or not isinstance(topic_id, int)
            or not 0 <= topic_id <= 2147483647):
        raise AiError("memory_invalid_scope", 12)
    return dialog_id, topic_id


def _memory_binding(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise AiError("memory_binding_changed", 13)
    return value


def _memory_timeout(timeout, upper=30):
    try:
        if isinstance(timeout, bool):
            raise ValueError()
        return max(1, min(upper, int(timeout)))
    except (TypeError, ValueError, OverflowError):
        raise AiError("memory_invalid_timeout", 12) from None


def _memory_call(name, *args):
    return _native_memory("ai.memory", MEMORY_API_VERSION == 1, name, *args)


def _native_memory(permission, available, name, *args):
    plugin = _state.owner()
    require(plugin, permission)
    if not available:
        raise AiError("memory_not_supported", 9)
    try:
        result = json.loads(str(getattr(ChatApi, name)(plugin.id, *args)))
        if not isinstance(result, dict):
            raise ValueError()
        return result
    except Exception as exc:
        if "SecurityException" in type(exc).__name__ or "SecurityException" in str(exc):
            raise PermissionError("Plugin needs permission " + permission) from None
        code = int(getattr(exc, "code", 6) or 6)
        getter = getattr(exc, "getMessage", None)
        key = str(getter() if callable(getter) else exc)
        if not re.fullmatch(r"memory_[a-z_]{1,50}", key):
            key = "memory_operation_failed"
        raise AiError(key, code) from None


def ai_memory_status(account, timeout=10):
    """Verified account-bound cloud-memory status (ai.memory, worker thread only).
    Returns protocolVersion, ownerId, ownerMatch, opaque binding, storage, collectionEnabled and limits.
    The token is never exposed. No collection or provider call is started by this method.
    """
    return _memory_call("aiMemoryStatus", _memory_account(account), _memory_timeout(timeout))


def ai_memory_cancel():
    """Synchronously invalidate this plugin's in-flight memory calls (ai.memory); no I/O.
    Call before local off/delete/consent changes. Does not revoke server consent or report deletion success.
    """
    _memory_call("aiMemoryCancel")


def ai_memory_consent(account, dialog_id, topic_id, retention_days=30, timeout=10, expected_binding=None):
    """Explicit per-chat/topic opt-in; expected_binding must come from the owner's accepted status.
    Does not read or send history. A changed endpoint/token/account requires fresh owner consent.
    """
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    if isinstance(retention_days, bool) or not isinstance(retention_days, int) or not 1 <= retention_days <= 90:
        raise AiError("memory_invalid_retention", 12)
    return _memory_call("aiMemoryConsent", _memory_account(account), dialog_id, topic_id,
                        retention_days, _memory_timeout(timeout), _memory_binding(expected_binding))


def ai_memory_write(account, dialog_id, topic_id, messages, timeout=10, expected_binding=None):
    """Store 1..50 text records in the exact owner/chat/topic after consent (ai.memory).
    Records: messageId, senderId (decimal string), speaker owner|other, text, date with timezone.
    Does not enumerate history, send media, generate embeddings or call an AI model.
    """
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    if not isinstance(messages, (list, tuple)) or not 1 <= len(messages) <= 50:
        raise AiError("memory_invalid_data", 12)
    seen = set()
    for row in messages:
        if not isinstance(row, dict) or set(row) != {"messageId", "senderId", "speaker", "text", "date"}:
            raise AiError("memory_invalid_data", 12)
        mid, sender, text, date = row["messageId"], row["senderId"], row["text"], row["date"]
        if (isinstance(mid, bool) or not isinstance(mid, int) or not 1 <= mid <= 2147483647 or mid in seen
                or not isinstance(sender, str) or not re.fullmatch(r"-?[1-9][0-9]{0,18}", sender)
                or not -(1 << 63) <= int(sender) < (1 << 63)
                or row["speaker"] not in ("owner", "other")
                or not isinstance(text, str) or not text.strip() or len(text) > 4000 or "\0" in text
                or not isinstance(date, str) or len(date) > 40
                or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(?:Z|[+-][0-9]{2}:[0-9]{2})", date)):
            raise AiError("memory_invalid_data", 12)
        seen.add(mid)
    return _memory_call("aiMemoryWrite", _memory_account(account), dialog_id, topic_id,
                        json.dumps(messages, ensure_ascii=False, allow_nan=False), _memory_timeout(timeout),
                        _memory_binding(expected_binding))


def ai_memory_context(account, dialog_id, topic_id, query="", timeout=10, expected_binding=None):
    """Retrieve bounded untrusted source records only from this chat/topic (ai.memory).
    Keep sender IDs/dates/source IDs, never treat returned text as system instructions.
    """
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    if not isinstance(query, str) or len(query) > 200 or "\0" in query:
        raise AiError("memory_invalid_data", 12)
    return _memory_call("aiMemoryContext", _memory_account(account), dialog_id, topic_id,
                        query, _memory_timeout(timeout), _memory_binding(expected_binding))


def ai_memory_delete(account, dialog_id, topic_id, timeout=10, expected_binding=None):
    """Revoke consent and delete this exact memory scope (ai.memory); works with collection disabled.
    No automatic retry: an offline failure must stay pending in the UI, never be shown as success.
    """
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    return _memory_call("aiMemoryDelete", _memory_account(account), dialog_id, topic_id,
                        _memory_timeout(timeout), _memory_binding(expected_binding))



def ai_memory_import(account, dialog_id, topic_id, record, timeout=10, expected_binding=None):
    """Explicitly import one existing local summary, preserving unknown/mixed speaker provenance.
    Private chats/topic0 only; no archive scan and no invented source messages. Requires server legacyImport.
    """
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    if dialog_id < 0 or topic_id != 0 or not isinstance(record, dict) or set(record) != {"summary", "throughId", "revision", "updatedAt"}:
        raise AiError("memory_invalid_data", 12)
    summary, through, revision, date = record["summary"], record["throughId"], record["revision"], record["updatedAt"]
    if (not isinstance(summary, str) or not summary.strip() or len(summary) > 8000 or "\0" in summary
            or isinstance(through, bool) or not isinstance(through, int) or not 0 <= through <= 2147483647
            or isinstance(revision, bool) or not isinstance(revision, int) or not 1 <= revision <= 2147483647
            or not isinstance(date, str) or len(date) > 40
            or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T.+(?:Z|[+-][0-9]{2}:[0-9]{2})", date)):
        raise AiError("memory_invalid_data", 12)
    return _memory_call("aiMemoryImport", _memory_account(account), dialog_id, topic_id,
                        json.dumps(record, ensure_ascii=False, allow_nan=False), _memory_timeout(timeout),
                        _memory_binding(expected_binding))


# ---------------------------------------------------------------- memory stage 2: style layer (memory.style)

_STYLE_AREA = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
_STYLE_SOURCES = ("owner_real", "accepted", "edited_final")
_STYLE_OUTCOMES = ("accepted", "edited", "replaced", "rejected", "owner_first")
_STYLE_SUBJECTS = ("self", "peer")
_STYLE_SOURCE_MSG = re.compile(r"[A-Za-z0-9_.:-]{1,128}")


def _style_call(name, *args):
    return _native_memory("memory.style", MEMORY_STYLE_API_VERSION == 1, name, *args)


def _style_invalid(key="memory_invalid_data"):
    return AiError(key, 12)


def _style_area(area):
    if not isinstance(area, str) or not _STYLE_AREA.fullmatch(area):
        raise _style_invalid("memory_invalid_area")
    return area


def _style_int(value, low, high):
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise _style_invalid()
    return value


def _style_text(value, limit, empty=False):
    if not isinstance(value, str) or len(value) > limit or "\0" in value or (not empty and not value.strip()):
        raise _style_invalid()
    return value


def _style_rows(rows, keys):
    if not isinstance(rows, (list, tuple)) or not 1 <= len(rows) <= 50:
        raise _style_invalid()
    out = []
    for row in rows:
        if not isinstance(row, dict) or not set(row) <= set(keys):
            raise _style_invalid()
        out.append({k: v for k, v in row.items() if v is not None})
    return out


def _style_request(operation, account, area, body, timeout, expected_binding, upper=30):
    payload = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False)
    return _style_call("aiMemoryStyle", _memory_account(account), operation, area, payload,
                       _memory_timeout(timeout, upper), _memory_binding(expected_binding))


def memory_style_area(dialog_id, topic_id=0):
    """Recommended area label of one chat ("dialog:<id>") or forum topic ("dialog:<id>:<topic>"). Use "global"
    for cross-chat data. Areas never mix in search; a secret chat's area is refused."""
    dialog_id, topic_id = _memory_scope(dialog_id, topic_id)
    return "dialog:%d" % dialog_id if topic_id == 0 else "dialog:%d:%d" % (dialog_id, topic_id)


def memory_style_status(account, timeout=10):
    """Verified status of the style layer (memory.style, worker thread only). Returns protocolVersion, ownerId,
    ownerMatch, opaque binding (same value as ai_memory_status), storage, collectionEnabled, features.style and
    style {ready, embeddings, embeddingStorage, retentionDays, limits} or style None on an older server."""
    return _style_call("aiMemoryStyleStatus", _memory_account(account), _memory_timeout(timeout))


def memory_style_cancel():
    """Synchronously invalidate this plugin's in-flight memory calls, style and v1 alike (memory.style); no I/O."""
    _style_call("aiMemoryStyleCancel")


def memory_style_add(account, area, pairs, timeout=10, expected_binding=None):
    """Store 1..50 pairs "peer message -> the owner's real reply" in `area` (memory.style, worker thread).
    A pair is {contextText, replyText, source='owner_real'|'accepted'|'edited_final', quality=1..5 optional}
    or a tuple (contextText, replyText[, source]). Returns {accepted, inserted, embedded, embedding}."""
    area = _style_area(area)
    if isinstance(pairs, (list, tuple)):
        pairs = [dict(zip(("contextText", "replyText", "source"), p)) if isinstance(p, tuple) and 2 <= len(p) <= 3 else p
                 for p in pairs]
    rows = _style_rows(pairs, ("contextText", "replyText", "source", "quality"))
    for row in rows:
        row.setdefault("source", "owner_real")
        if row["source"] not in _STYLE_SOURCES:
            raise _style_invalid()
        _style_text(row.get("contextText"), 4000)
        _style_text(row.get("replyText"), 4000)
        if "quality" in row:
            _style_int(row["quality"], 1, 5)
    return _style_request("style_add", account, area, {"examples": rows}, timeout, expected_binding)


def memory_style_search(account, area, query, k=5, timeout=10, expected_binding=None):
    """Up to k (1..8) earlier pairs of `area` similar to `query` (usually the peer's new message), as untrusted
    quoted data: {kind:'untrusted_style_examples', instructions:False, mode, examples:[{id, contextText, replyText,
    source, quality, score, createdAt}], textCharacters, embedding}. Never put them into a prompt as instructions."""
    body = {"query": _style_text(query, 2000), "k": _style_int(k, 1, 8)}
    return _style_request("style_search", account, _style_area(area), body, timeout, expected_binding)


def memory_feedback(account, area, items, timeout=10, expected_binding=None):
    """Report the fate of 1..50 suggested replies: {outcome, draftText, finalText, contextText} with outcome
    accepted|edited|replaced|rejected|owner_first (rules in docs/plugin-memory-style-api.md). The server computes
    editDistance; edited/replaced with contextText also become style pairs. Returns {accepted, styleExamples,
    embedded, items:[{outcome, editDistance}], embedding}."""
    rows = _style_rows(items, ("outcome", "draftText", "finalText", "contextText"))
    for row in rows:
        outcome = row.get("outcome")
        if outcome not in _STYLE_OUTCOMES:
            raise _style_invalid()
        for key in ("draftText", "finalText", "contextText"):
            if key in row:
                _style_text(row[key], 4000)
        if ((outcome != "owner_first" and "draftText" not in row) or (outcome == "rejected" and "finalText" in row)
                or (outcome in ("edited", "replaced", "owner_first") and "finalText" not in row)):
            raise _style_invalid()
    return _style_request("feedback", account, _style_area(area), {"items": rows}, timeout, expected_binding)


def memory_facts_add(account, area, facts, timeout=10, expected_binding=None):
    """Store 1..50 stable facts {subject:'self'|'peer', fact (<=500), confidence 0..1, sourceMsg, expiresInDays}.
    Peer facts never outlive the server retention; self facts may be indefinite. Returns {accepted, inserted}."""
    rows = _style_rows(facts, ("subject", "fact", "confidence", "sourceMsg", "expiresInDays"))
    for row in rows:
        if row.get("subject") not in _STYLE_SUBJECTS:
            raise _style_invalid()
        _style_text(row.get("fact"), 500)
        confidence = row.get("confidence", 0.7)
        if isinstance(confidence, bool) or not isinstance(confidence, (int, float)) or not 0 <= confidence <= 1:
            raise _style_invalid()
        if "sourceMsg" in row and (not isinstance(row["sourceMsg"], str) or not _STYLE_SOURCE_MSG.fullmatch(row["sourceMsg"])):
            raise _style_invalid()
        if "expiresInDays" in row:
            _style_int(row["expiresInDays"], 1, 365 if row["subject"] == "peer" else 3650)
    return _style_request("facts_add", account, _style_area(area), {"facts": rows}, timeout, expected_binding)


def memory_facts_search(account, area, subject, query="", k=10, timeout=10, expected_binding=None):
    """Facts of one subject ('self' or 'peer', never mixed) in `area`; empty query = most confident and recent.
    Returns {kind:'untrusted_facts', instructions:False, area, subject, facts:[{id, subject, fact, confidence,
    sourceMsg, createdAt, expiresAt}]}."""
    if subject not in _STYLE_SUBJECTS:
        raise _style_invalid()
    body = {"subject": subject, "query": _style_text(query, 200, empty=True), "k": _style_int(k, 1, 20)}
    return _style_request("facts_search", account, _style_area(area), body, timeout, expected_binding)


def memory_digest(account, area, generate=False, period_days=None, timeout=10, expected_binding=None):
    """generate=False: the latest stored digest, no model call. generate=True: one model call on the server over the
    pairs and facts of the last period_days (1..31, default 7); counts against the daily AI limit, timeout up to 60.
    Returns {generated, digest: None | {kind:'untrusted_digest', instructions:False, id, area, periodStart,
    periodEnd, text, createdAt, expiresAt}} and reason 'no_data' when there was nothing to summarise."""
    if not isinstance(generate, bool):
        raise _style_invalid()
    body = {"generate": generate}
    if period_days is not None:
        body["periodDays"] = _style_int(period_days, 1, 31)
    return _style_request("digest", account, _style_area(area), body, timeout, expected_binding, 60 if generate else 30)


def memory_delete_area(account, area, timeout=10, expected_binding=None):
    """Delete every pair, feedback row, fact and digest of the owner in `area`; works with collection disabled and
    never touches v1 chat memory. Returns {deleted:{examples, feedback, facts, digests}, area}. No retry."""
    return _style_request("delete_area", account, _style_area(area), None, timeout, expected_binding)


def memory_delete_all_areas(account, timeout=10, expected_binding=None):
    """The same as memory_delete_area for every area of the owner; returns area None."""
    return _style_request("delete_all", account, None, None, timeout, expected_binding)


def get_recent_messages(dialog_id, limit=20, account=None):
    """Newest-first list of cached messages (chat.read). Local database only, no network; messages
    not yet synced to this device are not returned. limit is clamped to 1..100. Worker thread only."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    raw = _call(ChatApi.getRecentMessages, plugin.id, int(dialog_id), int(limit), _account(account))
    return [_public_message(m) for m in json.loads(str(raw))]


def get_message_media(dialog_id, message_id, account=None, max_side=768, timeout_s=20):
    """Describe the media of a cached message for vision models (chat.read). Blocking: worker thread only.
    Returns None if the message/media is not found or on any failure, else a dict:
    type ('photo'|'video'|'gif'|'sticker'|'animated_sticker'|'voice'|'audio'|'document'|'other'), caption,
    emoji (stickers), set_title, mime, duration (s, 0 if n/a), width, height,
    image_b64 (JPEG, longest side <= max_side, base64 without data: prefix, <= 400 KB; or None) and
    image_kind ('photo'|'thumb'|'frame'|'sticker'|None). Downloads only small files (never videos) and does not
    send read receipts. max_side is clamped to 128..1280, timeout_s to 1..60."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    try:
        raw = _call(ChatApi.getMessageMedia, plugin.id, int(dialog_id), int(message_id), _account(account),
                    int(max_side), int(timeout_s))
    except PermissionError:
        raise
    except Exception:
        return None
    if raw is None:
        return None
    try:
        d = json.loads(str(raw))
    except Exception:
        return None
    return {"type": d.get("type") or "other", "caption": d.get("caption") or "", "emoji": d.get("emoji") or "",
            "set_title": d.get("set_title") or "", "mime": d.get("mime") or "",
            "duration": int(d.get("duration") or 0), "width": int(d.get("width") or 0),
            "height": int(d.get("height") or 0), "image_b64": d.get("image_b64") or None,
            "image_kind": d.get("image_kind") or None}


def get_voice(dialog_id, message_id, account=None, timeout_s=30, max_seconds=300):
    """Fetch a voice/audio message for speech-to-text (chat.read). Blocking: worker thread only. Returns None if the
    message is not found or on failure; otherwise a dict: {type: 'voice'|'audio', is_voice, duration, mime, size,
    audio_b64} (original OGG/Opus bytes, base64), or {'error': 'too_long'|'too_big'|'download_failed'|'not_audio'|
    'secret'} (limits: <= 2.5 MB and <= max_seconds), or, for a round video, the get_message_media shape with
    type 'round_video' and a frame in image_b64. Does not mark the message as read."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    try:
        raw = _call(ChatApi.getVoice, plugin.id, int(dialog_id), int(message_id), _account(account),
                    int(timeout_s), int(max_seconds))
    except PermissionError:
        raise
    except Exception:
        return None
    if raw is None:
        return None
    try:
        return json.loads(str(raw))
    except Exception:
        return None


def ai_transcribe(audio_b64, mime="audio/ogg", language=None):
    """Blocking speech-to-text via the user's AI server, POST /v1/audio/transcriptions (ai.complete). Worker thread
    only. Returns the transcript text; raises AiError (same codes as ai_complete) on failure."""
    plugin = _state.owner()
    require(plugin, "ai.complete")
    try:
        return str(ChatApi.aiTranscribe(plugin.id, str(audio_b64 or ""), str(mime or "audio/ogg"),
                                        None if not language else str(language)))
    except Exception as exc:
        getter = getattr(exc, "getMessage", None)
        text = str(getter() if callable(getter) else exc)
        if "SecurityException" in str(exc):
            raise PermissionError(text) from exc
        code = int(getattr(exc, "code", 0) or 0)
        if not code and "not configured" in text.lower():
            code = 1
        raise AiError(text, code) from exc


def get_dialog_info(dialog_id, account=None):
    """{id, title, type: 'user'|'group'|'channel'|'secret', is_bot, username} from the cache (chat.read)."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    return json.loads(str(_call(ChatApi.getDialogInfo, plugin.id, int(dialog_id), _account(account))))


def current_account():
    """Index of the account currently selected in the app (no permission needed)."""
    _state.owner()
    return int(Runtime.selectedAccount())


def search_dialogs(query, limit=20, account=None):
    """Chats from the user's chat list whose title or @username contains `query` (chat.read).
    Local cache only, no network. Returns [{id, title, type, username}]; limit is clamped to 1..100."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    raw = _call(ChatApi.searchDialogs, plugin.id, str(query or ""), int(limit), _account(account))
    return [{k: d.get(k) for k in ("id", "title", "type", "username")} for d in json.loads(str(raw))]


def resolve_username(username, account=None):
    """Resolve '@name', 'name' or a t.me link to {id, title, type, is_bot, username}, or None if no
    such public chat/user exists (chat.read). Uses the cache first, otherwise contacts.resolveUsername
    over the network (can take seconds). Blocking: worker thread only. Raises on network errors."""
    plugin = _state.owner()
    require(plugin, "chat.read")
    raw = _call(ChatApi.resolveUsername, plugin.id, str(username or ""), _account(account))
    return None if raw is None else json.loads(str(raw))


def _public_message(d):
    return {k: d.get(k) for k in ("id", "dialog_id", "from_id", "out", "date", "text", "is_bot",
                                  "is_secret", "is_service", "reply_to", "has_media")}


def message_to_dict(message_object):
    """Convert the MessageObject given to on_new_message & co. into a plain dict:
    id, dialog_id, from_id, out, date, text, is_bot, is_secret, reply_to (id or None), is_service, has_media."""
    _state.owner()
    return _public_message(json.loads(str(ChatApi.messageToJson(message_object))))


def run_on_ui(fn, delay_ms=0):
    plugin = _state.owner()
    Runtime.runOnUi(plugin.id, _state.bound_callback(fn), max(0, int(delay_ms)))


def keep_awake(seconds):
    """Keep the CPU awake for up to `seconds` (1..180) so this plugin's timers and sleeps keep running while the phone
    sleeps. It always ends by itself and a shorter request never cuts a longer one. Newer apps only (feature-detect)."""
    Runtime.keepAwake(_state.owner().id, int(max(1, min(180, float(seconds)))))


def run_in_bg(fn):
    plugin = _state.owner()
    Runtime.runInBackground(plugin.id, _state.bound_callback(fn))


def bulletin(text):
    Runtime.bulletin(_state.owner().id, str(text))


def copy_to_clipboard(text):
    Runtime.copyToClipboard(_state.owner().id, str(text))


_MAX_BODY = 16 * 1024 * 1024


def _read_limited(response):
    body = response.read(_MAX_BODY + 1)
    if len(body) > _MAX_BODY:
        raise ValueError("Response is larger than 16 MB")
    return body


_NAT64 = ipaddress.ip_network("64:ff9b::/96")


def _check_public_https(url):
    """https only, and the host must resolve exclusively to public (globally routable) addresses."""
    parts = urllib.parse.urlsplit(str(url))
    if parts.scheme != "https" or not parts.hostname:
        raise ValueError("Only HTTPS URLs are supported")
    try:
        infos = socket.getaddrinfo(parts.hostname, parts.port or 443, proto=socket.IPPROTO_TCP)
    except OSError as exc:
        raise ValueError("Cannot resolve host") from exc
    if not infos:
        raise ValueError("Cannot resolve host")
    for info in infos:
        address = ipaddress.ip_address(info[4][0].split("%", 1)[0])
        if getattr(address, "ipv4_mapped", None) is not None:
            address = address.ipv4_mapped
        elif address.version == 6 and address in _NAT64:
            # DNS64 answers on IPv6-only mobile networks: judge the embedded IPv4 address
            address = ipaddress.IPv4Address(int(address) & 0xFFFFFFFF)
        if not address.is_global or address.is_multicast:
            raise ValueError("Private, loopback and link-local addresses are not allowed")


class _PublicHttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _check_public_https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def http_get(url, headers=None, timeout=15):
    """HTTPS GET (internet). Refuses http:// and hosts resolving to private/loopback/link-local addresses, also on
    redirects. (A host that changes its DNS answer between this check and the connection is not covered.)"""
    plugin = _state.owner()
    require(plugin, "internet")
    _check_public_https(url)
    req = urllib.request.Request(str(url), headers=headers or {}, method="GET")
    timeout = min(max(float(timeout), 1.0), 60.0)
    opener = urllib.request.build_opener(_PublicHttpsRedirects())
    try:
        with opener.open(req, timeout=timeout) as response:
            return response.status, _read_limited(response)
    except urllib.error.HTTPError as response:
        with response:
            return response.code, _read_limited(response)


def app_version():
    return str(Runtime.appVersion())


def app_locale():
    """Language code of the app UI, e.g. 'uk', 'ru', 'en' (no permission needed)."""
    _state.owner()
    return str(Runtime.appLocale())


def api_version():
    return 1


