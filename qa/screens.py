#!/usr/bin/env python3
"""Screenshot matrix of ZephyrGram settings screens WITHOUT login (QA build with QaScreenLauncher).
languages x widths x font scales x themes x screens, scrolled to the end. Writes JPEGs, results.json, contact sheets."""
import argparse, hashlib, io, itertools, json, os, re, subprocess, sys, time

PKG = "org.zephyrgram.messenger"
SCREENS = [
    ("main", "ZephyrSettingsActivity"), ("appearance", "ZephyrAppearanceSettingsActivity"),
    ("chats", "ZephyrChatsSettingsActivity"), ("media", "ZephyrMediaSettingsActivity"),
    ("privacy", "ZephyrPrivacySettingsActivity"), ("history", "ZephyrHistorySettingsActivity"),
    ("filters", "ZephyrFiltersActivity"), ("plugins", "ZephyrPluginsActivity"),
    ("autoreply", "ZephyrAutoReplyChatsActivity"), ("assistant", "ZephyrAssistantSettingsActivity"),
    ("push", "ZephyrPushSettingsActivity"), ("updates", "ZephyrUpdatesSettingsActivity"),
    ("diagnostics", "ZephyrDiagnosticsActivity"), ("tg_root", "SettingsActivity"),
    ("tg_privacy", "PrivacySettingsActivity"),
]
LANGS = ["uk", "ru", "en"]
WIDTHS = [360, 411]
FONTS = ["1.0", "1.3"]
DEADLINE = time.time() + 100 * 60


def sh(cmd, timeout=60):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout).stdout


def adb(a, timeout=60):
    return sh("adb " + a, timeout)


def log(m):
    print(time.strftime("%H:%M:%S"), m, flush=True)


def shot_bytes():
    return subprocess.run("adb exec-out screencap -p", shell=True, capture_output=True, timeout=30).stdout


def save_jpeg(png, path):
    from PIL import Image
    im = Image.open(io.BytesIO(png)).convert("RGB")
    im.thumbnail((540, 1200))
    im.save(path, "JPEG", quality=82)
    return path


def set_display(width_dp, font):
    # Pixel 6 profile is 1080x2400; dp width = px / density * 160
    adb("shell wm size 1080x2400")
    adb(f"shell wm density {int(1080 * 160 / width_dp)}")
    adb(f"shell settings put system font_scale {font}")
    time.sleep(1.5)


def crashed():
    lc = adb("logcat -d -b crash,main -v time *:E", 60)
    alive = adb(f"shell pidof {PKG}").strip()
    bad = None
    if "FATAL EXCEPTION" in lc:
        m = re.search(r"FATAL EXCEPTION.*?\n((?:.*\n){0,8})", lc)
        bad = "FATAL: " + (m.group(1).strip().replace("\n", " | ")[:400] if m else "")
    elif "QaScreenLauncher" in lc and "cannot open screen" in lc:
        bad = "cannot open screen (constructor/exception)"
    elif not alive:
        bad = "process died"
    adb("logcat -c")
    return bad


def capture(cfg, key, cls, out_dir, results, lang, theme):
    adb(f"shell am force-stop {PKG}")
    adb("logcat -c")
    adb(f"shell am start -n {PKG}/org.telegram.ui.LaunchActivity --es qa_screen {cls} --es qa_lang {lang} --es qa_theme {theme}")
    time.sleep(5)
    bad = crashed()
    frames = []
    prev = None
    for i in range(8):
        png = shot_bytes()
        h = hashlib.md5(png).hexdigest()
        if h == prev:
            break
        prev = h
        path = os.path.join(out_dir, f"{key}_{i}.jpg")
        save_jpeg(png, path)
        frames.append(path)
        if bad:
            break
        adb("shell input swipe 540 1900 540 700 450")
        time.sleep(1.0)
        bad = crashed() or bad
        if bad:
            png = shot_bytes(); path = os.path.join(out_dir, f"{key}_after_crash.jpg"); save_jpeg(png, path); frames.append(path)
            break
    results.append(dict(config=cfg, screen=key, cls=cls, frames=len(frames), error=bad))
    qa_log = [l for l in adb("logcat -d -s QaScreenLauncher:I").splitlines() if "theme" in l]
    if qa_log:
        log(f"{cfg} {key}: hook {qa_log[-1].strip()[-90:]}")
    log(f"{cfg} {key}: {len(frames)} frames {'ERROR ' + bad if bad else 'ok'}")
    return frames


def ghost_open(cfg, out_dir, lang, theme, results):
    """Open the privacy screen and expand the ghost-mode group (tap the 'N/4' title), screenshot before and after."""
    adb(f"shell am force-stop {PKG}"); adb("logcat -c")
    adb(f"shell am start -n {PKG}/org.telegram.ui.LaunchActivity --es qa_screen ZephyrPrivacySettingsActivity --es qa_lang {lang} --es qa_theme {theme}")
    time.sleep(5)
    adb("shell uiautomator dump /sdcard/u.xml")
    xml = adb("shell cat /sdcard/u.xml")
    m = re.search(r'text="[^"]*\d/4[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    frames = []
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2; y = (int(m.group(2)) + int(m.group(4))) // 2
        adb(f"shell input tap {x} {y}"); time.sleep(1.5)
        log(f"{cfg} ghost: tapped {x},{y}")
    else:
        log(f"{cfg} ghost: N/4 title not found in uiautomator dump")
    for i in range(3):
        path = os.path.join(out_dir, f"ghost_open_{i}.jpg"); save_jpeg(shot_bytes(), path); frames.append(path)
        adb("shell input swipe 540 1900 540 700 450"); time.sleep(1.0)
    results.append(dict(config=cfg, screen="ghost_open", cls="ZephyrPrivacySettingsActivity", frames=len(frames), error=crashed()))
    return frames


def contact_sheet(cfg_dir, cfg, entries):
    from PIL import Image, ImageDraw
    thumbs = [(k, p[0]) for k, p in entries if p]
    if not thumbs:
        return
    cols, tw, th = 5, 216, 480
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (tw + 8) + 8, rows * (th + 30) + 8), (30, 30, 30))
    d = ImageDraw.Draw(sheet)
    for i, (k, p) in enumerate(thumbs):
        im = Image.open(p).convert("RGB"); im.thumbnail((tw, th))
        x = 8 + (i % cols) * (tw + 8); y = 8 + (i // cols) * (th + 30)
        sheet.paste(im, (x, y + 18)); d.text((x, y), f"{cfg} {k}", fill=(230, 230, 230))
    sheet.save(os.path.join(cfg_dir, f"contact_{cfg}.jpg"), "JPEG", quality=85)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="screens-out")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    adb("wait-for-device")
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb(f"shell settings put global {k} 0")
    log("install: " + adb(f"install -r -g {a.apk}", 300).strip()[:80])
    results = []
    configs = [(l, w, f, "dark") for l, w, f in itertools.product(LANGS, WIDTHS, FONTS)]
    configs += [("uk", 411, "1.0", "light"), ("ru", 360, "1.3", "light")]  # light theme: main, appearance, chats
    mode = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens_mode.txt")).read().strip() if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "screens_mode.txt")) else "full"
    v210 = mode == "v210"
    if v210:  # 0.2.10: five screens, dark+light, uk+ru, 360dp/1.3 and 411dp/1.0, plus the expanded ghost group
        configs = [(l, w, f, t) for t in ("dark", "light") for l in ("uk", "ru") for w, f in ((360, "1.3"), (411, "1.0"))]
    if mode == "quick":  # hook check: one screen, dark and light
        configs = [("uk", 411, "1.0", "dark"), ("uk", 411, "1.0", "light")]
    for lang, w, font, theme in configs:
        if time.time() > DEADLINE:
            log("deadline reached, stopping"); break
        cfg = f"{lang}_{w}dp_f{font}_{theme}"
        cdir = os.path.join(a.out, cfg); os.makedirs(cdir, exist_ok=True)
        set_display(w, font)
        entries = []
        screens = SCREENS if theme == "dark" else SCREENS[:3:1] if False else [x for x in SCREENS if x[0] in ("main", "appearance", "chats")]
        if v210:
            screens = [x for x in SCREENS if x[0] in ("main", "privacy", "chats", "appearance", "history")]
        if mode == "quick":
            screens = [x for x in SCREENS if x[0] in ("main", "appearance")]
        for key, cls in screens:
            entries.append((key, capture(cfg, key, cls, cdir, results, lang, theme)))
        if v210:
            entries.append(("ghost_open", ghost_open(cfg, cdir, lang, theme, results)))
        contact_sheet(cdir, cfg, entries)
        json.dump(results, open(os.path.join(a.out, "results.json"), "w"), ensure_ascii=False, indent=1)
    adb("shell wm size reset"); adb("shell wm density reset"); adb("shell settings put system font_scale 1.0")
    errs = [r for r in results if r["error"]]
    lines = ["## ZephyrGram screens (no login)", "", f"Shots: {sum(r['frames'] for r in results)}, screens run: {len(results)}, errors: {len(errs)}", ""]
    seen = {}
    for r in errs:
        seen.setdefault((r["screen"], r["error"][:120]), []).append(r["config"])
    for (s, e), cs in seen.items():
        lines.append(f"- **{s}**: {e} ({len(cs)} configs, e.g. {cs[0]})")
    open(os.path.join(a.out, "report.md"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
