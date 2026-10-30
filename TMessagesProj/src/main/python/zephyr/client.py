"""Helpers execute as the current plugin; async work retains its owner."""
import urllib.error
import urllib.request
from . import _state
from ._bridge import Runtime, require


def _account(account):
    return Runtime.selectedAccount() if account is None else int(account)


def send_request(request, callback=None, account=None):
    plugin = _state.owner()
    require(plugin, "network.requests")
    cb = None if callback is None else _state.bound_callback(callback)
    return Runtime.sendRequest(plugin.id, request, cb, _account(account))


def send_text(dialog_id, text, account=None):
    plugin = _state.owner()
    require(plugin, "messages.send")
    Runtime.sendText(plugin.id, int(dialog_id), str(text), _account(account))


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


def http_get(url, headers=None, timeout=15):
    plugin = _state.owner()
    require(plugin, "internet")
    if not str(url).startswith(("https://", "http://")):
        raise ValueError("Only HTTP(S) URLs are supported")
    req = urllib.request.Request(str(url), headers=headers or {}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=float(timeout)) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        with response:
            return response.code, response.read()


def app_version():
    return str(Runtime.appVersion())


def api_version():
    return 1
