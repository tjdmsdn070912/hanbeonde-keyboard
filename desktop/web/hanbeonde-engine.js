/*
 * 한 번 더(Hanbeonde) 감지 엔진 v2 — JavaScript
 * 브라우저(PC 웹, 크롬 확장)와 Node.js 에서 같이 쓴다.
 *
 *   const engine = HanbeondeEngine.create(wordsJson);
 *   engine.analyze("씨발련아")          // 규격 JSON
 *   engine.analyzeDetailed("씨발련아")  // 규격 JSON + 위치·설명(matches)
 *
 * 탐지 순서
 *  1) 정규화: 소문자, 기호·이모지 제거, 한글 사이 숫자/영문 한 글자 제거(씨1발, 씨x발),
 *     한 글자씩 띄어 쓴 부분 붙이기(씨 발 련 아 → 씨발련아)
 *  2) 변형 → 표준형: 초성(ㅆㅂ), 영문(c발, tlqkf), 숫자(18놈) 등
 *  3) 사전 대조: 2글자 이하 표현은 띄어쓰기를 지킨 문장에서, 3글자 이상 표현은 띄어쓰기를 뺀 문장에서 찾는다
 *  4) 오탐 예외(시발점, 돼지고기 …) 제거 → 겹치는 감지 묶기 → 어절 단위로 넓혀 원문 위치 계산
 *  5) 위험도·분류·감정 판단 → 순화안 3개
 * Python(desktop/engine.py), Java(Engine.java), Swift(Engine.swift) 포팅과 결과가 같아야 한다.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.HanbeondeEngine = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
  var GROUP_RANK = { P: 3, S: 2, A: 1 };

  var SUGGEST = {
    P: ["가족 얘기는 하지 말자. 지금 나 많이 화났어", "너한테 정말 서운하고 화가 나", "우리 일은 우리끼리 얘기하자"],
    P_joke: ["야 그건 선 넘었다ㅋㅋ 다른 얘기 하자", "가족 얘기는 장난이어도 좀 그렇다ㅋㅋ", "ㅋㅋㅋ 오늘 왜 이렇게 웃기냐"],
    S_angry: ["너 때문에 정말 속상하고 화가 나", "일이 이렇게 돼서 너무 답답해", "다음부터는 주의해 줬으면 좋겠어"],
    S_frustrated: ["일이 이렇게 돼서 너무 답답해", "아 진짜 마음대로 안 돼서 속상하다", "잠깐 쉬었다가 다시 해 보자"],
    S_joke: ["아 진짜 웃기네ㅋㅋ", "너 오늘 왜 이렇게 웃기냐ㅋㅋ", "야 장난 그만ㅋㅋ"],
    S_surprised: ["와 진짜 대박이다", "헐 진짜?", "완전 놀랐어"],
    A_angry: ["너 때문에 좀 속상해", "그 행동은 나한테 좀 서운했어", "지금 기분이 좀 안 좋아. 이따 얘기하자"],
    A_frustrated: ["아 오늘 일이 잘 안 풀린다", "좀 답답해서 그래", "다음엔 같이 잘해 보자"],
    body: ["오늘 진짜 맛있게 먹더라", "너랑 밥 먹으면 재밌어", "이따 같이 매점 갈래?"],
    face: ["오늘 많이 피곤해 보이는데 괜찮아?", "무슨 일 있었어? 분위기 다르다", "오늘 하루 어땠어?"],
    slang: ["오늘 텐션 뭐야", "너 오늘 말빨 좋다", "오늘 뭔가 기분 좋아 보인다"],
    hair: ["오늘 뭔가 분위기 달라졌다", "헤어 바꿨어? 새롭다", "오늘 스타일 신경 썼네"],
    height: ["너 진짜 날쌔다", "너랑 다니면 재밌어", "오늘 뭐 하고 놀까?"],
    thin: ["밥 같이 먹자", "너 체육 시간에 진짜 잘 뛰더라", "오늘 컨디션 어때?"],
    part: ["요즘 많이 바쁘지?", "잠은 잘 잤어?", "오늘 하루 어땠어?"],
    idol: ["너는 너만의 분위기가 있어", "너 그 노래 진짜 잘 부르더라", "주말에 무대 영상 같이 볼래?"]
  };

  var PARTICLES = "아야이가는은을를들";
  var EMOTION_LABEL = { joke: "장난", frustrated: "답답함", angry: "화남", surprised: "놀람", care: "관심" };

  var RE_LAUGH = /[ㅋㅎ]{2,}|lol|\^\^/i;
  var RE_FRUSTRATED = /답답|에휴|어휴|하아|한숨|미치겠|짜증/;
  var RE_ANGRY = /화나|화난|빡치|빡쳐|열받|꺼져|닥쳐|죽을래|!/;
  var RE_SURPRISED = /헐|대박|\?/;

  function isSyl(c) { var x = c.charCodeAt(0); return x >= 0xac00 && x <= 0xd7a3; }
  function isJamo(c) { var x = c.charCodeAt(0); return x >= 0x3131 && x <= 0x318e; }
  function isHangul(c) { return isSyl(c) || isJamo(c); }
  function isLatin(c) { return c >= "a" && c <= "z"; }
  function isDigit(c) { return c >= "0" && c <= "9"; }
  function isSpace(c) { return c === " " || c === "\t" || c === "\n" || c === "\r" || c === " " || c === "　"; }

  // ---------- 사전(트라이) ----------
  function Trie() { this.root = {}; }
  Trie.prototype.add = function (key, value) {
    var n = this.root;
    for (var i = 0; i < key.length; i++) {
      var c = key[i];
      if (!n[c]) n[c] = {};
      n = n[c];
    }
    n.$ = value;
  };
  /** 각 시작 위치에서 가장 긴 일치 하나씩 */
  Trie.prototype.scan = function (chars) {
    var hits = [];
    for (var i = 0; i < chars.length; i++) {
      var n = this.root, best = -1, bestVal = null;
      for (var j = i; j < chars.length; j++) {
        n = n[chars[j].c];
        if (!n) break;
        if (n.$ !== undefined) { best = j + 1; bestVal = n.$; }
      }
      if (best > 0) hits.push({ i: i, j: best, v: bestVal });
    }
    return hits;
  };

  // ---------- 정규화 ----------
  function normalize(text, variants) {
    var chars = [];
    for (var i = 0; i < text.length; i++) {
      var ch = text[i];
      if (isSpace(ch)) { chars.push({ c: " ", a: i, b: i + 1 }); continue; }
      var lc = ch.toLowerCase();
      if (lc.length === 1 && (isHangul(lc) || isLatin(lc) || isDigit(lc))) chars.push({ c: lc, a: i, b: i + 1 });
    }
    // 한글 사이 숫자(씨1발), 한글 사이 영문 한 글자(씨x발) 제거
    var cleaned = [];
    for (var j = 0; j < chars.length; j++) {
      var cur = chars[j];
      if (isDigit(cur.c) || isLatin(cur.c)) {
        var k = j;
        while (k < chars.length && (isDigit(chars[k].c) || isLatin(chars[k].c))) k++;
        var prev = cleaned.length ? cleaned[cleaned.length - 1] : null;
        var next = k < chars.length ? chars[k] : null;
        var between = prev && next && isHangul(prev.c) && isHangul(next.c);
        var allDigits = true;
        for (var r = j; r < k; r++) if (!isDigit(chars[r].c)) allDigits = false;
        if (!(between && (allDigits || k - j === 1))) for (var q = j; q < k; q++) cleaned.push(chars[q]);
        j = k - 1;
        continue;
      }
      cleaned.push(cur);
    }
    // 어절로 나누고, 한 글자 어절이 이어지면 붙인다
    var tokens = [], tok = [];
    for (var t = 0; t < cleaned.length; t++) {
      if (cleaned[t].c === " ") { if (tok.length) { tokens.push(tok); tok = []; } }
      else tok.push(cleaned[t]);
    }
    if (tok.length) tokens.push(tok);
    var spaced = [], runs = [];
    for (var u = 0; u < tokens.length; u++) {
      // 한 글자 어절이 이어진 구간(씨 발 련 아)은 원문 위치를 기억해 두었다가 강조할 때 통째로 쓴다
      if (tokens[u].length === 1 && u + 1 < tokens.length && tokens[u + 1].length === 1 &&
          !(u > 0 && tokens[u - 1].length === 1)) {
        var run = [];
        for (var x = u; x < tokens.length && tokens[x].length === 1; x++) run.push(tokens[x][0]);
        runs.push(run);
      }
      if (u > 0 && !(tokens[u - 1].length === 1 && tokens[u].length === 1)) {
        spaced.push({ c: " ", a: tokens[u - 1][tokens[u - 1].length - 1].b, b: tokens[u][0].a });
      }
      for (var v = 0; v < tokens[u].length; v++) spaced.push(tokens[u][v]);
    }
    // 변형 → 표준형
    for (var w = 0; w < variants.length; w++) spaced = applyVariant(spaced, variants[w][0], variants[w][1]);
    return { chars: spaced, runs: runs };
  }

  function applyVariant(chars, re, rep) {
    var s = "";
    for (var i = 0; i < chars.length; i++) s += chars[i].c;
    re.lastIndex = 0;
    var m, out = [], last = 0, any = false;
    while ((m = re.exec(s)) !== null) {
      if (m[0].length === 0) { re.lastIndex++; continue; }
      any = true;
      for (var k = last; k < m.index; k++) out.push(chars[k]);
      var a = chars[m.index].a, b = chars[m.index + m[0].length - 1].b;
      for (var r = 0; r < rep.length; r++) out.push({ c: rep[r], a: a, b: b });
      last = m.index + m[0].length;
    }
    if (!any) return chars;
    for (var z = last; z < chars.length; z++) out.push(chars[z]);
    return out;
  }

  // ---------- 엔진 ----------
  function create(data) {
    var entries = data.entries;
    var shortTrie = new Trie(), longTrie = new Trie(), allowTrie = new Trie();
    for (var i = 0; i < entries.length; i++) {
      var key = entries[i][1];
      if (key.length <= 2) shortTrie.add(key, i); else longTrie.add(key, i);
    }
    for (var a = 0; a < data.allow.length; a++) allowTrie.add(data.allow[a], a);
    var variants = data.variants.map(function (v) { return [new RegExp(v[0], "g"), v[1]]; });

    function entryInfo(idx) {
      var e = entries[idx];
      return {
        word: e[0], level: e[2], group: e[3],
        category: data.categories[e[4]], type: data.types[e[5]],
        description: e[6] >= 0 ? data.descriptions[e[6]] : "", sg: e[7]
      };
    }

    function categoryLabel(info) {
      if (info.group === "P") return "패드립 및 인격 모독";
      if (info.group === "S") return info.level >= 3 ? "강한 욕설 및 비속어" : info.level === 2 ? "공격적 비속어" : "비속어";
      switch (info.category) {
        case "얼굴/생김새": return "외모 비하 (얼굴/생김새)";
        case "체형/신체조건": return "외모 비하 (체형/신체조건)";
        case "성형/피부/노화": return "외모 비하 (성형/피부/노화)";
        case "젠더/연령대 결합": return "외모·성별 비하";
        case "인터넷/게임 은어": return "외모 비하 은어";
        default: return "외모·체형 비하";
      }
    }

    function emotionOf(text) {
      if (RE_LAUGH.test(text)) return "joke";
      if (RE_FRUSTRATED.test(text)) return "frustrated";
      if (RE_ANGRY.test(text)) return "angry";
      if (RE_SURPRISED.test(text)) return "surprised";
      return "";
    }

    function suggestionsFor(info, emotion) {
      if (info.group === "P") return SUGGEST[emotion === "joke" ? "P_joke" : "P"].slice();
      if (info.group === "S") {
        var e = emotion || "angry";
        if (e === "care") e = "angry";
        return SUGGEST["S_" + e].slice();
      }
      if (emotion === "angry") return SUGGEST.A_angry.slice();
      if (emotion === "frustrated") return SUGGEST.A_frustrated.slice();
      var set = (SUGGEST[info.sg] || SUGGEST.face).slice();
      if (emotion === "joke") {
        for (var i = 0; i < set.length; i++) if (!/[?ㅋ]$/.test(set[i])) set[i] += "ㅋㅋ";
      }
      return set;
    }

    function analyzeDetailed(input) {
      var text = String(input == null ? "" : input).normalize("NFC");
      var norm = normalize(text, variants);
      var spaced = norm.chars;
      var noSpace = spaced.filter(function (c) { return c.c !== " "; });

      var hits = [];
      shortTrie.scan(spaced).forEach(function (h) { hits.push(toHit(spaced, h)); });
      longTrie.scan(noSpace).forEach(function (h) { hits.push(toHit(noSpace, h)); });
      var allows = allowTrie.scan(spaced).map(function (h) { return { a: spaced[h.i].a, b: spaced[h.j - 1].b }; });
      hits = hits.filter(function (h) {
        for (var i = 0; i < allows.length; i++) if (allows[i].a <= h.a && h.b <= allows[i].b) return false;
        return true;
      });

      // 겹치는 감지 묶기
      hits.sort(function (x, y) { return x.a - y.a || (y.b - y.a) - (x.b - x.a); });
      var clusters = [];
      hits.forEach(function (h) {
        var c = clusters[clusters.length - 1];
        if (c && h.a < c.b) { c.b = Math.max(c.b, h.b); c.hits.push(h); }
        else clusters.push({ a: h.a, b: h.b, hits: [h] });
      });

      // 어절 단위로 넓히고, 같은 어절이면 합친다
      var groups = [];
      clusters.forEach(function (c) {
        var s = c.a, e = c.b;
        // 한 글자씩 띄어 쓴 구간: 감지에 걸린 글자들 + 바로 뒤 조사(아/야…)까지만 강조
        norm.runs.forEach(function (run) {
          var first = -1, last = -1;
          for (var i = 0; i < run.length; i++) if (run[i].a < e && s < run[i].b) { if (first < 0) first = i; last = i; }
          if (first < 0) return;
          while (last + 1 < run.length && PARTICLES.indexOf(run[last + 1].c) >= 0) last++;
          s = Math.min(s, run[first].a); e = Math.max(e, run[last].b);
        });
        while (s > 0 && !isSpace(text[s - 1])) s--;
        while (e < text.length && !isSpace(text[e])) e++;
        while (s < e && /[\s"'“”‘’()[\]{}<>.,!?~…·:;]/.test(text[s])) s++;
        while (e > s && /[\s"'“”‘’()[\]{}<>.,!?~…·:;]/.test(text[e - 1])) e--;
        var g = groups[groups.length - 1];
        if (g && s < g.end) { g.end = Math.max(g.end, e); g.hits = g.hits.concat(c.hits); }
        else groups.push({ start: s, end: e, hits: c.hits.slice() });
      });

      var matches = groups.map(function (g) {
        var best = null;
        g.hits.forEach(function (h) {
          if (!best || h.info.level > best.info.level ||
              (h.info.level === best.info.level && (GROUP_RANK[h.info.group] > GROUP_RANK[best.info.group] ||
                (GROUP_RANK[h.info.group] === GROUP_RANK[best.info.group] && (h.b - h.a) > (best.b - best.a))))) best = h;
        });
        return {
          word: text.slice(g.start, g.end), start: g.start, end: g.end,
          risk_level: LEVELS[best.info.level], level: best.info.level, group: best.info.group,
          category: categoryLabel(best.info), matched: best.info.word, type: best.info.type,
          description: best.info.description, sg: best.info.sg
        };
      });

      if (!matches.length) {
        return {
          is_harmful: false, risk_level: "NONE", detected_words: [], category: null, suggestions: [],
          pc_ui_action: { show_overlay: false, highlight_target: null },
          emotion: null, matches: []
        };
      }
      var top = matches[0];
      matches.forEach(function (m) {
        if (m.level > top.level || (m.level === top.level && GROUP_RANK[m.group] > GROUP_RANK[top.group])) top = m;
      });
      var emotion = emotionOf(text);
      var shownEmotion = emotion || (top.group === "A" ? "care" : "angry");
      var words = [];
      matches.forEach(function (m) { if (words.indexOf(m.word) < 0) words.push(m.word); });
      return {
        is_harmful: true,
        risk_level: top.risk_level,
        detected_words: words,
        category: top.category,
        suggestions: suggestionsFor({ group: top.group, sg: top.sg }, emotion),
        pc_ui_action: { show_overlay: top.level >= 1, highlight_target: top.word },
        emotion: EMOTION_LABEL[shownEmotion],
        matches: matches.map(function (m) {
          return {
            word: m.word, start: m.start, end: m.end, risk_level: m.risk_level, category: m.category,
            matched: m.matched, type: m.type, description: m.description
          };
        })
      };
    }

    function toHit(chars, h) {
      return { a: chars[h.i].a, b: chars[h.j - 1].b, info: entryInfo(h.v) };
    }

    /** 요구 규격 그대로의 JSON */
    function analyze(text) {
      var d = analyzeDetailed(text);
      return {
        is_harmful: d.is_harmful,
        risk_level: d.risk_level,
        detected_words: d.detected_words,
        category: d.category,
        suggestions: d.suggestions,
        pc_ui_action: d.pc_ui_action
      };
    }

    return {
      analyze: analyze,
      analyzeDetailed: analyzeDetailed,
      entryCount: entries.length,
      excelCount: data.excel_count
    };
  }

  return { create: create, LEVELS: LEVELS };
});
