"""
한 번 더(Hanbeonde) 감지 엔진 v2 — Python 포팅
engine/hanbeonde-engine.js 와 같은 규칙, 같은 결과.

    from engine import Engine
    e = Engine.load("words.json")
    e.analyze("씨발련아")           # 규격 JSON(dict)
    e.analyze_detailed("씨발련아")  # + 위치·설명(matches)
"""
import json
import re
import unicodedata

LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
GROUP_RANK = {"P": 3, "S": 2, "A": 1}

SUGGEST = {
    "P": ["가족 얘기는 하지 말자. 지금 나 많이 화났어", "너한테 정말 서운하고 화가 나", "우리 일은 우리끼리 얘기하자"],
    "P_joke": ["야 그건 선 넘었다ㅋㅋ 다른 얘기 하자", "가족 얘기는 장난이어도 좀 그렇다ㅋㅋ", "ㅋㅋㅋ 오늘 왜 이렇게 웃기냐"],
    "S_angry": ["너 때문에 정말 속상하고 화가 나", "일이 이렇게 돼서 너무 답답해", "다음부터는 주의해 줬으면 좋겠어"],
    "S_frustrated": ["일이 이렇게 돼서 너무 답답해", "아 진짜 마음대로 안 돼서 속상하다", "잠깐 쉬었다가 다시 해 보자"],
    "S_joke": ["아 진짜 웃기네ㅋㅋ", "너 오늘 왜 이렇게 웃기냐ㅋㅋ", "야 장난 그만ㅋㅋ"],
    "S_surprised": ["와 진짜 대박이다", "헐 진짜?", "완전 놀랐어"],
    "A_angry": ["너 때문에 좀 속상해", "그 행동은 나한테 좀 서운했어", "지금 기분이 좀 안 좋아. 이따 얘기하자"],
    "A_frustrated": ["아 오늘 일이 잘 안 풀린다", "좀 답답해서 그래", "다음엔 같이 잘해 보자"],
    "body": ["오늘 진짜 맛있게 먹더라", "너랑 밥 먹으면 재밌어", "이따 같이 매점 갈래?"],
    "face": ["오늘 많이 피곤해 보이는데 괜찮아?", "무슨 일 있었어? 분위기 다르다", "오늘 하루 어땠어?"],
    "slang": ["오늘 텐션 뭐야", "너 오늘 말빨 좋다", "오늘 뭔가 기분 좋아 보인다"],
    "hair": ["오늘 뭔가 분위기 달라졌다", "헤어 바꿨어? 새롭다", "오늘 스타일 신경 썼네"],
    "height": ["너 진짜 날쌔다", "너랑 다니면 재밌어", "오늘 뭐 하고 놀까?"],
    "thin": ["밥 같이 먹자", "너 체육 시간에 진짜 잘 뛰더라", "오늘 컨디션 어때?"],
    "part": ["요즘 많이 바쁘지?", "잠은 잘 잤어?", "오늘 하루 어땠어?"],
    "idol": ["너는 너만의 분위기가 있어", "너 그 노래 진짜 잘 부르더라", "주말에 무대 영상 같이 볼래?"],
}
PARTICLES = "아야이가는은을를들"

# ---------- 메시지 맞춤 순화 ----------
# "야이 개새끼야 너 때문에 약속 시간 늦었잖아" → "아니 너 때문에 약속 시간 늦었잖아 ㅜㅜ"
INTENS = ["존나", "졸라", "존내", "존니", "좃나", "조낸"]
INTENS_SURF = ["존나", "졸라", "존내", "존니", "좃나", "조낸", "ㅈㄴ", "whssk"]
INTERJ = ["야", "이", "야이", "이런", "저런", "아오", "에이", "아씨", "저"]
APPEAR_PRED = ["생겼", "같이", "같아", "같다", "같네", "같은", "닮", "처럼"]
DANGLING = ["진짜", "완전", "너무", "개", "좀", "왜", "너", "니", "넌", "진심", "약간", "되게", "엄청", "아주", "그냥"]
EDGE = "\"'“”‘’()[]{}<>.,!~…·:;"
RE_TAIL = re.compile(r"[\sㅋㅎㅠㅜ.,!~…]+$")
RE_HEAD = re.compile(r"^[\s.,!~…]+")
RE_ENDP = re.compile(r"[?!]$")
RE_WOW = re.compile(r"헐|대박")


def _strip_edge(s):
    a, b = 0, len(s)
    while a < b and (s[a] in EDGE or s[a] == "?"):
        a += 1
    while b > a and s[b - 1] in EDGE:
        b -= 1
    return s[a:b]


def _prefixed(p, c, alts=()):
    first = c.split(" ")[0].rstrip(",")
    if first in [p.rstrip(" ,")] + list(alts):
        return c
    return p + c


RE_ADDR = re.compile(r"^(너|넌|널|니가|네가|니네|니도|니꺼|니거)")
RE_ADDR_END = re.compile(r"(냐|잖아|하지 ?마|해라|줄래|줘)[?!]*$")  # 따지거나 부탁하는 말끝


def _addressed(c):
    """상대에게 하는 말인지 (너/넌/니가/네가 …)"""
    return any(RE_ADDR.search(w) for w in c.split(" ")) or bool(RE_ADDR_END.search(c))


def _joined(c, tail):
    return c + " " + tail if RE_ENDP.search(c) else c + ". " + tail


def contextual_suggestions(text, matches, group, emotion):
    """보내려던 메시지에서 공격적인 말만 빼고 다듬은 문장들. 문장이 성립하지 않으면 None"""
    toks, i, n = [], 0, len(text)
    while i < n:
        while i < n and text[i] in SPACES:
            i += 1
        if i >= n:
            break
        s = i
        while i < n and text[i] not in SPACES:
            i += 1
        toks.append({"s": s, "e": i, "t": text[s:i], "st": "keep", "rep": ""})
    for tk in toks:
        for m in matches:
            if not (m["start"] < tk["e"] and tk["s"] < m["end"]) or tk["st"] == "bad":
                continue
            if m["start"] >= tk["s"] and m["end"] <= tk["e"] and m["matched"] in INTENS:
                core = _strip_edge(tk["t"])
                low, rep = core.lower(), "진짜"
                for p in INTENS_SURF:
                    if low.startswith(p):
                        rest = core[len(p):]
                        rep = "진짜 " + rest if rest else "진짜"
                        break
                tk["st"], tk["rep"] = "soft", rep
            else:
                tk["st"] = "bad"
    incomplete = False
    for k, tk in enumerate(toks):
        if tk["st"] != "bad":
            continue
        j = k - 1
        while j >= 0 and toks[j]["st"] == "keep" and _strip_edge(toks[j]["t"]) in INTERJ:
            toks[j]["st"] = "drop"
            j -= 1
        if k + 1 < len(toks) and toks[k + 1]["st"] == "keep":
            nt = _strip_edge(toks[k + 1]["t"])
            if any(nt.startswith(p) for p in APPEAR_PRED):
                toks[k + 1]["st"] = "drop"
                incomplete = True
    if incomplete:
        return None
    last = next((tk for tk in reversed(toks) if _strip_edge(tk["t"])), None)
    if last is not None and last["st"] == "bad" and group == "A":
        return None
    parts = [tk["t"] if tk["st"] == "keep" else tk["rep"] for tk in toks if tk["st"] in ("keep", "soft")]
    core = RE_HEAD.sub("", RE_TAIL.sub("", " ".join(parts)))
    if sum(1 for ch in core if _syl(ch)) < 2:
        return None
    if core.split(" ")[-1].rstrip("?") in DANGLING:
        return None
    has_bad = any(tk["st"] == "bad" for tk in toks)
    e = emotion or ("care" if group == "A" or not has_bad else "angry")
    if e == "surprised" and not RE_WOW.search(text):
        e = "ask"
    if e == "angry" and not _addressed(core):
        e = "frustrated"
    if e == "frustrated":
        out = [_prefixed("아 ", core, ["아", "하아", "하", "에휴", "어휴", "아오"]) + " ㅠㅠ",
               core + (" " if RE_ENDP.search(core) else "... ") + "너무 답답하다", _joined(core, "좀 쉬었다 해야겠다")]
    elif e == "joke":
        out = [core + "ㅋㅋ", _prefixed("야 ", core) + "ㅋㅋ", core + " ㅎㅎ"]
    elif e == "surprised":
        out = [_prefixed("헐 ", core, ["와", "대박"]), _joined(core, "진짜 놀랐어"), _joined(core, "완전 신기하다")]
    elif e == "ask":
        out = [core, _prefixed("근데 ", core), _joined(core, "궁금해서")]
    elif e == "care":
        out = [core, _prefixed("있잖아, ", core), core + " ㅎㅎ"]
    else:
        out = [_prefixed("아니 ", core, ["아"]) + " ㅜㅜ", _joined(core, "나 좀 속상해"), _joined(core, "다음엔 조심해 줘")]
    if group == "P":
        out[1] = _joined(core, "가족 얘기는 빼자ㅋㅋ" if e == "joke" else "가족 얘기는 하지 말자")
    uniq = []
    for x in out:
        if x not in uniq:
            uniq.append(x)
    return uniq


def merge_suggestions(ctx, base):
    out = list(ctx or [])
    for b in base:
        if len(out) >= 3:
            break
        if b not in out:
            out.append(b)
    return out[:3]
EMOTION_LABEL = {"joke": "장난", "frustrated": "답답함", "angry": "화남", "surprised": "놀람", "care": "관심"}

RE_LAUGH = re.compile(r"[ㅋㅎ]{2,}|lol|\^\^", re.I)
RE_FRUSTRATED = re.compile(r"답답|에휴|어휴|하아|한숨|미치겠|짜증")
RE_ANGRY = re.compile(r"화나|화난|빡치|빡쳐|열받|꺼져|닥쳐|죽을래|!")
RE_SURPRISED = re.compile(r"헐|대박|\?")
TRIM = set("\"'“”‘’()[]{}<>.,!?~…·:; \t\n\r 　")
SPACES = set(" \t\n\r 　")


def _syl(c): return 0xAC00 <= ord(c) <= 0xD7A3
def _jamo(c): return 0x3131 <= ord(c) <= 0x318E
def _hangul(c): return _syl(c) or _jamo(c)
def _latin(c): return "a" <= c <= "z"
def _digit(c): return "0" <= c <= "9"


class _Trie:
    def __init__(self):
        self.root = {}

    def add(self, key, value):
        n = self.root
        for c in key:
            n = n.setdefault(c, {})
        n["$"] = value

    def scan(self, chars):
        hits = []
        for i in range(len(chars)):
            n, best, val = self.root, -1, None
            for j in range(i, len(chars)):
                n = n.get(chars[j][0])
                if n is None:
                    break
                if "$" in n:
                    best, val = j + 1, n["$"]
            if best > 0:
                hits.append((i, best, val))
        return hits


class Engine:
    def __init__(self, data):
        self.data = data
        self.entries = data["entries"]
        self.short, self.long, self.allow = _Trie(), _Trie(), _Trie()
        for i, e in enumerate(self.entries):
            (self.short if len(e[1]) <= 2 else self.long).add(e[1], i)
        for i, a in enumerate(data["allow"]):
            self.allow.add(a, i)
        self.variants = [(re.compile(v[0]), v[1]) for v in data["variants"]]
        self.entry_count = len(self.entries)
        self.excel_count = data.get("excel_count", 0)

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    # ---------- 정규화 ----------
    def _normalize(self, text):
        chars = []  # (c, a, b)
        for i, ch in enumerate(text):
            if ch in SPACES:
                chars.append((" ", i, i + 1))
                continue
            lc = ch.lower()
            if len(lc) == 1 and (_hangul(lc) or _latin(lc) or _digit(lc)):
                chars.append((lc, i, i + 1))
        cleaned = []
        j = 0
        while j < len(chars):
            c = chars[j][0]
            if _digit(c) or _latin(c):
                k = j
                while k < len(chars) and (_digit(chars[k][0]) or _latin(chars[k][0])):
                    k += 1
                prev = cleaned[-1] if cleaned else None
                nxt = chars[k] if k < len(chars) else None
                between = prev is not None and nxt is not None and _hangul(prev[0]) and _hangul(nxt[0])
                all_digits = all(_digit(chars[r][0]) for r in range(j, k))
                if not (between and (all_digits or k - j == 1)):
                    cleaned.extend(chars[j:k])
                j = k
                continue
            cleaned.append(chars[j])
            j += 1
        tokens, tok = [], []
        for ch in cleaned:
            if ch[0] == " ":
                if tok:
                    tokens.append(tok)
                    tok = []
            else:
                tok.append(ch)
        if tok:
            tokens.append(tok)
        spaced, runs = [], []
        for u, t in enumerate(tokens):
            if len(t) == 1 and u + 1 < len(tokens) and len(tokens[u + 1]) == 1 and not (u > 0 and len(tokens[u - 1]) == 1):
                run, x = [], u
                while x < len(tokens) and len(tokens[x]) == 1:
                    run.append(tokens[x][0])
                    x += 1
                runs.append(run)
            if u > 0 and not (len(tokens[u - 1]) == 1 and len(t) == 1):
                spaced.append((" ", tokens[u - 1][-1][2], t[0][1]))
            spaced.extend(t)
        for rx, rep in self.variants:
            spaced = self._apply_variant(spaced, rx, rep)
        return spaced, runs

    @staticmethod
    def _apply_variant(chars, rx, rep):
        s = "".join(c[0] for c in chars)
        out, last, anyhit = [], 0, False
        for m in rx.finditer(s):
            if m.end() == m.start():
                continue
            anyhit = True
            out.extend(chars[last:m.start()])
            a, b = chars[m.start()][1], chars[m.end() - 1][2]
            out.extend((r, a, b) for r in rep)
            last = m.end()
        if not anyhit:
            return chars
        out.extend(chars[last:])
        return out

    # ---------- 분석 ----------
    def _info(self, idx):
        e, d = self.entries[idx], self.data
        return {
            "word": e[0], "level": e[2], "group": e[3], "category": d["categories"][e[4]],
            "type": d["types"][e[5]], "description": d["descriptions"][e[6]] if e[6] >= 0 else "", "sg": e[7],
        }

    @staticmethod
    def _category_label(info):
        g, lv = info["group"], info["level"]
        if g == "P":
            return "패드립 및 인격 모독"
        if g == "S":
            return "강한 욕설 및 비속어" if lv >= 3 else ("공격적 비속어" if lv == 2 else "비속어")
        return {
            "얼굴/생김새": "외모 비하 (얼굴/생김새)",
            "체형/신체조건": "외모 비하 (체형/신체조건)",
            "성형/피부/노화": "외모 비하 (성형/피부/노화)",
            "젠더/연령대 결합": "외모·성별 비하",
            "인터넷/게임 은어": "외모 비하 은어",
        }.get(info["category"], "외모·체형 비하")

    @staticmethod
    def _emotion(text):
        if RE_LAUGH.search(text):
            return "joke"
        if RE_FRUSTRATED.search(text):
            return "frustrated"
        if RE_ANGRY.search(text):
            return "angry"
        if RE_SURPRISED.search(text):
            return "surprised"
        return ""

    @staticmethod
    def _suggestions(group, sg, emotion):
        if group == "P":
            return list(SUGGEST["P_joke" if emotion == "joke" else "P"])
        if group == "S":
            e = emotion or "angry"
            return list(SUGGEST["S_" + e])
        if emotion == "angry":
            return list(SUGGEST["A_angry"])
        if emotion == "frustrated":
            return list(SUGGEST["A_frustrated"])
        out = list(SUGGEST.get(sg) or SUGGEST["face"])
        if emotion == "joke":
            out = [s if (s.endswith("?") or s.endswith("ㅋ")) else s + "ㅋㅋ" for s in out]
        return out

    def analyze_detailed(self, text):
        text = unicodedata.normalize("NFC", "" if text is None else str(text))
        spaced, runs = self._normalize(text)
        nospace = [c for c in spaced if c[0] != " "]
        hits = []
        for i, j, v in self.short.scan(spaced):
            hits.append((spaced[i][1], spaced[j - 1][2], self._info(v)))
        for i, j, v in self.long.scan(nospace):
            hits.append((nospace[i][1], nospace[j - 1][2], self._info(v)))
        allows = [(spaced[i][1], spaced[j - 1][2]) for i, j, _ in self.allow.scan(spaced)]
        hits = [h for h in hits if not any(a <= h[0] and h[1] <= b for a, b in allows)]
        hits.sort(key=lambda h: (h[0], -(h[1] - h[0])))

        clusters = []
        for h in hits:
            if clusters and h[0] < clusters[-1][1]:
                c = clusters[-1]
                c[1] = max(c[1], h[1])
                c[2].append(h)
            else:
                clusters.append([h[0], h[1], [h]])

        groups = []
        for a, b, hs in clusters:
            s, e = a, b
            # 한 글자씩 띄어 쓴 구간: 감지에 걸린 글자들 + 바로 뒤 조사(아/야…)까지만 강조
            for run in runs:
                idx = [i for i, ch in enumerate(run) if ch[1] < e and s < ch[2]]
                if not idx:
                    continue
                first, last = idx[0], idx[-1]
                while last + 1 < len(run) and run[last + 1][0] in PARTICLES:
                    last += 1
                s, e = min(s, run[first][1]), max(e, run[last][2])
            while s > 0 and text[s - 1] not in SPACES:
                s -= 1
            while e < len(text) and text[e] not in SPACES:
                e += 1
            while s < e and text[s] in TRIM:
                s += 1
            while e > s and text[e - 1] in TRIM:
                e -= 1
            if groups and s < groups[-1][1]:
                groups[-1][1] = max(groups[-1][1], e)
                groups[-1][2].extend(hs)
            else:
                groups.append([s, e, list(hs)])

        matches = []
        for s, e, hs in groups:
            best = None
            for h in hs:
                if best is None:
                    best = h
                    continue
                hi, bi = h[2], best[2]
                if (hi["level"] > bi["level"] or (hi["level"] == bi["level"] and (
                        GROUP_RANK[hi["group"]] > GROUP_RANK[bi["group"]] or (
                        GROUP_RANK[hi["group"]] == GROUP_RANK[bi["group"]] and (h[1] - h[0]) > (best[1] - best[0]))))):
                    best = h
            info = best[2]
            matches.append({
                "word": text[s:e], "start": s, "end": e, "risk_level": LEVELS[info["level"]],
                "level": info["level"], "group": info["group"], "category": self._category_label(info),
                "matched": info["word"], "type": info["type"], "description": info["description"], "sg": info["sg"],
            })

        if not matches:
            return {
                "is_harmful": False, "risk_level": "NONE", "detected_words": [], "category": None, "suggestions": [],
                "pc_ui_action": {"show_overlay": False, "highlight_target": None}, "emotion": None, "matches": [],
            }
        top = matches[0]
        for m in matches:
            if m["level"] > top["level"] or (m["level"] == top["level"] and GROUP_RANK[m["group"]] > GROUP_RANK[top["group"]]):
                top = m
        emotion = self._emotion(text)
        shown = emotion or ("care" if top["group"] == "A" else "angry")
        words = []
        for m in matches:
            if m["word"] not in words:
                words.append(m["word"])
        return {
            "is_harmful": True,
            "risk_level": top["risk_level"],
            "detected_words": words,
            "category": top["category"],
            "suggestions": merge_suggestions(contextual_suggestions(text, matches, top["group"], emotion),
                                             self._suggestions(top["group"], top["sg"], emotion)),
            "pc_ui_action": {"show_overlay": top["level"] >= 1, "highlight_target": top["word"]},
            "emotion": EMOTION_LABEL[shown],
            "matches": [{k: m[k] for k in ("word", "start", "end", "risk_level", "category", "matched", "type", "description")}
                        for m in matches],
        }

    def analyze(self, text):
        d = self.analyze_detailed(text)
        return {k: d[k] for k in ("is_harmful", "risk_level", "detected_words", "category", "suggestions", "pc_ui_action")}


if __name__ == "__main__":
    import os
    import sys
    eng = Engine.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), "words.json"))
    print(json.dumps(eng.analyze(" ".join(sys.argv[1:]) or "씨발련아"), ensure_ascii=False, indent=2))
