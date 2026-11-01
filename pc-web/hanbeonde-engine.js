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

  // ---------- 메시지 맞춤 순화 ----------
  // "야이 개새끼야 너 때문에 약속 시간 늦었잖아" → "아니 너 때문에 약속 시간 늦었잖아 ㅜㅜ"
  var INTENS = ["존나", "졸라", "존내", "존니", "좃나", "조낸"];          // 강조로 쓰인 비속어 → "진짜"
  var INTENS_SURF = ["존나", "졸라", "존내", "존니", "좃나", "조낸", "ㅈㄴ", "whssk"];
  var INTERJ = ["야", "이", "야이", "이런", "저런", "아오", "에이", "아씨", "저"]; // 욕 바로 앞의 부르는 말
  var APPEAR_PRED = ["생겼", "같이", "같아", "같다", "같네", "같은", "닮", "처럼"]; // 외모 비하 뒤에 붙는 서술어
  var DANGLING = ["진짜", "완전", "너무", "개", "좀", "왜", "너", "니", "넌", "진심", "약간", "되게", "엄청", "아주", "그냥"];
  var EDGE = "\"'“”‘’()[]{}<>.,!~…·:;";
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

    function stripEdge(s) {
      var a = 0, b = s.length;
      while (a < b && (EDGE.indexOf(s[a]) >= 0 || s[a] === "?")) a++;
      while (b > a && EDGE.indexOf(s[b - 1]) >= 0) b--;
      return s.slice(a, b);
    }
    function hangulCount(s) { var n = 0; for (var i = 0; i < s.length; i++) if (isSyl(s[i])) n++; return n; }
    function startsWithWord(c, w) { return c.indexOf(w) === 0; }
    /** 문장 앞에 말을 붙이되, 이미 비슷한 말로 시작하면 그대로 둔다 */
    function prefixed(p, c, alts) {
      var first = c.split(" ")[0].replace(/,+$/, "");
      var all = [p.replace(/[ ,]+$/, "")].concat(alts || []);
      for (var i = 0; i < all.length; i++) if (first === all[i]) return c;
      return p + c;
    }
    /** 상대에게 하는 말인지 (너/넌/니가/네가 …) */
    function addressed(c) {
      var w = c.split(" ");
      for (var i = 0; i < w.length; i++) {
        if (/^(너|넌|널|니가|네가|니네|니도|니꺼|니거)/.test(w[i])) return true;
      }
      return /(냐|잖아|하지 ?마|해라|줄래|줘)[?!]*$/.test(c);   // 따지거나 부탁하는 말끝
    }
    function joined(c, tail) { return /[?!]$/.test(c) ? c + " " + tail : c + ". " + tail; }

    /** 보내려던 메시지에서 공격적인 말만 빼고 다듬은 문장 3개. 문장이 성립하지 않으면 null */
    function contextualSuggestions(text, matches, group, emotion) {
      var toks = [], i = 0, n = text.length;
      while (i < n) {
        while (i < n && isSpace(text[i])) i++;
        if (i >= n) break;
        var s = i;
        while (i < n && !isSpace(text[i])) i++;
        toks.push({ s: s, e: i, t: text.slice(s, i), st: "keep", rep: "" });
      }
      toks.forEach(function (tk) {
        matches.forEach(function (m) {
          if (!(m.start < tk.e && tk.s < m.end) || tk.st === "bad") return;
          if (m.start >= tk.s && m.end <= tk.e && INTENS.indexOf(m.matched) >= 0) {
            var core = stripEdge(tk.t), low = core.toLowerCase(), rep = "진짜";
            for (var p = 0; p < INTENS_SURF.length; p++) {
              if (low.indexOf(INTENS_SURF[p]) === 0) {
                var rest = core.slice(INTENS_SURF[p].length);
                rep = rest ? "진짜 " + rest : "진짜";
                break;
              }
            }
            tk.st = "soft";
            tk.rep = rep;
          } else {
            tk.st = "bad";
          }
        });
      });
      var incomplete = false;
      for (var k = 0; k < toks.length; k++) {
        if (toks[k].st !== "bad") continue;
        for (var j = k - 1; j >= 0 && toks[j].st === "keep" && INTERJ.indexOf(stripEdge(toks[j].t)) >= 0; j--) toks[j].st = "drop";
        var nx = toks[k + 1];
        if (nx && nx.st === "keep") {
          var nt = stripEdge(nx.t);
          for (var q = 0; q < APPEAR_PRED.length; q++) if (nt.indexOf(APPEAR_PRED[q]) === 0) { nx.st = "drop"; incomplete = true; break; }
        }
      }
      if (incomplete) return null;
      var lastIdx = -1;
      for (var z = toks.length - 1; z >= 0; z--) if (stripEdge(toks[z].t)) { lastIdx = z; break; }
      if (lastIdx >= 0 && toks[lastIdx].st === "bad" && group === "A") return null;
      var parts = [];
      toks.forEach(function (tk) { if (tk.st === "keep") parts.push(tk.t); else if (tk.st === "soft") parts.push(tk.rep); });
      var core = parts.join(" ").replace(/[\sㅋㅎㅠㅜ.,!~…]+$/, "").replace(/^[\s.,!~…]+/, "");
      if (hangulCount(core) < 2) return null;
      var words = core.split(" ");
      if (DANGLING.indexOf(words[words.length - 1].replace(/[?]+$/, "")) >= 0) return null;

      var hasBad = toks.some(function (tk) { return tk.st === "bad"; });
      var e = emotion || (group === "A" || !hasBad ? "care" : "angry");
      if (e === "surprised" && !/헐|대박/.test(text)) e = "ask";   // 물음표만 있으면 놀람이 아니라 질문
      if (e === "angry" && !addressed(core)) e = "frustrated";      // 혼잣말 욕은 화풀이보다 답답함
      var out;
      if (e === "frustrated") out = [prefixed("아 ", core, ["아", "하아", "하", "에휴", "어휴", "아오"]) + " ㅠㅠ", core + (/[?!]$/.test(core) ? " " : "... ") + "너무 답답하다", joined(core, "좀 쉬었다 해야겠다")];
      else if (e === "joke") out = [core + "ㅋㅋ", prefixed("야 ", core) + "ㅋㅋ", core + " ㅎㅎ"];
      else if (e === "surprised") out = [prefixed("헐 ", core, ["와", "대박"]), joined(core, "진짜 놀랐어"), joined(core, "완전 신기하다")];
      else if (e === "ask") out = [core, prefixed("근데 ", core), joined(core, "궁금해서")];
      else if (e === "care") out = [core, prefixed("있잖아, ", core), core + " ㅎㅎ"];
      else out = [prefixed("아니 ", core, ["아"]) + " ㅜㅜ", joined(core, "나 좀 속상해"), joined(core, "다음엔 조심해 줘")];
      if (group === "P") out[1] = joined(core, e === "joke" ? "가족 얘기는 빼자ㅋㅋ" : "가족 얘기는 하지 말자");
      var uniq = [];
      out.forEach(function (x) { if (uniq.indexOf(x) < 0) uniq.push(x); });
      return uniq;
    }

    /** 메시지 맞춤 순화안을 먼저, 모자라면 기본 순화안으로 3개를 채운다 */
    function mergeSuggestions(ctx, base) {
      var out = (ctx || []).slice();
      for (var i = 0; i < base.length && out.length < 3; i++) if (out.indexOf(base[i]) < 0) out.push(base[i]);
      return out.slice(0, 3);
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
        suggestions: mergeSuggestions(contextualSuggestions(text, matches, top.group, emotion), suggestionsFor({ group: top.group, sg: top.sg }, emotion)),
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
