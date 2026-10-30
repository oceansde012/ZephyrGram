"""Private Java/Chaquopy dispatcher. Never imported by plugin code as its API."""
import json
import sys
import types
from pathlib import Path
from zephyr import BasePlugin, HookResult, _state
from zephyr._bridge import Runtime

_HOOKS = frozenset(("pre_request_hook", "post_request_hook", "on_update_hook",
                    "on_updates_hook", "on_send_message_hook"))


def load_plugin(plugin_id, path, data_dir):
    unload_plugin(plugin_id)
    module_name = "_zephyr_plugin_" + plugin_id
    module = types.ModuleType(module_name)
    module.__file__ = path
    # A temporary owner gives top-level code its manifest's permissions, too.
    owner = BasePlugin()
    owner.id = plugin_id
    _state.plugins[plugin_id] = owner
    token = _state.current.set(owner)
    sys.modules[module_name] = module
    try:
        source = Path(path).read_bytes()
        if len(source) > 512 * 1024:
            raise ValueError("Plugin is larger than 512 KB")
        exec(compile(source.decode("utf-8"), path, "exec"), module.__dict__)
        plugin_class = module.__dict__.get("Plugin")
        if not isinstance(plugin_class, type) or not issubclass(plugin_class, BasePlugin):
            raise TypeError("Plugin must subclass zephyr.BasePlugin")
        if module.__dict__.get("__id__") != plugin_id:
            raise ValueError("Plugin id does not match the file")
        plugin = plugin_class()
        # Host-owned fields must exist even if a plugin did not call super().__init__.
        BasePlugin.__init__(plugin)
        plugin.id = plugin_id
        plugin.name = module.__dict__.get("__name__", plugin_id)
        plugin.version = module.__dict__.get("__version__", "0.0.0")
        plugin.data_dir = str(data_dir)
        _state.plugins[plugin_id] = plugin
        _state.current.set(plugin)
        plugin.on_plugin_load()
    except BaseException:
        _state.plugins.pop(plugin_id, None)
        sys.modules.pop(module_name, None)
        raise
    finally:
        _state.current.reset(token)


def unload_plugin(plugin_id):
    plugin = _state.plugins.get(plugin_id)
    try:
        if plugin is not None:
            token = _state.current.set(plugin)
            try:
                plugin.on_plugin_unload()
            finally:
                _state.current.reset(token)
    finally:
        _state.plugins.pop(plugin_id, None)
        sys.modules.pop("_zephyr_plugin_" + plugin_id, None)


def _context(account, dialog_id, message, fragment):
    return {"account": account, "dialog_id": dialog_id, "message": message,
            "fragment": fragment, "context": Runtime.context()}


def invoke(plugin_id, method, arguments):
    plugin = _state.plugins.get(plugin_id)
    if plugin is None:
        return None
    token = _state.current.set(plugin)
    try:
        args = tuple(arguments)
        if method in ("__menu_condition__", "__menu_click__"):
            key, account, dialog_id, message, fragment = args
            item = plugin._menus.get(str(key))
            if item is None:
                return False if method == "__menu_condition__" else None
            ctx = _context(account, dialog_id, message, fragment)
            if method == "__menu_condition__":
                return item.condition is None or bool(item.condition(ctx))
            return item.on_click(ctx)
        if method == "__create_settings__":
            from zephyr.ui import _Item
            items = plugin.create_settings() or []
            plugin._settings = {}
            data = []
            for index, item in enumerate(items):
                if not isinstance(item, _Item):
                    raise TypeError("create_settings must return zephyr.ui items")
                key = str(getattr(item, "key", None) or "item_" + str(index))
                if key in plugin._settings:
                    raise ValueError("Duplicate setting key: " + key)
                plugin._settings[key] = item
                data.append(item._data(key))
            return json.dumps(data, allow_nan=False)
        if method == "__settings_event__":
            key, event, value = args
            item = plugin._settings.get(str(key))
            if event == "pick":
                callback = getattr(item, "on_pick", None) or getattr(item, "on_resolved", None)
                if callback:
                    data = json.loads(str(value))
                    return callback(int(data["id"]), str(data.get("title") or ""))
                return None
            callback = getattr(item, "on_change" if event == "change" else "on_click", None)
            if callback:
                return callback(json.loads(str(value))) if event == "change" else callback()
            return None
        callback = getattr(plugin, method, None)
        if callback is None:
            return None
        value = callback(*args)
        if method in _HOOKS:
            if value is None:
                return None
            if not isinstance(value, HookResult):
                raise TypeError("Hook callback must return HookResult or None")
            return value._java()
        return value
    finally:
        _state.current.reset(token)


def run_callback(plugin_id, callback, arguments):
    plugin = _state.plugins.get(plugin_id)
    if plugin is None:
        return None
    token = _state.current.set(plugin)
    try:
        return callback(*tuple(arguments))
    finally:
        _state.current.reset(token)
