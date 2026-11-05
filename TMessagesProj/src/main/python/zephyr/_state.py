"""Runtime-owned plugin state. This API is not a Python security sandbox."""
from contextvars import ContextVar

current = ContextVar("zephyr_plugin", default=None)
plugins = {}


def owner():
    plugin = current.get()
    if plugin is None or plugins.get(plugin.id) is not plugin:
        raise RuntimeError("This API must run inside an active plugin callback")
    return plugin


def bound_callback(fn):
    plugin = owner()

    def wrapped(*args):
        if plugins.get(plugin.id) is not plugin:
            return None
        token = current.set(plugin)
        try:
            return fn(*args)
        finally:
            current.reset(token)
    return wrapped
