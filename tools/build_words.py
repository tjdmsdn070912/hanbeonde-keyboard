#!/usr/bin/env python3
"""
엑셀 단어 DB → engine/words.json 변환기

사용법:  python3 tools/build_words.py
  - tools/외모비하_욕설_DB.xlsx 의 모든 표현(번호/단어/카테고리/표현 유형/심각도/설명)을 읽는다.
  - 아래 CORE(핵심 욕설·비속어·패드립·외모 비하 토큰), VARIANTS(초성·영문·숫자 변형), ALLOW(오탐 예외)를 합친다.
  - 결과를 engine/words.json 으로 쓰고, 각 플랫폼 폴더(PC 웹, 크롬 확장, 윈도우 앱, 안드로이드, 아이폰)에 복사한다.
엑셀을 고친 뒤 이 스크립트를 다시 실행하면 모든 버전에 반영된다.
"""
import json
import os
import re
import shutil
import sys

import openpyxl

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
XLSX = os.path.join(ROOT, "tools", "외모비하_욕설_DB.xlsx")
OUT = os.path.join(ROOT, "engine", "words.json")
COPIES = [
    "pc-web/words.json",
    "chrome-extension/words.json",
    "desktop/words.json",
    "android/app/src/main/assets/words.json",
    "ios/Shared/words.json",
]

LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
SEVERITY = {"하": 0, "중": 1, "상": 2, "최상": 3}

# 그룹: P = 패드립·인격 모독, S = 욕설·비속어, A = 외모·체형 비하
CATEGORY_GROUP = {
    "패드립/유전자": "P",
    "욕설/강한 비속어 결합": "S",
    "얼굴/생김새": "A",
    "체형/신체조건": "A",
    "성형/피부/노화": "A",
    "젠더/연령대 결합": "A",
    "인터넷/게임 은어": "A",
}

# ---------------------------------------------------------------------------
# 핵심 토큰: (표현, 위험도, 그룹, 추천 묶음 sg)
#   위험도 0=LOW 1=MEDIUM 2=HIGH 3=CRITICAL
#   sg: 외모 비하일 때 쓰는 맞춤 추천 묶음 (engine 의 SUGGEST_SETS 참고)
# ---------------------------------------------------------------------------
CORE = [
    # 최상위: 욕설
    *[(w, 3, "S", "") for w in [
        "씨발", "시발", "씨빨", "씨팔", "시팔", "씨바", "씨벌", "시벌", "쓰발", "쉬발", "씌발", "쒸발", "씨부랄", "씨부럴",
        "씹새끼", "씹새", "씹년", "씹놈", "씹창", "씹", "개새끼", "개새기", "개색기", "개색끼", "개세끼", "개쌔끼", "개섀끼", "개쉐이",
        "병신", "븅신", "빙신", "병싄", "좆", "좆같", "좆밥", "좆까", "썅", "쌍년", "쌍놈", "미친년", "미친놈", "미친새끼",
        "호로새끼", "후레자식", "엿먹어",
    ]],
    # 최상위: 패드립
    *[(w, 3, "P", "") for w in [
        "느금", "느금마", "느그애미", "니애미", "니에미", "니미", "애미", "애비", "애미뒤진", "애비뒤진", "애미없는", "애비없는",
        "엄마없는", "부모없는", "고아새끼", "호로", "유전자빻았다", "유전자 빻았다",
    ]],
    # 상위: 강한 비속어
    *[(w, 2, "S", "") for w in [
        "존나", "졸라", "존내", "존니", "좃나", "조낸", "지랄", "개지랄", "좆같은", "개같은", "개같", "염병", "닥쳐", "꺼져",
        "또라이", "돌아이", "등신", "머저리", "찐따", "새끼", "새기",
    ]],
    # 상위: 자극적 외모 폄하
    *[(w, 2, "A", "face") for w in ["면상 박살", "와꾸 씹창", "돼지새끼"]],
    ("면상", 1, "A", "face"),
    # 중위: 외모·체형 비하 은어
    *[(w, 1, "A", sg) for w, sg in [
        ("존못", "face"), ("빻았", "face"), ("빻음", "face"), ("못생", "face"), ("와꾸", "face"),
        ("오크", "slang"), ("얼굴 테러", "slang"), ("개못생", "face"),
        ("돼지", "body"), ("뚱땡이", "body"), ("뚱보", "body"), ("뚱돼지", "body"), ("뚱뚱", "body"), ("살쪘", "body"),
        ("살 좀 빼", "body"), ("하체비만", "body"),
        ("멸치", "thin"), ("빼빼", "thin"), ("해골", "thin"), ("젓가락 같", "thin"),
        ("난쟁이", "height"), ("숏다리", "height"), ("땅콩만", "height"), ("키작남", "height"), ("루저", "height"),
        ("대두", "part"), ("얼굴 크", "part"), ("찢어진 눈", "part"), ("사각턱", "part"),
        ("얼굴 왜", "face"), ("얼굴 실화", "face"), ("거울 좀 봐", "face"), ("거울 안 봐", "face"),
        ("머리 왜", "hair"), ("패션 테러", "hair"), ("대머리", "hair"),
        ("성괴", "idol"), ("성형 해", "idol"), ("성형 좀", "idol"), ("코 좀 세워", "idol"), ("반만 닮", "idol"), ("반만 따라", "idol"),
    ]],
    # 하위: 가벼운 외모 평가·비속어
    *[(w, 0, "A", sg) for w, sg in [("오징어", "slang"), ("메주", "slang"), ("얼평", "face"), ("몸평", "body"), ("여드름", "part")]],
    *[(w, 0, "S", "") for w in ["미친"]],
]

# 2글자 욕설은 띄어쓰기를 섞어 쓰는 경우("씨 발련아")를 잡기 위해 대표적인 꼬리말과 붙인 형태도 넣는다.
SWEAR_SUFFIXES = ["놈", "년", "련", "롬", "새끼", "새기", "것", "같은", "같네"]
BODY_SUFFIXES = ["야", "같은", "새끼", "년", "놈"]

# ---------------------------------------------------------------------------
# 변형 입력 → 표준형 (정규식, 바꿀 말). 공백·특수문자·숫자 정리 뒤, 긴 것부터 적용된다.
# ---------------------------------------------------------------------------
VARIANTS = [
    ["씨이+발", "씨발"], ["시이+발", "시발"], ["씨+발", "씨발"],
    ["ㅆㅂ", "씨발"], ["ㅅㅂ", "시발"], ["ㅆ발", "씨발"], ["ㅅ발", "시발"], ["ㅆ바", "씨바"], ["씨ㅂ", "씨발"], ["시ㅂ", "시발"],
    ["c발", "씨발"], ["c바", "씨바"], ["c8", "씨팔"], ["씨8", "씨팔"], ["시8", "시팔"], ["18(?=[놈년련롬새것])", "씨발"],
    ["ssibal", "씨발"], ["shibal", "시발"], ["sibal", "시발"], ["ssiba", "씨바"], ["tlqkf", "시발"], ["tlqk", "시바"],
    ["ㅄ", "병신"], ["ㅂㅅ", "병신"], ["ㅂ신", "병신"], ["병ㅅ", "병신"], ["qudtls", "병신"],
    ["ㅈㄴ", "존나"], ["whssk", "존나"], ["ㅈㄹ", "지랄"], ["wlfkf", "지랄"],
    ["ㄱㅅㄲ", "개새끼"], ["개ㅅㄲ", "개새끼"], ["rotokl", "개새끼"], ["ㅅㄲ", "새끼"],
    ["ㄴㄱㅁ", "느금마"], ["ㄴㄱ마", "느금마"], ["ㅁㅊ", "미친"], ["ㅈ같", "좆같"], ["ㅈ까", "좆까"], ["ㅈ밥", "좆밥"],
    ["ㅈㄴ못생", "존나못생"], ["ㅈ못", "존못"],
]

# ---------------------------------------------------------------------------
# 오탐 예외: 이 말 안에 들어 있는 감지는 무시한다 (띄어쓰기를 지킨 문장에서 비교)
# ---------------------------------------------------------------------------
ALLOW = [
    "시발점", "시발역", "시바견", "시바이누", "새끼손가락", "새끼발가락", "새끼줄", "새끼손톱",
    "돼지고기", "돼지갈비", "돼지국밥", "돼지껍데기", "돼지띠", "돼지저금통", "돼지꿈", "돼지바", "아기돼지", "돼지감자", "돼지불고기",
    "멸치볶음", "멸치육수", "멸치국수", "멸치액젓", "멸치젓", "멸치쌈밥",
    "오징어볶음", "오징어튀김", "오징어게임", "오징어순대", "오징어채", "마른오징어", "오징어덮밥",
    "대두콩", "대두유", "대두단백", "메주콩",
    "씹어", "씹다", "씹고", "씹는", "씹으", "씹을", "씹히", "씹혀", "씹었", "씹던", "곱씹", "씹어먹",
    "미친듯이", "미친척", "등신대", "호로록", "호로요이", "닥쳐오", "닥쳐온", "닥쳐올",
    "불 꺼져", "불이 꺼져", "불꺼져", "꺼져 있", "꺼져있", "꺼져서", "꺼져가", "꺼져 버렸", "전원이 꺼져", "화면이 꺼져",
    "새끼 낳", "새끼를 낳", "새끼 고양이", "새끼 강아지", "강아지 새끼", "고양이 새끼", "새끼 돼지", "새끼 오리",
    "해골물", "와꾸와꾸", "애비뉴",
]


def clean_word(w: str) -> str:
    w = str(w).strip()
    w = re.sub(r"\s*\([^)]*\)", "", w)  # "영포티 (외모폄하형)" → "영포티"
    return re.sub(r"\s+", " ", w).strip()


def key_of(w: str) -> str:
    """띄어쓰기·기호를 뺀 비교용 키 (엔진의 정규화와 같은 규칙)"""
    w = w.lower()
    return "".join(ch for ch in w if re.match(r"[0-9a-z가-힣ㄱ-ㅎㅏ-ㅣ]", ch))


def allow_key(w: str) -> str:
    """오탐 예외 키: 띄어쓰기는 한 칸으로 남긴다 (띄어쓰기를 지킨 문장에서 비교)"""
    return re.sub(r" +", " ", "".join(ch for ch in w.lower() if re.match(r"[0-9a-z가-힣ㄱ-ㅎㅏ-ㅣ ]", ch))).strip()


def main():
    ws = openpyxl.load_workbook(XLSX, read_only=True).active
    header_row = None
    rows = []
    for r in ws.iter_rows(values_only=True):
        if header_row is None:
            if r and r[0] == "번호":
                header_row = r
            continue
        if not r or not r[1]:
            continue
        rows.append(r)
    if header_row is None:
        sys.exit("엑셀에서 '번호' 머리글 줄을 찾지 못했어요.")

    cats, types, descs = [], [], []

    def idx(lst, v):
        if v not in lst:
            lst.append(v)
        return lst.index(v)

    entries = {}

    def add(word, lv, group, cat, typ, desc, sg, src):
        k = key_of(word)
        if not k:
            return
        old = entries.get(k)
        if old and old["lv"] >= lv and old["src"] == "x":
            return  # 엑셀 항목이 이미 있으면 엑셀 정보를 우선
        entries[k] = {
            "w": word, "k": k, "lv": lv, "g": group,
            "c": idx(cats, cat), "t": idx(types, typ), "d": idx(descs, desc) if desc else -1,
            "sg": sg, "src": src,
        }

    for r in rows:
        word = clean_word(r[1])
        cat = str(r[2] or "").strip()
        lv = SEVERITY.get(str(r[4] or "").strip(), 1)
        group = CATEGORY_GROUP.get(cat, "A")
        add(word, lv, group, cat, str(r[3] or "").strip(), str(r[5] or "").strip(), "", "x")

    n_excel = len(entries)
    core_label = {"P": "패드립/유전자", "S": "욕설/강한 비속어", "A": "외모/체형 비하"}
    core_type = {"P": "패드립", "S": "욕설", "A": "은어"}
    for w, lv, g, sg in CORE:
        if key_of(w) not in entries:
            add(w, lv, g, core_label[g], core_type[g], "", sg, "c")
        else:
            e = entries[key_of(w)]
            e["lv"] = max(e["lv"], lv)
            if not e["sg"]:
                e["sg"] = sg
    # 2글자 핵심어 + 꼬리말
    for w, lv, g, sg in CORE:
        k = key_of(w)
        if len(k) != 2:
            continue
        if g in ("S", "P") and lv >= 2:
            for s in SWEAR_SUFFIXES:
                if key_of(w + s) not in entries:
                    add(w + s, lv, g, core_label[g], core_type[g], "", sg, "c")
        if g == "A" and lv >= 1:
            for s in BODY_SUFFIXES:
                if key_of(w + s) not in entries:
                    add(w + s, lv, g, core_label[g], core_type[g], "", sg, "c")

    out_entries = []
    for e in sorted(entries.values(), key=lambda e: (-len(e["k"]), e["k"])):
        out_entries.append([e["w"], e["k"], e["lv"], e["g"], e["c"], e["t"], e["d"], e["sg"]])

    data = {
        "version": 2,
        "source": os.path.basename(XLSX),
        "excel_count": n_excel,
        "levels": LEVELS,
        "fields": ["word", "key", "level", "group", "category", "type", "description", "suggest_set"],
        "categories": cats,
        "types": types,
        "descriptions": descs,
        "entries": out_entries,
        "variants": sorted(VARIANTS, key=lambda v: -len(v[0])),
        "allow": sorted({allow_key(a) for a in ALLOW}, key=lambda a: -len(a)),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    # 브라우저용: <script src="words.js"> 로 불러오면 window.HANBEONDE_WORDS 가 생긴다 (파일로 열어도 작동)
    js_out = os.path.join(ROOT, "engine", "words.js")
    with open(js_out, "w", encoding="utf-8") as f:
        f.write("window.HANBEONDE_WORDS=")
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")
    engine_js = os.path.join(ROOT, "engine", "hanbeonde-engine.js")
    for rel in COPIES:
        dst = os.path.join(ROOT, rel)
        if not os.path.isdir(os.path.dirname(dst)):
            continue
        if rel.startswith(("pc-web", "chrome-extension")):
            # 브라우저용은 words.js + 엔진 JS
            shutil.copyfile(js_out, os.path.join(os.path.dirname(dst), "words.js"))
            shutil.copyfile(engine_js, os.path.join(os.path.dirname(dst), "hanbeonde-engine.js"))
        else:
            shutil.copyfile(OUT, dst)
    # PC 웹 화면을 크롬 확장(대시보드)과 윈도우 앱(대시보드)에도 같이 넣는다
    web = os.path.join(ROOT, "pc-web")
    if os.path.isdir(web):
        for d in ("chrome-extension", "desktop/web"):
            dd = os.path.join(ROOT, d)
            os.makedirs(dd, exist_ok=True)
            shutil.copyfile(os.path.join(web, "index.html"), os.path.join(dd, "dashboard.html" if d == "chrome-extension" else "index.html"))
            for fn in ("app.js", "words.js", "hanbeonde-engine.js"):
                shutil.copyfile(os.path.join(web, fn), os.path.join(dd, fn))
    print(f"엑셀 표현 {n_excel}개 + 핵심·변형 포함 총 {len(out_entries)}개 → {OUT}")


if __name__ == "__main__":
    main()
