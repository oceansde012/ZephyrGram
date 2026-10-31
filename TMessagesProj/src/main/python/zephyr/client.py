"""Helpers execute as the current plugin; async work retains its owner."""
import json
import urllib.error
import urllib.request
from . import _state
from ._bridge import ChatApi, Runtime, require


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


def http_get(url, headers=None, timeout=15):
    plugin = _state.owner()
    require(plugin, "internet")
    if not str(url).startswith(("https://", "http://")):
        raise ValueError("Only HTTP(S) URLs are supported")
    req = urllib.request.Request(str(url), headers=headers or {}, method="GET")
    timeout = min(max(float(timeout), 1.0), 60.0)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
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
