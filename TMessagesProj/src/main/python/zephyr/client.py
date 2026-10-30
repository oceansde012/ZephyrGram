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


def ai_complete(messages, system=None, model=None, temperature=None, timeout=60):
    """Blocking, non-streaming chat completion via the user's configured ZephyrGram AI server (ai.complete).
    Call from a worker thread (client.run_in_bg), never directly from hooks/UI callbacks. Returns the reply text."""
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
    try:
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


def api_version():
    return 1
