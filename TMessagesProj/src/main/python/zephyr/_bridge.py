from java import jclass

Runtime = jclass("org.telegram.messenger.zephyr.plugins.PluginRuntime").INSTANCE
Hooks = jclass("org.telegram.messenger.zephyr.plugins.PluginHooks")
Menus = jclass("org.telegram.messenger.zephyr.plugins.PluginMenus")
Settings = jclass("org.telegram.messenger.zephyr.plugins.PluginSettingsModel")
Result = jclass("org.telegram.messenger.zephyr.plugins.PluginHooks$Result")


def require(plugin, permission):
    try:
        Runtime.requirePermission(plugin.id, permission)
    except Exception as exc:
        raise PermissionError(str(exc)) from exc
ChatApi = jclass("org.telegram.messenger.zephyr.plugins.PluginChatApi")
