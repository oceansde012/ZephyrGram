#!/usr/bin/env python3
"""ZephyrGram settings tour for an Android emulator (adb + uiautomator dump, stdlib only).

1. installs the QA APK, logs in on the Telegram TEST backend (number 99966 X YYYY, code = X repeated 5 times)
2. opens Settings -> ZephyrGram and crawls every screen: presses every item, toggles every switch twice,
   opens and dismisses dialogs, drags sliders, recurses into sub-screens
3. after every action: process alive? FATAL EXCEPTION / ANR in logcat? screenshot per screen
4. writes report.md, results.json, screenshots/, logcat files into --out

Destructive items (reset, log out, delete, ...) are never pressed; they are listed as "skipped".
Secrets are never printed (the test number/code are public Telegram test-server values).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "org.zephyrgram.messenger"
DESTRUCTIVE = re.compile(
    r"log ?out|sign ?out|выйти|выход|delete|удал|reset|сброс|clear|очист|deactivat|terminat|заверш|erase|"
    r"remove|убрать|disconnect|отключить все|wipe", re.I)
DEADLINE = time.time() + 85 * 60

rows = []          # (screen, item, action, result, note)
out_dir = "qa-out"
shot_n = 0


def sh(cmd, timeout=60, check=False):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f"{cmd}: {r.stderr[:300]}")
    return r.stdout


def adb(args, timeout=60):
    return sh("adb " + args, timeout)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def add(screen, item, action, result, note=""):
    rows.append((screen, item, action, result, note))
    log(f"[{result}] {screen} | {item} | {action} {note}")


# ---------------------------------------------------------------- UI primitives
class Node:
    def __init__(self, e):
        a = e.attrib
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.cls = a.get("class", "")
        self.rid = a.get("resource-id", "")
        self.pkg = a.get("package", "")
        self.clickable = a.get("clickable") == "true"
        self.checkable = a.get("checkable") == "true"
        self.checked = a.get("checked") == "true"
        self.enabled = a.get("enabled") == "true"
        self.scrollable = a.get("scrollable") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        self.b = tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0)

    @property
    def cx(self): return (self.b[0] + self.b[2]) // 2
    @property
    def cy(self): return (self.b[1] + self.b[3]) // 2
    @property
    def w(self): return self.b[2] - self.b[0]
    @property
    def h(self): return self.b[3] - self.b[1]
    @property
    def label(self): return (self.text or self.desc or "").strip()
    def key(self): return f"{self.cls}|{self.rid}|{self.label}|{self.b[1] // 40}"


def dump():
    for _ in range(3):
        adb("shell uiautomator dump /sdcard/u.xml", 30)
        x = adb("exec-out cat /sdcard/u.xml", 30)
        if x.strip().startswith("<?xml"):
            try:
                root = ET.fromstring(x)
                return [Node(e) for e in root.iter("node")], x
            except ET.ParseError:
                pass
        time.sleep(0.7)
    return [], ""


def tap(n):
    adb(f"shell input tap {n.cx} {n.cy}")
    time.sleep(1.1)


def back():
    adb("shell input keyevent 4")
    time.sleep(1.0)


def b64shot(tag):
    """dump a (downscaled when possible) screenshot into the job log so it can be read without artifact access"""
    import base64
    raw = subprocess.run("adb exec-out screencap -p", shell=True, capture_output=True, timeout=30).stdout
    try:
        from PIL import Image  # noqa
        import io
        im = Image.open(io.BytesIO(raw)); im.thumbnail((360, 800))
        buf = io.BytesIO(); im.save(buf, "PNG", optimize=True); raw = buf.getvalue()
    except Exception:
        pass
    b = base64.b64encode(raw).decode()
    for i in range(0, len(b), 900):
        print(f"B64SHOT {tag} {i // 900} {b[i:i + 900]}", flush=True)
    print(f"B64SHOT {tag} END", flush=True)


def screenshot(name):
    global shot_n
    shot_n += 1
    safe = re.sub(r"[^\w.-]+", "_", name)[:60]
    path = os.path.join(out_dir, "screenshots", f"{shot_n:03d}_{safe}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run("adb exec-out screencap -p", shell=True, capture_output=True, timeout=30).stdout)


def texts(nodes):
    return {n.label for n in nodes if n.label and n.pkg == PKG}


def similarity(a, b):
    if not a and not b:
        return 1.0
    return len(a & b) / max(1, len(a | b))


def in_app(nodes):
    return any(n.pkg == PKG for n in nodes)


def find(nodes, pattern, exact=False):
    rx = re.compile(("^%s$" if exact else "%s") % pattern, re.I)
    for n in nodes:
        if rx.search(n.text or "") or rx.search(n.desc or ""):
            return n
    return None


# ---------------------------------------------------------------- logcat / process health
CRASH_RX = re.compile(r"FATAL EXCEPTION|ANR in " + re.escape(PKG) + r"|Process: " + re.escape(PKG) + r".*\n.*PID|"
                      r"Fatal signal \d+ .*" + re.escape(PKG), re.I)


def health(tag):
    """returns None if healthy else a short description; saves logcat on trouble."""
    alive = adb(f"shell pidof {PKG}").strip()
    lc = adb("logcat -d -b main,system,crash -v time *:E", 60)
    bad = None
    if "FATAL EXCEPTION" in lc or re.search(r"ANR in " + re.escape(PKG), lc):
        m = re.search(r"(FATAL EXCEPTION.*?\n(?:.*\n){0,12})", lc)
        bad = "CRASH/ANR: " + (m.group(1)[:600] if m else "see logcat")
    elif not alive:
        bad = "process died"
    if bad:
        safe = re.sub(r"[^\w.-]+", "_", tag)[:50]
        with open(os.path.join(out_dir, f"crash_{len(rows):04d}_{safe}.log"), "w") as f:
            f.write(adb("logcat -d -v time", 60))
    adb("logcat -c")
    return bad


# ---------------------------------------------------------------- login on test backend
def handle_system_dialogs(nodes):
    for n in nodes:
        if n.pkg in ("com.google.android.permissioncontroller", "com.android.permissioncontroller"):
            btn = [x for x in nodes if x.rid.endswith("permission_deny_button")
                   or x.rid.endswith("permission_deny_and_dont_ask_again_button")]
            if btn:
                tap(btn[0])
                return True
        if n.pkg == "android" and ("aerr" in n.rid or n.rid.endswith("alertTitle")):
            ok = find(nodes, r"^(Close app|Wait|OK)$")
            if ok:
                tap(ok)
                return True
    return False


def type_text(field, value, clear=True):
    """tap the field, delete exactly its current digits (never DEL on an empty phone field: Telegram then
    moves focus to the country code field and erases it), then type value."""
    tap(field)
    if clear:
        cur = re.sub(r"[^0-9A-Za-z]", "", field.text or "")
        if cur and not re.search(r"phone|номер|name|имя", field.text or "", re.I):
            adb("shell input keyevent KEYCODE_MOVE_END")
            adb("shell input keyevent " + " ".join(["KEYCODE_DEL"] * (len(cur) + 2)))
    adb(f"shell input text {value}")


def read_fields():
    nodes, _ = dump()
    edits = sorted([n for n in nodes if n.cls.endswith("EditText")], key=lambda n: n.b[0])
    if len(edits) < 2:
        return None, None, nodes
    return edits[0], edits[-1], nodes


def digits(x):
    return re.sub(r"\D", "", x or "")


def clear_all(phone):
    tap(phone)
    # DEL in an empty phone field bounces to the country code field, so this clears both
    adb("shell input keyevent KEYCODE_MOVE_END")
    adb("shell input keyevent " + " ".join(["KEYCODE_DEL"] * 30))


def enter_number(nodes, number):
    """Several strategies; each is verified by reading both fields back (digits of code+phone must be 999+number)."""
    want = "999" + number
    strategies = ["code999_then_phone", "full_in_phone", "full_in_code", "slow_phone"]
    for st in strategies:
        code_f, phone_f, nodes = read_fields()
        if not phone_f:
            return False
        clear_all(phone_f)
        code_f, phone_f, nodes = read_fields()
        if not phone_f:
            return False
        if st == "code999_then_phone":
            tap(code_f); adb("shell input text 999")
            code_f, phone_f, nodes = read_fields()
            tap(phone_f); adb(f"shell input text {number}")
        elif st == "full_in_phone":
            tap(phone_f); adb(f"shell input text {want}")
        elif st == "full_in_code":
            tap(code_f); adb(f"shell input text {want}")
        else:
            tap(code_f); adb("shell input text 999")
            code_f, phone_f, nodes = read_fields()
            tap(phone_f)
            for ch in number:
                adb(f"shell input text {ch}")
                time.sleep(0.4)
        time.sleep(1.5)
        code_f, phone_f, nodes = read_fields()
        got = digits(code_f.text) + digits(phone_f.text) if code_f else ""
        log(f"strategy {st}: code='{code_f.text if code_f else None}' phone='{phone_f.text if phone_f else None}' want {want}")
        if got == want:
            return True
    return False


def login(dc):
    number = f"66{dc}{int(time.time()) % 10000:04d}"  # 99966 X YYYY, country code 999
    code = str(dc) * 5
    log(f"login with test number +999 {number}")
    adb(f"shell monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(6)
    tb_done = False
    phone_done = False
    phone_tries = 0
    phone_submitted = False
    code_sent_at = 0
    for step in range(45):
        nodes, _ = dump()
        if step % 5 == 0:
            screenshot(f"login_{step}")
        if not nodes:
            log(f"login step {step}: empty dump")
            time.sleep(2)
            continue
        log(f"login step {step}: " + " | ".join(f"{n.pkg.split('.')[-1]}:{n.cls.split('.')[-1]}:{n.label}"
            for n in nodes if n.label or n.cls.endswith("EditText"))[:700])
        if handle_system_dialogs(nodes):
            continue
        if find(nodes, r"Open navigation menu|^Settings$|^Настройки$") and not find(nodes, r"Your phone|Ваш телефон|Phone number|Test Backend"):
            log("logged in")
            return True
        start = find(nodes, r"Start Messaging|Начать общение", exact=False)
        if start and not phone_done:
            tap(start)
            continue
        tb = find(nodes, r"Test Backend")
        if tb and not tb_done:
            tap(tb)
            tb_done = True
            time.sleep(10)  # switchBackend + country list from the test server
            continue
        edits = [n for n in nodes if n.cls.endswith("EditText")]
        on_phone_screen = bool(find(nodes, r"Your phone number|Ваш номер телефона|Please confirm your country code"))
        if find(nodes, r"Invalid phone number|Wrong format|Недействительный номер|Неверный формат"):
            phone_submitted = False
            ok = find(nodes, r"^(OK|ОК)$")
            if ok:
                tap(ok)
            continue
        if find(nodes, r"Enter code|Введите код|activation code|sent an SMS|sent you a code|sent the code"):
            phone_submitted = True
            if time.time() - code_sent_at > 25:
                ce = sorted([n for n in nodes if n.cls.endswith("EditText")], key=lambda n: (n.b[1], n.b[0]))
                if ce:
                    tap(ce[0])
                    attempt = getattr(login, "attempt", 0)
                    login.attempt = attempt + 1
                    candidates = [code, "11111", "33333", "22222", "12345"]
                    use = candidates[attempt % len(candidates)]
                    sz = re.search(r"(\d+)x(\d+)", adb("shell wm size"))
                    W, H = (int(sz.group(1)), int(sz.group(2))) if sz else (1080, 2400)
                    pos = {"1": (0.18, 0.758), "2": (0.50, 0.758), "3": (0.82, 0.758), "4": (0.18, 0.816), "5": (0.50, 0.816),
                           "6": (0.82, 0.816), "7": (0.18, 0.874), "8": (0.50, 0.874), "9": (0.82, 0.874), "0": (0.50, 0.932)}
                    log(f"typing code {use} on the on-screen keypad")
                    for ch in use:
                        adb(f"shell input tap {int(pos[ch][0] * W)} {int(pos[ch][1] * H)}")
                        time.sleep(0.5)
                    code_sent_at = time.time()
                    if not getattr(login, "shot_done", False):
                        login.shot_done = True
                        b64shot("after_code")
                    log("code typed digit by digit: " + " ".join(n.text for n in dump()[0] if n.cls.endswith("EditText")))
                    time.sleep(6)
                    lc = adb("logcat -d -v time", 60)
                    hits = [l for l in lc.splitlines() if re.search(r"tmessages|error|invalid|sign|code|rpc", l, re.I)
                            and not re.search(r"Choreographer|gralloc|SurfaceFlinger|OpenGL|Vulkan", l)]
                    log("logcat after code (%d hits): " % len(hits) + " || ".join(h[-160:] for h in hits[-6:]))
                    adb("logcat -c")
            time.sleep(5)
            continue
        if on_phone_screen and len(edits) >= 2 and not phone_submitted:
            if phone_done and phone_tries >= 3:
                time.sleep(3)
                continue
            nodes, _ = dump()
            if not enter_number(nodes, number):
                log("could not get '+999' + number into the fields, not submitting")
                phone_tries += 1
                phone_done = phone_tries >= 3
                continue
            if True:
                nodes, _ = dump()
                done_btn = find(nodes, r"^(Done|Готово|Next|Далее)$")
                if done_btn:
                    tap(done_btn)
                else:
                    adb("shell input keyevent 66")
                phone_done = True
                phone_submitted = True
                phone_tries += 1
                time.sleep(5)
            continue
        # confirmation of the number ("Is this the correct number?" / yes)
        yes = find(nodes, r"^(Yes|Да)$")
        if yes:
            tap(yes)
            continue
        # code entry
        if phone_done and not on_phone_screen and edits and find(nodes, r"sent|отправлен|Enter code|Введите код"):
            tap(edits[0])
            adb(f"shell input text {code}")
            time.sleep(5)
            continue
        # registration
        if find(nodes, r"Sign Up|Регистрация|First name|Имя"):
            fe = [n for n in nodes if n.cls.endswith("EditText")]
            if fe:
                type_text(fe[0], "QA")
                if len(fe) > 1:
                    type_text(fe[1], "Bot")
                adb("shell input keyevent 66")
                time.sleep(3)
            nxt = find(nodes, r"Done|Готово|Next|Далее")
            if nxt:
                tap(nxt)
            continue
        ok = find(nodes, r"^(OK|ОК|Not now|Не сейчас|Skip|Пропустить|Allow|Разрешить)$")
        if ok:
            tap(ok)
            continue
        time.sleep(2)
    screenshot("login_failed")
    return False


# ---------------------------------------------------------------- navigation
def open_settings():
    nodes, _ = dump()
    s = find(nodes, r"^Settings$|^Настройки$", exact=False)
    if not s:
        m = find(nodes, r"Open navigation menu")
        if m:
            tap(m)
            nodes, _ = dump()
            s = find(nodes, r"^Settings$|^Настройки$")
    if not s:
        return False
    tap(s)
    time.sleep(1.5)
    nodes, _ = dump()
    z = find(nodes, r"^ZephyrGram$")
    if not z:
        add("Settings root", "ZephyrGram entry", "find", "FAIL", "entry not found")
        return False
    tap(z)
    time.sleep(1.5)
    return True


def to_main_and_settings():
    adb(f"shell am force-stop {PKG}")
    adb(f"shell monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    time.sleep(6)
    return open_settings()


def replay(path):
    """re-open a screen after a crash: path is the list of labels pressed from the ZephyrGram hub."""
    if not to_main_and_settings():
        return False
    for lab in path:
        nodes, _ = dump()
        n = next((x for x in nodes if x.label == lab and x.pkg == PKG), None)
        if not n:
            return False
        tap(n)
    return True


# ---------------------------------------------------------------- crawler
def candidates(nodes):
    res = []
    seen = set()
    for n in nodes:
        if n.pkg != PKG or not n.enabled or n.w < 20 or n.h < 20:
            continue
        interactive = n.clickable or n.checkable or "SeekBar" in n.cls
        if not interactive:
            continue
        if n.label == "" and not (n.checkable or "SeekBar" in n.cls):
            # unlabeled clickable rows: label from the child text is not available in flat list; keep by geometry
            pass
        k = n.key()
        if k in seen:
            continue
        seen.add(k)
        res.append(n)
    return res


def crawl(screen, path, depth, processed_global):
    if depth > 4:
        return
    screenshot(screen)
    base_nodes, _ = dump()
    base_txt = texts(base_nodes)
    done = set()
    scrolls = 0
    guard = 0
    while guard < 120 and time.time() < DEADLINE:
        guard += 1
        nodes, _ = dump()
        if not in_app(nodes):
            back()
            continue
        cands = [n for n in candidates(nodes) if n.key() not in done]
        # skip top-bar back arrow
        cands = [n for n in cands if not re.search(r"^(Go back|Back|Назад|Navigate up)$", n.label, re.I)]
        if not cands:
            # scroll for more
            before = texts(nodes)
            adb("shell input swipe 540 1700 540 700 400")
            time.sleep(0.8)
            nodes2, _ = dump()
            scrolls += 1
            if similarity(before, texts(nodes2)) > 0.97 or scrolls > 14:
                break
            continue
        n = cands[0]
        done.add(n.key())
        item = n.label or f"<{n.cls.split('.')[-1]} @y{n.cy}>"
        if DESTRUCTIVE.search(item):
            add(screen, item, "press", "SKIP", "destructive item, not pressed")
            continue
        before_txt = texts(nodes)
        if "SeekBar" in n.cls:
            adb(f"shell input swipe {n.b[0] + 20} {n.cy} {n.b[2] - 20} {n.cy} 300")
            time.sleep(0.8)
            adb(f"shell input swipe {n.b[2] - 20} {n.cy} {n.b[0] + 20} {n.cy} 300")
            bad = health(f"{screen}/{item}")
            add(screen, item, "slider drag", "FAIL" if bad else "OK", bad or "")
            if bad and not recover(path):
                return
            continue
        was = n.checked
        tap(n)
        bad = health(f"{screen}/{item}")
        if bad:
            add(screen, item, "press", "FAIL", bad)
            screenshot(f"crash_{item}")
            if not recover(path):
                return
            continue
        after, _ = dump()
        if not in_app(after):
            add(screen, item, "press", "OK", "left the app (external intent), returned")
            back()
            nodes3, _ = dump()
            if not in_app(nodes3):
                to_main_and_settings()
                replay(path)
            continue
        sim = similarity(before_txt, texts(after))
        if n.checkable:
            # switch / checkbox: toggled?  press again to restore
            n_after = next((x for x in after if x.key() == n.key() or (x.checkable and abs(x.cy - n.cy) < 10)), None)
            toggled = n_after is not None and n_after.checked != was
            tap(n_after or n)
            bad2 = health(f"{screen}/{item} 2nd")
            final, _ = dump()
            n_fin = next((x for x in final if x.checkable and abs(x.cy - n.cy) < 10), None)
            restored = n_fin is not None and n_fin.checked == was
            note = "" if toggled else "state did not change on 1st press"
            if not restored:
                note += " | state not restored after 2nd press"
            add(screen, item, "toggle x2", "FAIL" if (bad2 or not restored) else ("OK" if toggled else "WARN"), bad2 or note.strip(" |"))
            if bad2 and not recover(path):
                return
            continue
        if sim < 0.45:
            # navigated to a new screen
            sub = f"{screen} > {item}"
            add(screen, item, "open", "OK", "sub-screen")
            crawl(sub, path + [n.label] if n.label else path, depth + 1, processed_global)
            back()
            ret, _ = dump()
            if similarity(base_txt, texts(ret)) < 0.45 and similarity(before_txt, texts(ret)) < 0.45:
                add(screen, item, "back", "WARN", "did not return to the same screen, re-opening")
                replay(path)
            continue
        # moderate change: dialog / expansion / popup
        dlg = find(after, r"^(Cancel|Отмена|No|Нет|Close|Закрыть)$")
        if dlg and dlg.pkg == PKG:
            screenshot(f"{screen}_{item}_dialog")
            back()
            bad2 = health(f"{screen}/{item} dismiss")
            add(screen, item, "open dialog and dismiss", "FAIL" if bad2 else "OK", bad2 or "")
        else:
            add(screen, item, "press", "OK", f"in-place change (sim {sim:.2f})")
        # long press on tile-like items once for the main hub
        if depth == 0 and "dialog" not in item:
            pass
    return


def recover(path):
    log("recovering after crash/ANR")
    adb(f"shell am force-stop {PKG}")
    ok = replay(path)
    if not ok:
        add("harness", "/".join(path) or "hub", "recover", "FAIL", "could not return to screen after crash")
    return ok


def long_press_tiles():
    nodes, _ = dump()
    tiles = [n for n in nodes if n.pkg == PKG and n.clickable and re.search(
        r"призрак|ghost|реклам|ads|premium", n.label, re.I)]
    for t in tiles[:6]:
        adb(f"shell input swipe {t.cx} {t.cy} {t.cx} {t.cy} 900")
        time.sleep(1.5)
        bad = health(f"longpress {t.label}")
        add("ZephyrGram hub", t.label, "long press", "FAIL" if bad else "OK", bad or "")
        back()
        nodes2, _ = dump()
        if not find(nodes2, r"^ZephyrGram$|Zephyr"):
            replay([])


def tg_settings_pass():
    """root of Telegram settings and Privacy screen (new cards)."""
    nodes, _ = dump()
    for pat in (r"Privacy and Security|Конфиденциальность", ):
        n = find(nodes, pat)
        if n:
            tap(n)
            crawl("Telegram > Privacy", ["Privacy"], 1, set())
            back()


def write_report(dc, login_ok, plugins_note):
    cnt = {}
    for r in rows:
        cnt[r[3]] = cnt.get(r[3], 0) + 1
    with open(os.path.join(out_dir, "results.json"), "w") as f:
        json.dump([dict(screen=a, item=b, action=c, result=d, note=e) for a, b, c, d, e in rows], f, ensure_ascii=False, indent=1)
    lines = ["## ZephyrGram settings tour", "",
             f"Login on test backend (DC{dc}): {'OK' if login_ok else 'FAILED'}  ",
             f"Totals: " + ", ".join(f"{k}: {v}" for k, v in sorted(cnt.items())), "",
             "| Screen | Item | Action | Result | Note |", "|---|---|---|---|---|"]
    for a, b, c, d, e in rows:
        esc = lambda s: s.replace("|", "\\|").replace("\n", " ")[:200]
        lines.append(f"| {esc(a)} | {esc(b)} | {esc(c)} | {d} | {esc(e)} |")
    with open(os.path.join(out_dir, "report.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines[:6]))


def main():
    global out_dir
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="qa-out")
    ap.add_argument("--dc", default="2")
    a = ap.parse_args()
    out_dir = a.out
    os.makedirs(os.path.join(out_dir, "screenshots"), exist_ok=True)
    adb("wait-for-device")
    adb("shell settings put global window_animation_scale 0")
    adb("shell settings put global transition_animation_scale 0")
    adb("shell settings put global animator_duration_scale 0")
    inst = adb(f"install -r -g {a.apk}", 300)
    log("install: " + inst.strip()[:120])
    adb("logcat -c")
    ok = login(a.dc)
    if not ok:
        add("login", "test backend login", "login", "FAIL", "could not log in (see screenshots/login_*.png)")
        write_report(a.dc, False, "")
        sys.exit(1)
    time.sleep(5)
    bad = health("after login")
    add("login", "login + first chat list", "login", "FAIL" if bad else "OK", bad or "")
    if not open_settings():
        add("Settings", "open ZephyrGram", "open", "FAIL", "")
        write_report(a.dc, True, "")
        sys.exit(1)
    long_press_tiles()
    crawl("ZephyrGram hub", [], 0, set())
    # Telegram settings root and privacy
    adb("shell input keyevent 4")
    time.sleep(1)
    crawl("Telegram settings root", [], 1, set())
    write_report(a.dc, True, "")
    fails = [r for r in rows if r[3] == "FAIL"]
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
