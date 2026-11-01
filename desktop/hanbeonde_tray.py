"""
한 번 더 — 윈도우 트레이 앱 (카카오톡 PC, 디스코드, 슬랙 등 PC 메신저용)

작동 방식
  1) 백그라운드 트레이에 상주한다.
  2) 대상 메신저 창에서 Enter(보내기)를 누르면 먼저 가로채서 입력창 문장을 검사한다.
     - 안전하면 Enter를 그대로 다시 보내 메시지가 전송된다.
     - 위험하면 입력창 근처에 오버레이 위젯을 띄운다: 순화안 3개 / 직접 고칠게요 / 그대로 보내기
  3) 카카오톡처럼 입력창 글자를 직접 읽을 수 있는 앱은, 입력하다 손을 멈춰도(0.8초) 위험도 알약과 경고를 띄운다.
  4) 횟수와 감지된 표현만 %APPDATA%\\Hanbeonde\\stats.json 에 남기고, 트레이 메뉴의 '대시보드'에서 그래프로 본다.
  Shift+Enter(줄바꿈)는 건드리지 않는다. 입력한 문장은 저장하지도, 인터넷으로 보내지도 않는다.

실행:  pip install -r requirements.txt  →  python hanbeonde_tray.py
"""
import ctypes
import json
import os
import queue
import shutil
import sys
import threading
import time
import tkinter as tk
import webbrowser

import keyboard            # 전역 키보드 후킹 (Enter 가로채기)
import psutil              # 앞에 떠 있는 창의 프로그램 이름
import pystray             # 트레이 아이콘
import win32api
import win32clipboard
import win32con
import win32gui
import win32process
from PIL import Image, ImageDraw

from engine import Engine

APP = "한 번 더"
BASE = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "Hanbeonde")
STATS = os.path.join(DATA_DIR, "stats.json")
SETTINGS = os.path.join(DATA_DIR, "settings.json")
LV = {"CRITICAL": 3, "HIGH": 2, "MEDIUM": 1, "LOW": 0, "NONE": -1}

TARGETS = {
    "kakaotalk.exe": "카카오톡",
    "discord.exe": "디스코드",
    "slack.exe": "슬랙",
    "line.exe": "라인",
    "telegram.exe": "텔레그램",
    "ms-teams.exe": "팀즈",
    "teams.exe": "팀즈",
}
NATIVE_EDIT_CLASSES = ("richedit", "edit")  # 카카오톡 PC 입력창 등 직접 읽을 수 있는 입력창

COLORS = {
    "CRITICAL": ("#c62828", "#fdeaea"), "HIGH": ("#d9480f", "#fff0e6"),
    "MEDIUM": ("#9a6412", "#fdf5e3"), "LOW": ("#5f6f6b", "#eef1f0"),
}
FONT = "Malgun Gothic"


# ---------------------------------------------------------------- 설정·기록
def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def save_json(path, data):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


settings = load_json(SETTINGS, {"enabled": True, "pause_check": True, "low": False, "all_apps": False})
stats_lock = threading.Lock()


def log(kind, word="", lv=-1):
    with stats_lock:
        store = load_json(STATS, {"events": [], "badges": [], "seeded": False})
        store["events"].append({"t": int(time.time() * 1000), "type": kind, "w": word, "lv": lv})
        store["events"] = store["events"][-5000:]
        save_json(STATS, store)


def threshold():
    return 0 if settings.get("low") else 1


# ---------------------------------------------------------------- 창·입력창 다루기
def foreground():
    hwnd = win32gui.GetForegroundWindow()
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        name = psutil.Process(pid).name().lower()
    except Exception:
        name = ""
    return hwnd, name


def is_target(name):
    if settings.get("all_apps"):
        return name not in ("explorer.exe", "hanbeonde.exe", "python.exe", "pythonw.exe")
    return name in TARGETS


def focused_control(hwnd):
    """앞 창에서 키보드 포커스를 가진 컨트롤"""
    me = win32api.GetCurrentThreadId()
    other, _ = win32process.GetWindowThreadProcessId(hwnd)
    try:
        ctypes.windll.user32.AttachThreadInput(me, other, True)
        return win32gui.GetFocus()
    except Exception:
        return 0
    finally:
        ctypes.windll.user32.AttachThreadInput(me, other, False)


def is_native_edit(ctrl):
    try:
        return bool(ctrl) and win32gui.GetClassName(ctrl).lower().startswith(NATIVE_EDIT_CLASSES)
    except Exception:
        return False


def native_get(ctrl):
    n = win32gui.SendMessage(ctrl, win32con.WM_GETTEXTLENGTH, 0, 0)
    if n <= 0:
        return ""
    buf = win32gui.PyMakeBuffer((n + 1) * 2)
    win32gui.SendMessage(ctrl, win32con.WM_GETTEXT, n + 1, buf)
    return bytes(buf[: n * 2]).decode("utf-16-le", errors="ignore")


def native_set(ctrl, text):
    win32gui.SendMessage(ctrl, win32con.WM_SETTEXT, 0, text)
    win32gui.SendMessage(ctrl, win32con.EM_SETSEL, len(text), len(text))


def clip_get():
    try:
        win32clipboard.OpenClipboard()
        try:
            if win32clipboard.IsClipboardFormatAvailable(win32con.CF_UNICODETEXT):
                return win32clipboard.GetClipboardData(win32con.CF_UNICODETEXT)
            return None
        finally:
            win32clipboard.CloseClipboard()
    except Exception:
        return None


def clip_set(text):
    for _ in range(5):
        try:
            win32clipboard.OpenClipboard()
            try:
                win32clipboard.EmptyClipboard()
                if text is not None:
                    win32clipboard.SetClipboardData(win32con.CF_UNICODETEXT, text)
                return
            finally:
                win32clipboard.CloseClipboard()
        except Exception:
            time.sleep(0.03)


class Passing:
    """우리가 다시 보내는 키는 후킹에서 그냥 통과시킨다"""
    on = False


def send_keys(combo):
    Passing.on = True
    try:
        keyboard.send(combo)
    finally:
        time.sleep(0.02)
        Passing.on = False


def read_text(hwnd):
    """입력창 문장 읽기: 직접 읽을 수 있으면 직접, 아니면 클립보드로(전체 선택 → 복사 → 원래 클립보드 복원)"""
    ctrl = focused_control(hwnd)
    if is_native_edit(ctrl):
        return native_get(ctrl), ctrl
    old = clip_get()
    clip_set("")
    send_keys("ctrl+a")
    send_keys("ctrl+c")
    text = ""
    for _ in range(15):
        time.sleep(0.02)
        got = clip_get()
        if got:
            text = got
            break
    send_keys("end")
    clip_set(old)
    return text, ctrl


def write_text(hwnd, ctrl, text):
    if is_native_edit(ctrl):
        native_set(ctrl, text)
        return
    old = clip_get()
    clip_set(text)
    send_keys("ctrl+a")
    send_keys("ctrl+v")
    time.sleep(0.08)
    clip_set(old)


def anchor_rect(hwnd, ctrl):
    try:
        return win32gui.GetWindowRect(ctrl if ctrl else hwnd)
    except Exception:
        return win32gui.GetWindowRect(hwnd)


# ---------------------------------------------------------------- 화면 (포커스를 뺏지 않는 창)
def no_activate(win):
    """클릭해도 메신저 창의 포커스를 뺏지 않게 WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW"""
    win.update_idletasks()
    hwnd = ctypes.windll.user32.GetParent(win.winfo_id()) or win.winfo_id()
    style = ctypes.windll.user32.GetWindowLongW(hwnd, -20)
    ctypes.windll.user32.SetWindowLongW(hwnd, -20, style | 0x08000000 | 0x00000080)


class Overlay:
    def __init__(self, root, app):
        self.app = app
        self.win = tk.Toplevel(root)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True)
        self.win.configure(bg="#d8e0de")
        self.win.withdraw()
        self.body = tk.Frame(self.win, bg="white", padx=16, pady=12)
        self.body.pack(padx=1, pady=1)
        top = tk.Frame(self.body, bg="white")
        top.pack(fill="x")
        self.badge = tk.Label(top, font=(FONT, 9, "bold"), padx=8, pady=2)
        self.badge.pack(side="left")
        tk.Label(top, text="잠깐! 상대방이 상처받을 수 있어요", font=(FONT, 11, "bold"), bg="white", fg="#16201e").pack(side="left", padx=8)
        tk.Button(top, text="×", bd=0, bg="white", fg="#7c8784", font=(FONT, 12), command=self.app.on_close).pack(side="right")
        self.sub = tk.Label(self.body, font=(FONT, 9), bg="white", fg="#4d5a57", wraplength=330, justify="left", anchor="w")
        self.sub.pack(fill="x", pady=(6, 8))
        self.chips = tk.Frame(self.body, bg="white")
        self.chips.pack(fill="x")
        acts = tk.Frame(self.body, bg="white")
        acts.pack(fill="x", pady=(8, 0))
        tk.Button(acts, text="직접 고칠게요", font=(FONT, 9), bg="white", relief="groove", command=self.app.on_edit).pack(side="left", expand=True, fill="x", padx=(0, 4))
        self.keep = tk.Button(acts, text="그대로 보내기", font=(FONT, 9), bg="white", fg="#4d5a57", relief="groove", command=self.app.on_keep)
        self.keep.pack(side="left", expand=True, fill="x")
        tk.Label(self.body, text="한 번 더 · 이 컴퓨터 안에서만 분석해요", font=(FONT, 8), bg="white", fg="#7c8784").pack(anchor="w", pady=(8, 0))
        self._activated = False

    def show(self, result, rect, from_enter):
        fg, bg = COLORS.get(result["risk_level"], COLORS["LOW"])
        self.badge.configure(text=result["risk_level"], fg=fg, bg=bg)
        top = max(result["matches"], key=lambda m: LV[m["risk_level"]])
        self.sub.configure(text=f"‘{top['word']}’ · {result['category']}. 원래 하려던 {result['emotion']}의 마음은 살리고 공격적인 말만 빼 볼까요?")
        for w in self.chips.winfo_children():
            w.destroy()
        for s in result["suggestions"]:
            tk.Button(self.chips, text=s, anchor="w", font=(FONT, 10), bg="#f2faf8", fg="#0e6b63", activebackground="#0e6b63",
                      activeforeground="white", relief="flat", padx=12, pady=5, cursor="hand2",
                      command=lambda s=s: self.app.on_choose(s)).pack(fill="x", pady=2)
        self.keep.configure(text="그대로 보내기" if from_enter else "그대로 쓸게요")
        self.win.update_idletasks()
        w, h = self.win.winfo_reqwidth(), self.win.winfo_reqheight()
        left, top_y, right, _ = rect
        x = max(8, min(right - w, self.win.winfo_screenwidth() - w - 8))
        y = top_y - h - 10 if top_y - h - 10 > 8 else top_y + 40
        self.win.geometry(f"+{x}+{y}")
        self.win.deiconify()
        if not self._activated:
            no_activate(self.win)
            self._activated = True
        self.win.lift()

    def hide(self):
        self.win.withdraw()


class Pill:
    """입력창 오른쪽 위의 위험도 알약"""
    def __init__(self, root):
        self.win = tk.Toplevel(root)
        self.win.overrideredirect(True)
        self.win.attributes("-topmost", True)
        self.win.withdraw()
        self.label = tk.Label(self.win, font=(FONT, 9, "bold"), padx=10, pady=3)
        self.label.pack()
        self._activated = False

    def show(self, result, rect):
        fg, bg = COLORS.get(result["risk_level"], COLORS["LOW"])
        words = ", ".join(result["detected_words"])
        self.label.configure(text=f"● {result['risk_level']} · {words[:30]}", fg=fg, bg=bg)
        self.win.update_idletasks()
        w = self.win.winfo_reqwidth()
        _, top, right, _ = rect
        self.win.geometry(f"+{max(8, right - w - 6)}+{max(8, top - 30)}")
        self.win.deiconify()
        if not self._activated:
            no_activate(self.win)
            self._activated = True

    def hide(self):
        self.win.withdraw()


# ---------------------------------------------------------------- 앱
class App:
    def __init__(self):
        self.engine = Engine.load(os.path.join(BASE, "words.json"))
        self.root = tk.Tk()
        self.root.withdraw()
        self.overlay = Overlay(self.root, self)
        self.pill = Pill(self.root)
        self.jobs = queue.Queue()
        self.ctx = None          # 오버레이를 띄운 대상 {hwnd, ctrl, text, result, from_enter}
        self.bypass = None       # '그대로 보내기'로 허락한 문장
        self.session = None      # 이번 메시지에서 처음 감지된 표현
        self.last_seen = ("", 0.0)
        self.paused_text = None
        keyboard.hook_key("enter", self.on_enter_hook, suppress=True)
        threading.Thread(target=self.watch_typing, daemon=True).start()
        self.tray = self.make_tray()
        self.root.after(50, self.pump)

    # ----- Enter 가로채기 (후킹 스레드) -----
    def on_enter_hook(self, event):
        if Passing.on or event.event_type != keyboard.KEY_DOWN:
            return True
        if not settings.get("enabled", True) or keyboard.is_pressed("shift") or keyboard.is_pressed("ctrl") or keyboard.is_pressed("alt"):
            return True
        hwnd, name = foreground()
        if not is_target(name):
            return True
        self.jobs.put(("enter", hwnd))
        return False  # 일단 막고, 검사한 뒤 다시 보낸다

    # ----- 입력 중 감시 (직접 읽을 수 있는 입력창만, 키 입력 없이 읽는다) -----
    def watch_typing(self):
        while True:
            time.sleep(0.25)
            try:
                if not settings.get("enabled", True):
                    continue
                hwnd, name = foreground()
                if not is_target(name):
                    self.jobs.put(("pill_hide", None))
                    continue
                ctrl = focused_control(hwnd)
                if not is_native_edit(ctrl):
                    continue
                text = native_get(ctrl)
                prev, since = self.last_seen
                now = time.time()
                if text != prev:
                    self.last_seen = (text, now)
                    self.jobs.put(("typing", (hwnd, ctrl, text, False)))
                elif settings.get("pause_check", True) and text and now - since > 0.8 and self.paused_text != text:
                    self.paused_text = text
                    self.jobs.put(("typing", (hwnd, ctrl, text, True)))
            except Exception:
                pass

    def handle_typing(self, hwnd, ctrl, text, paused):
        result = self.engine.analyze_detailed(text)
        if not result["is_harmful"]:
            self.pill.hide()
            if self.ctx and not self.ctx["from_enter"]:
                self.overlay.hide()
            return
        if not self.session:
            self.session = {"word": result["matches"][0]["word"], "lv": LV[result["risk_level"]], "changed": False}
        rect = anchor_rect(hwnd, ctrl)
        self.pill.show(result, rect)
        if paused and LV[result["risk_level"]] >= threshold() and self.bypass != text:
            self.ctx = {"hwnd": hwnd, "ctrl": ctrl, "text": text, "result": result, "from_enter": False}
            self.overlay.show(result, rect, False)

    # ----- 오버레이 버튼 -----
    def on_choose(self, s):
        c = self.ctx
        if not c:
            return
        m = c["result"]["matches"][0]
        lv = LV[c["result"]["risk_level"]]
        log("detect", m["word"], lv)
        log("change", m["word"], lv)
        if self.session:
            self.session["changed"] = True
        self.overlay.hide()
        self.pill.hide()

        def work():
            try:
                if win32gui.GetForegroundWindow() != c["hwnd"]:
                    win32gui.SetForegroundWindow(c["hwnd"])
            except Exception:
                pass
            write_text(c["hwnd"], c["ctrl"], s)
            if c["from_enter"]:
                time.sleep(0.05)
                log("sent")
                self.session = None
                send_keys("enter")
        threading.Thread(target=work, daemon=True).start()
        self.ctx = None

    def on_keep(self):
        c = self.ctx
        self.overlay.hide()
        if not c:
            return
        self.bypass = c["text"]
        if c["from_enter"]:
            m = c["result"]["matches"][0]
            log("detect", m["word"], LV[c["result"]["risk_level"]])
            log("keep", m["word"], LV[c["result"]["risk_level"]])
            log("sent")
            self.session = None
            threading.Thread(target=lambda: send_keys("enter"), daemon=True).start()
        self.ctx = None

    def on_edit(self):
        self.overlay.hide()
        self.ctx = None

    def on_close(self):
        self.overlay.hide()
        self.ctx = None

    # ----- 작업 처리 (tk 스레드) -----
    def pump(self):
        try:
            while True:
                kind, arg = self.jobs.get_nowait()
                if kind == "enter":
                    threading.Thread(target=self._enter_safe, args=(arg,), daemon=True).start()
                elif kind == "show_enter":
                    self.overlay.show(*arg)
                elif kind == "typing":
                    self.handle_typing(*arg)
                elif kind == "pill_hide":
                    self.pill.hide()
                elif kind == "quit":
                    self.root.quit()
                    return
        except queue.Empty:
            pass
        self.root.after(40, self.pump)

    def _enter_safe(self, hwnd):
        # 클립보드·키 입력이 들어가는 작업은 별도 스레드에서, 화면은 tk 스레드에서
        try:
            text, ctrl = read_text(hwnd)
            if not text.strip():
                send_keys("enter")
                return
            result = self.engine.analyze_detailed(text)
            harmful = result["is_harmful"] and LV[result["risk_level"]] >= threshold()
            if harmful and self.bypass != text:
                self.ctx = {"hwnd": hwnd, "ctrl": ctrl, "text": text, "result": result, "from_enter": True}
                self.jobs.put(("show_enter", (result, anchor_rect(hwnd, ctrl), True)))
                return
            self.jobs.put(("pill_hide", None))
            self._send_logged(text, result)
        except Exception:
            send_keys("enter")  # 문제가 생기면 사용자의 Enter를 막지 않는다

    def _send_logged(self, text, result):
        if result["is_harmful"]:
            m = result["matches"][0]
            log("detect", m["word"], LV[result["risk_level"]])
            log("keep", m["word"], LV[result["risk_level"]])
        elif self.session and not self.session.get("changed"):
            log("detect", self.session["word"], self.session["lv"])
            log("change", self.session["word"], self.session["lv"])
        elif any(k in text for k in ("수고", "고생", "대단", "잘했", "열심히", "고마워", "덕분", "노력", "최고야")):
            log("praise")
        log("sent")
        self.session, self.bypass, self.paused_text = None, None, None
        send_keys("enter")

    # ----- 트레이 -----
    def make_tray(self):
        def toggle(key):
            def f(icon, item):
                settings[key] = not settings.get(key, key in ("enabled", "pause_check"))
                save_json(SETTINGS, settings)
            return f

        def checked(key, default):
            return lambda item: settings.get(key, default)

        menu = pystray.Menu(
            pystray.MenuItem("켜기", toggle("enabled"), checked=checked("enabled", True)),
            pystray.MenuItem("입력하다 멈추면 검사 (카카오톡)", toggle("pause_check"), checked=checked("pause_check", True)),
            pystray.MenuItem("가벼운 표현도 알림", toggle("low"), checked=checked("low", False)),
            pystray.MenuItem("모든 프로그램에서 검사", toggle("all_apps"), checked=checked("all_apps", False)),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("대시보드 열기", lambda: self.open_web("dashboard"), default=True),
            pystray.MenuItem("텍스트 검사기 열기", lambda: self.open_web("checker")),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("종료", self.quit),
        )
        icon = pystray.Icon("hanbeonde", tray_image(), f"{APP} · 대상: " + ", ".join(sorted(set(TARGETS.values()))), menu)
        icon.run_detached()
        return icon

    def open_web(self, view):
        """대시보드: 화면 파일을 내 폴더로 복사하고 기록을 desktop-data.js 로 붙여서 브라우저로 연다"""
        src = os.path.join(BASE, "web")
        dst = os.path.join(DATA_DIR, "web")
        os.makedirs(dst, exist_ok=True)
        for fn in ("app.js", "words.js", "hanbeonde-engine.js"):
            shutil.copyfile(os.path.join(src, fn), os.path.join(dst, fn))
        with open(os.path.join(src, "index.html"), encoding="utf-8") as f:
            html = f.read().replace('<script src="app.js"></script>',
                                    '<script src="desktop-data.js"></script>\n<script src="app.js"></script>')
        with open(os.path.join(dst, "index.html"), "w", encoding="utf-8") as f:
            f.write(html)
        store = load_json(STATS, {"events": [], "badges": [], "seeded": False})
        with open(os.path.join(dst, "desktop-data.js"), "w", encoding="utf-8") as f:
            f.write("window.HANBEONDE_DESKTOP_STORE=" + json.dumps(store, ensure_ascii=False) + ";")
        webbrowser.open("file:///" + os.path.join(dst, "index.html").replace("\\", "/") + "#" + view)

    def quit(self):
        try:
            keyboard.unhook_all()
        finally:
            self.tray.stop()
            self.jobs.put(("quit", None))

    def run(self):
        self.root.mainloop()


def tray_image():
    s = 64
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=14, fill=(14, 107, 99, 255))
    d.rounded_rectangle([13, 14, 51, 43], radius=6, fill="white")
    d.polygon([(20, 42), (20, 53), (31, 42)], fill="white")
    d.line([(19, 30), (24, 26), (29, 32), (34, 26), (39, 32), (44, 28)], fill=(214, 54, 58, 255), width=4)
    return im


if __name__ == "__main__":
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)  # 고해상도 화면에서 흐리지 않게
    except Exception:
        pass
    App().run()
