"""Crash guard for plugin dynamic_proxy classes (0.2.7). Private to the host.

Java calls the methods of a plugin's dynamic_proxy object directly (click listeners, Runnables, callbacks). An
exception raised there used to travel back into the calling Java code as a PyException and crash the app. The
host replaces java.dynamic_proxy with a version whose classes wrap every interface method: an exception is logged
to the plugin log (exception class and method name only, no data) and a safe value for the Java return type is
returned instead (void -> None, boolean -> False, numbers -> 0, char -> NUL, objects -> None). The wrapper also
runs the method inside its plugin's context, so zephyr APIs work from Java-initiated calls, and skips the call
once the plugin is unloaded.
"""
import functools

from zephyr import _state

PROXY_BASE_NAME = "_chaquopy_proxy"
_PRIMITIVE_DEFAULTS = {
    "void": None, "boolean": False, "byte": 0, "short": 0, "int": 0, "long": 0,
    "float": 0.0, "double": 0.0, "char": "\0",
}
_meta_cache = {}


def _return_defaults(implements):
    """{method name: safe return value} for every method declared by the interfaces."""
    out = {}
    for iface in implements:
        try:
            methods = iface.getClass().getMethods()
        except Exception:
            continue
        for m in methods:
            name = str(m.getName())
            if name in out:
                continue
            out[name] = _PRIMITIVE_DEFAULTS.get(str(m.getReturnType().getName()))
    return out


def _report(plugin_id, method, exc):
    try:
        from zephyr._bridge import Runtime
        Runtime.logProxyError(str(plugin_id or ""), str(method), type(exc).__name__)
    except Exception:
        pass


def _guard(fn, method, default, plugin_id):
    @functools.wraps(fn)
    def guarded(*args, **kwargs):
        plugin = _state.plugins.get(plugin_id) if plugin_id else None
        if plugin_id and plugin is None:
            return default  # plugin unloaded: its code must not run any more
        token = None
        if plugin is not None and _state.current.get() is not plugin:
            token = _state.current.set(plugin)
        try:
            return fn(*args, **kwargs)
        except BaseException as exc:  # nothing may reach the Java caller
            _report(plugin_id, method, exc)
            return default
        finally:
            if token is not None:
                _state.current.reset(token)
    guarded._zephyr_guarded = True
    return guarded


def _safe_meta(meta):
    cached = _meta_cache.get(meta)
    if cached is not None:
        return cached

    class SafeProxyClass(meta):
        def __new__(metacls, cls_name, bases, cls_dict):
            if cls_name != PROXY_BASE_NAME and bases:
                implements = getattr(bases[0], "implements", ())
                defaults = _return_defaults(implements)
                current = _state.current.get()
                plugin_id = getattr(current, "id", None)
                for name, value in list(cls_dict.items()):
                    if name in defaults and callable(value) and not getattr(value, "_zephyr_guarded", False):
                        cls_dict[name] = _guard(value, name, defaults[name], plugin_id)
            return super().__new__(metacls, cls_name, bases, cls_dict)

    SafeProxyClass.__name__ = meta.__name__
    _meta_cache[meta] = SafeProxyClass
    return SafeProxyClass


def install(java_module):
    """Replaces java.dynamic_proxy once; harmless when Chaquopy is not present (tests)."""
    original = getattr(java_module, "dynamic_proxy", None)
    if original is None or getattr(original, "_zephyr_safe", False):
        return

    def dynamic_proxy(*implements):
        base = original(*implements)  # validates the interfaces exactly like Chaquopy
        meta = _safe_meta(type(base))
        return meta(base.__name__, (), {"implements": base.implements})

    dynamic_proxy.__doc__ = original.__doc__
    dynamic_proxy._zephyr_safe = True
    java_module.dynamic_proxy = dynamic_proxy
