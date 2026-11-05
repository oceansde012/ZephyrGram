# Plugin API: inline-result hook and send_media

Additions to plugin API v1 (`client.api_version()` stays 1). Feature-detect with
`getattr(client, "INLINE_RESULT_HOOK", False)` and `getattr(client, "SEND_MEDIA", False)`.

| Permission    | Grants |
|---------------|--------|
| `inline.hook` | `add_inline_result_hook` / `on_inline_result_send` |
| `media.send`  | `client.send_media` (an `https://` source also needs `internet`) |

## on_inline_result_send

```python
from zephyr import BasePlugin, InlineAction, client

__permissions__ = ["inline.hook", "media.send"]

class Plugin(BasePlugin):
    def on_plugin_load(self):
        self.add_inline_result_hook(priority=0)

    def on_inline_result_send(self, account, info):
        if info["can_send_as_own"]:
            return InlineAction.send_as_own()          # same media/text, no "via @bot"
        if info["file_path"]:
            client.send_media(info["dialog_id"], info["file_path"], caption=info["caption"],
                              reply_to=info["reply_to"] or None)
            return InlineAction.cancel()
        return None                                     # send unchanged
```

Runs on the UI thread when the user picks a result from the inline-bot panel (before
`messages.sendInlineBotResult`). Keep it fast; do network work with `client.send_media` /
`client.run_in_bg`. Plugins run by priority; the first `cancel` or `send_as_own` wins.

`info` keys: `id`, `query_id`, `type` (gif, photo, video, article, ...), `kind` (`media` = hosted by
Telegram, `external` = bot URL, `none`), `message_kind` (`media_auto`, `text`, `geo`, `venue`,
`contact`, `invoice`, `webpage`, `rich`, `other`), `title`, `description`, `url`, `content_url`,
`content_mime`, `content_size`, `thumb_url`, `document_id`, `document_mime`, `file_name`, `size`,
`photo_id`, `width`, `height`, `duration`, `file_path` (local cached file or `None`), `caption`,
`has_entities`, `has_reply_markup`, `bot_id`, `bot_username`, `dialog_id`, `reply_to`, `top_msg_id`,
`scheduled`, `secret`, `can_send_as_own`. All strings from the bot are untrusted.

Return values:

- `None` / `InlineAction.keep()` — send unchanged.
- `InlineAction.cancel()` — send nothing.
- `InlineAction.send_as_own(caption=None)` — send the same media or text as an ordinary message: no
  `via_bot`, no bot keyboard, not reported to the bot as a chosen result. `caption=None` keeps the
  bot caption and formatting, `""` removes it (media), any other text replaces it (plain, max 4096).
  Honoured only when `can_send_as_own` is true: hosted photo/document, text results, external
  non-photo content already in the local cache. Never for games, geo/venue/contact/invoice results or
  secret chats. External photos are excluded because resending would refetch the bot URL from the
  user's IP. Otherwise the result is sent unchanged and the plugin log says why.

Only the inline-bot panel in a chat is hooked; the attach menu web image search is not.

## client.send_media

```python
client.send_media(dialog_id, source, caption=None, reply_to=None, kind=None, account=None,
                  on_done=None, max_bytes=None, timeout=30)
```

Sends an ordinary outgoing message of the user through Telegram's media pipeline.

- `source`: a path inside the plugin `data_dir` (relative paths are resolved there) or inside
  Telegram's media/cache directories, or an `https://` URL. URLs are streamed into
  `data_dir/send_media` (public hosts only, also on redirects, at most `max_bytes`, default and
  maximum 50 MB). Files older than 6 hours there are removed.
- `kind`: `photo`, `video`, `gif`, `document` or `None`/`auto` (by extension). An explicit kind must
  match the file header (JPEG/PNG, GIF/MP4, MP4/WebM). In auto mode a mismatch falls back to
  `document`. An MP4 `gif` is sent as a silent animation.
- Limits: photo 10 MB, gif 20 MB, video/document 100 MB. Secret chats are refused.
- It does not block. `on_done(ok, error)` runs on a worker thread. Without `on_done`, errors go to
  the plugin log. Queued does not mean delivered. The message passes through `on_send_message` hooks
  like one the user wrote, and ghost mode still applies.

Not implemented: `client.delete_messages`, and a "hide via @bot in the chat bubble" option. The
via label is drawn in many places in `ChatMessageCell`, so hiding it is not a trivial change.

Tests: `python3 tools/test_inline_media.py`, `sh tools/run_plugin_media_test.sh`.
