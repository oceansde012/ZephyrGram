"""Declarative settings; callbacks stay in Python, display data is serialized."""
class _Item:
    def _data(self, key):
        value = {"type": self.type, "key": key, "text": self.text}
        for name in ("subtext", "default", "options", "hint", "icon", "accent", "red", "multiline", "kind", "multiple"):
            if hasattr(self, name):
                value[name] = getattr(self, name)
        return value


class Header(_Item):
    type = 0
    def __init__(self, text):
        self.text = str(text)


class Switch(_Item):
    type = 1
    def __init__(self, key, text, default=False, subtext=None, on_change=None):
        self.key, self.text = str(key), str(text)
        self.default, self.subtext, self.on_change = bool(default), subtext, on_change


class Input(_Item):
    type = 2
    def __init__(self, key, text, default="", subtext=None, on_change=None, hint=None, multiline=False):
        self.key, self.text = str(key), str(text)
        self.default, self.subtext, self.on_change = str(default), subtext, on_change
        self.hint, self.multiline = hint, bool(multiline)


class Selector(_Item):
    type = 3
    def __init__(self, key, text, items, default=0, subtext=None, on_change=None):
        self.key, self.text = str(key), str(text)
        self.options = [str(item) for item in items]
        self.default, self.subtext, self.on_change = int(default), subtext, on_change


class Text(_Item):
    type = 4
    def __init__(self, text, on_click=None, key=None, subtext=None):
        self.text, self.on_click, self.key = str(text), on_click, key
        self.subtext = subtext


class DialogPicker(_Item):
    """Button that opens Telegram's own chat picker. on_pick(dialog_id, title) runs on the UI thread."""
    type = 6
    def __init__(self, key, text, on_pick=None, subtext=None, *, on_pick_context=None):
        if (callable(on_pick) + callable(on_pick_context)) != 1:
            raise ValueError("Provide exactly one picker callback")
        self.key, self.text, self.on_pick, self.subtext = str(key), str(text), on_pick, subtext
        self.on_pick_context = on_pick_context


class UsernameInput(_Item):
    """Button that asks for a @username / t.me link, resolves it (cache, then network) and calls
    on_resolved(dialog_id, title) on the UI thread; an error is shown if nothing is found."""
    type = 7
    def __init__(self, key, text, on_resolved=None, subtext=None, hint=None, *, on_resolved_context=None):
        if (callable(on_resolved) + callable(on_resolved_context)) != 1:
            raise ValueError("Provide exactly one username callback")
        self.key, self.text, self.on_resolved, self.subtext = str(key), str(text), on_resolved, subtext
        self.on_resolved_context = on_resolved_context
        if hint is not None:
            self.hint = str(hint)


class Page(_Item):
    """Navigation row (settings pages, 0.2.2+): opens a sub-screen that shows create_settings(page=<key>).
    Only use it when client.SETTINGS_PAGES is true; `icon` is a name from the app's icon set (e.g. "general",
    "style", "timing", "limits", "commands", "memory", "prompts", "test", "log", "chats")."""
    type = 8
    def __init__(self, key, text, subtext=None, icon=None):
        self.key, self.text, self.subtext, self.icon = str(key), str(text), subtext, icon


class FilePicker(_Item):
    """Button that opens the system file chooser (client.FILE_PICKER, 0.2.7+). The chosen files are copied into this
    plugin's data_dir/picked/ (at most 20 files, 50 MB each) and on_pick(paths) runs on the UI thread with a list of
    paths relative to data_dir, which client.send_media accepts as they are. kind: "any" | "image" | "video" | "audio"."""
    type = 9
    def __init__(self, key, text, on_pick, subtext=None, kind="any", multiple=False):
        if not callable(on_pick):
            raise ValueError("on_pick must be callable")
        kind = str(kind).lower()
        if kind not in ("any", "image", "video", "audio"):
            raise ValueError("kind must be any, image, video or audio")
        self.key, self.text, self.on_pick, self.subtext = str(key), str(text), on_pick, subtext
        self.kind, self.multiple = kind, bool(multiple)


class Divider(_Item):
    type = 5
    text = ""

