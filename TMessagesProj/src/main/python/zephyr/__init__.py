"""ZephyrGram plugin API v1. Plugins are reviewed trusted Python code."""
import json
from enum import IntEnum
from . import _state
from ._bridge import Hooks, Menus, Settings, Runtime, Result, require


class HookStrategy(IntEnum):
    DEFAULT = 0
    CANCEL = 1
    MODIFY = 2
    MODIFY_FINAL = 3


class HookResult:
    def __init__(self, strategy=HookStrategy.DEFAULT, request=None, response=None,
                 error=None, update=None, updates=None, params=None):
        self.strategy = HookStrategy(strategy)
        self.request = request
        self.response = response
        self.error = error
        self.update = update
        self.updates = updates
        self.params = params

    def _java(self):
        value = Result()
        value.strategy = int(self.strategy)
        for key in ("request", "response", "error", "update", "updates", "params"):
            setattr(value, key, getattr(self, key))
        return value


class AppEvent:
    START = "START"
    PAUSE = "PAUSE"
    RESUME = "RESUME"
    STOP = "STOP"


class MenuType(IntEnum):
    DRAWER = 0
    MESSAGE_CONTEXT = 1
    CHAT_ACTION = 2
    PROFILE_ACTION = 3


class MenuItem:
    def __init__(self, menu_type, text, on_click, icon=None, subtext=None,
                 priority=0, condition=None, key=None):
        self.menu_type = MenuType(menu_type)
        self.text = str(text)
        if not callable(on_click):
            raise TypeError("on_click must be callable")
        self.on_click = on_click
        self.icon = icon
        self.subtext = subtext
        self.priority = int(priority)
        self.condition = condition
        self.key = key


class BasePlugin:
    """Subclass as `class Plugin(BasePlugin)`; lifecycle overrides are optional."""
    def __init__(self):
        # Host fills these fields before on_plugin_load. Helpers aren't usable in __init__.
        self.id = None
        self.name = None
        self.version = None
        self.data_dir = None
        self._menus = {}
        self._settings = {}
        self._menu_sequence = 0

    def _check_owner(self):
        if _state.owner() is not self:
            raise RuntimeError("A plugin cannot register callbacks for another plugin")

    def log(self, message):
        self._check_owner()
        Runtime.log(self.id, str(message))

    def error(self, message):
        self._check_owner()
        Runtime.reportError(self.id, str(message))

    def get_setting(self, key, default=None):
        self._check_owner()
        raw = Settings.getRaw(self.id, str(key))
        if raw is None:
            return default
        try:
            return json.loads(str(raw))
        except (TypeError, ValueError):
            return default

    def set_setting(self, key, value):
        self._check_owner()
        if value is not None and type(value) not in (str, bool, int, float):
            raise TypeError("Setting value must be a string, number, boolean or None")
        Settings.putRaw(self.id, str(key), json.dumps(value, allow_nan=False))

    def add_request_hook(self, name, match_substring=False, priority=0):
        self._check_owner()
        require(self, "network.requests")
        Hooks.addRequestHook(self.id, str(name), bool(match_substring), int(priority),
                             callable(getattr(self, "pre_request_hook", None)),
                             callable(getattr(self, "post_request_hook", None)))

    def remove_request_hook(self, name):
        self._check_owner()
        Hooks.removeRequestHook(self.id, str(name))

    def add_update_hook(self, name, match_substring=False, priority=0):
        self._check_owner()
        require(self, "updates.read")
        Hooks.addUpdateHook(self.id, str(name), bool(match_substring), int(priority))

    def add_updates_hook(self, name, match_substring=False, priority=0):
        self._check_owner()
        require(self, "updates.read")
        Hooks.addUpdatesHook(self.id, str(name), bool(match_substring), int(priority))

    def add_on_send_message_hook(self, priority=0):
        self._check_owner()
        require(self, "messages.send")
        Hooks.addSendMessageHook(self.id, int(priority))

    def add_on_new_message_hook(self, priority=0):
        self._add_message_hook(0, priority)

    def add_on_message_edited_hook(self, priority=0):
        self._add_message_hook(1, priority)

    def add_on_messages_deleted_hook(self, priority=0):
        self._add_message_hook(2, priority)

    def _add_message_hook(self, kind, priority):
        self._check_owner()
        require(self, "updates.read")
        Hooks.addMessageHook(self.id, kind, int(priority))

    def add_menu_item(self, item):
        self._check_owner()
        require(self, "ui.menu")
        if not isinstance(item, MenuItem):
            raise TypeError("Expected a MenuItem")
        if item.key is None:
            self._menu_sequence += 1
            item.key = "menu_" + str(self._menu_sequence)
        item.key = str(item.key)
        self._menus[item.key] = item
        Menus.add(self.id, item.key, int(item.menu_type), item.text,
                  item.subtext, item.icon, item.priority, item.condition is not None)
        return item

    def remove_menu_item(self, item):
        self._check_owner()
        key = item.key if isinstance(item, MenuItem) else str(item)
        self._menus.pop(key, None)
        Menus.remove(self.id, key)

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event):
        pass

    def create_settings(self):
        return []


from . import client  # noqa: E402
from .client import AiError, message_to_dict  # noqa: E402,F401
