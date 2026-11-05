"""Private Java/Chaquopy dispatcher. Never imported by plugin code as its API."""
import json
import sys
import types
from pathlib import Path
from zephyr import BasePlugin, HookResult, _state
from zephyr._bridge import Runtime

_HOOKS = frozenset(("pre_request_hook", "post_request_hook", "on_update_hook",
                    "on_updates_hook", "on_send_message_hook"))
_INLINE_RESULT_SEND = "on_inline_result_send"


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
        if len(source) > 2 * 1024 * 1024:
            raise ValueError("Plugin is larger than 2 MB")
        exec(_compiled(source, path), module.__dict__)
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


def _compiled(source, path):
    """Code object of a plugin, cached next to its file by the source's SHA-256 and the interpreter's magic.

    compile() is one C call that holds the GIL for its whole run (a 500 KB plugin takes hundreds of ms on a phone),
    so every UI-thread plugin call (send hook, menu condition, settings tap) waited for it at app start and after
    each plugin update. Loading the cached bytecode takes a few ms. Any cache problem falls back to compile().
    """
    import hashlib
    import importlib.util
    import marshal
    import os
    header = importlib.util.MAGIC_NUMBER + hashlib.sha256(source).digest()
    cache = Path(str(path) + ".pyc")
    try:
        data = cache.read_bytes()
        if data[:len(header)] == header:
            code = marshal.loads(data[len(header):])
            if isinstance(code, types.CodeType) and code.co_filename == str(path):
                return code
    except Exception:
        pass
    code = compile(source.decode("utf-8"), path, "exec")
    try:
        tmp = Path(str(cache) + ".tmp")
        tmp.write_bytes(header + marshal.dumps(code))
        os.replace(str(tmp), str(cache))
    except Exception:
        pass
    return code


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
            page = str(args[0]) if args and args[0] else None
            items = (plugin.create_settings(page) if page else plugin.create_settings()) or []
            built = {}
            data = []
            for index, item in enumerate(items):
                if not isinstance(item, _Item):
                    raise TypeError("create_settings must return zephyr.ui items")
                key = str(getattr(item, "key", None) or "item_" + str(index))
                if key in built:
                    raise ValueError("Duplicate setting key: " + key)
                built[key] = item
                data.append(item._data(key))
            # Always merge, never replace: the main screen stays open under its sub-screens and rebuilds on its
            # own (a chat switch, a plugin update). Replacing the map there dropped the callbacks of the
            # sub-screens still on top of it, so their buttons, switches and pickers silently did nothing.
            # A key rebuilt later takes the newest callback; a row that is gone is never shown, so its old
            # callback can never fire.
            plugin._settings.update(built)
            return json.dumps(data, allow_nan=False)
        if method == "__settings_event__":
            key, event, value = args
            item = plugin._settings.get(str(key))
            if item is None:
                # Should not happen any more; say so in the plugin log instead of dropping the tap silently.
                try:
                    plugin.log("Settings: no callback for '%s' (%s)" % (key, event))
                except Exception:
                    pass
                return None
            if event == "pick":
                callback = getattr(item, "on_pick_context", None) or getattr(item, "on_resolved_context", None)
                if callback:
                    data = json.loads(str(value))
                    context = data.get("context")
                    if (not isinstance(context, dict) or type(context.get("account")) is not int
                            or context["account"] < 0 or not isinstance(context.get("ownerId"), str)
                            or not context["ownerId"].isdigit() or not 0 < int(context["ownerId"]) < (1 << 63)):
                        return None  # Old native host/event has no verified provenance: never guess.
                    return callback(int(data["id"]), str(data.get("title") or ""),
                                    {"account": context["account"], "ownerId": context["ownerId"]})
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
        if method == _INLINE_RESULT_SEND:
            account, info = args
            value = callback(int(account), json.loads(str(info)))
            if value is None:
                return None
            if not isinstance(value, HookResult):
                raise TypeError("on_inline_result_send must return an InlineAction value (HookResult) or None")
            return value._java()
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

