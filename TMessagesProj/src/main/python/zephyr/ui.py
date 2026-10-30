"""Declarative settings; callbacks stay in Python, display data is serialized."""
class _Item:
    def _data(self, key):
        value = {"type": self.type, "key": key, "text": self.text}
        for name in ("subtext", "default", "options", "hint", "icon", "accent", "red", "multiline"):
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


class Divider(_Item):
    type = 5
    text = ""
