/*
 * 한 번 더 — 크롬 확장 콘텐츠 스크립트
 * 웹 메신저(디스코드, 슬랙, 인스타그램 DM, 웹 메일 등)의 입력창을 지켜보다가
 *  - 입력 중: 입력창 오른쪽 아래에 위험도 알약(pill)을 띄운다
 *  - 손을 0.8초 멈추면: 경고 오버레이 위젯 + 순화안 3개
 *  - Enter를 누르면: 위험한 문장은 전송을 한 번 멈추고 오버레이를 띄운다
 * 모든 분석은 이 컴퓨터의 브라우저 안에서만 한다. 문장은 저장하지도 보내지도 않는다.
 */
(function () {
  "use strict";
  if (window.__hanbeondeLoaded) return;
  window.__hanbeondeLoaded = true;
  if (!window.HANBEONDE_WORDS || !window.HanbeondeEngine) return;

  var engine = HanbeondeEngine.create(window.HANBEONDE_WORDS);
  var KEY = "hanbeonde-pc-v2", SKEY = "hanbeonde-settings";
  var LV = { CRITICAL: 3, HIGH: 2, MEDIUM: 1, LOW: 0, NONE: -1 };
  var settings = { enabled: true, overlay: true, low: false, off: [] };

  chrome.storage.local.get(SKEY, function (r) { Object.assign(settings, r[SKEY] || {}); });
  chrome.storage.onChanged.addListener(function (ch, area) {
    if (area === "local" && ch[SKEY]) Object.assign(settings, ch[SKEY].newValue || {});
  });
  function active() {
    return settings.enabled !== false && (settings.off || []).indexOf(location.hostname) < 0;
  }
  function threshold() { return settings.low ? 0 : 1; }

  // ---------- 기록 (횟수와 표현만) ----------
  function log(type, word, lv) {
    chrome.storage.local.get(KEY, function (r) {
      var store = r[KEY] || { events: [], badges: [], seeded: false };
      store.events.push({ t: Date.now(), type: type, w: word || "", lv: lv == null ? -1 : lv });
      if (store.events.length > 5000) store.events = store.events.slice(-5000);
      var o = {}; o[KEY] = store;
      chrome.storage.local.set(o);
    });
  }

  // ---------- 입력창 ----------
  function editableOf(node) {
    var el = node && node.nodeType === 1 ? node : node && node.parentElement;
    if (!el) return null;
    if (el.tagName === "TEXTAREA") return el;
    if (el.tagName === "INPUT") {
      var t = (el.getAttribute("type") || "text").toLowerCase();
      return (t === "text" || t === "search") ? el : null;
    }
    var ce = el.closest && el.closest('[contenteditable="true"],[contenteditable=""],[contenteditable="plaintext-only"]');
    return ce || null;
  }
  function getText(el) {
    if (el.tagName === "TEXTAREA" || el.tagName === "INPUT") return el.value;
    return el.innerText.replace(/\n$/, "");
  }
  function setText(el, s) {
    el.focus();
    if (el.tagName === "TEXTAREA" || el.tagName === "INPUT") {
      var proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, s); // React 입력창도 값이 바뀌게
      el.dispatchEvent(new Event("input", { bubbles: true }));
      return;
    }
    var sel = window.getSelection(), range = document.createRange();
    range.selectNodeContents(el);
    sel.removeAllRanges();
    sel.addRange(range);
    if (!document.execCommand("insertText", false, s)) el.textContent = s;
  }

  // ---------- 화면 (Shadow DOM: 사이트 스타일과 섞이지 않게) ----------
  var host = document.createElement("hanbeonde-ui");
  host.style.cssText = "all:initial;position:fixed;z-index:2147483647;top:0;left:0;width:0;height:0";
  var root = host.attachShadow({ mode: "closed" });
  root.innerHTML = '<style>' +
    ':host{all:initial}*{box-sizing:border-box;font-family:"IBM Plex Sans KR","Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif}' +
    '.pill{position:fixed;display:none;align-items:center;gap:6px;font-size:12px;font-weight:700;padding:4px 10px;border-radius:999px;border:0;cursor:pointer;box-shadow:0 4px 12px rgba(0,0,0,.18);max-width:260px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '.pill::before{content:"";width:7px;height:7px;border-radius:50%;background:currentColor;flex:none}' +
    '.CRITICAL{color:#c62828;background:#fdeaea}.HIGH{color:#d9480f;background:#fff0e6}.MEDIUM{color:#9a6412;background:#fdf5e3}.LOW{color:#5f6f6b;background:#eef1f0}' +
    '.ov{position:fixed;display:none;width:360px;background:#fff;color:#16201e;border:1px solid #d8e0de;border-radius:14px;box-shadow:0 18px 40px -12px rgba(10,30,26,.4);padding:14px 16px;font-size:14px}' +
    '.top{display:flex;align-items:center;gap:8px}.top strong{font-size:15px}' +
    '.badge{font-size:11.5px;font-weight:700;padding:3px 9px;border-radius:999px}' +
    '.x{margin-left:auto;border:0;background:none;color:#7c8784;font-size:18px;cursor:pointer;line-height:1}' +
    '.sub{font-size:12.5px;color:#4d5a57;margin:6px 0 10px;line-height:1.5}.sub mark{background:none;color:#c62828;font-weight:700}' +
    '.chips{display:flex;flex-direction:column;gap:6px}' +
    '.chip{border:1px solid #bcd9d4;background:#f2faf8;color:#0e6b63;border-radius:999px;padding:7px 13px;font-size:13.5px;text-align:left;cursor:pointer}' +
    '.chip:hover,.chip:focus-visible{background:#0e6b63;color:#fff;outline:none}' +
    '.acts{display:flex;gap:8px;margin-top:10px}.acts button{flex:1;border:1px solid #d8e0de;background:#fff;border-radius:9px;padding:7px;font-size:13px;cursor:pointer;color:#16201e}' +
    '.acts .ghost{color:#4d5a57}.brand{font-size:11px;color:#7c8784;margin-top:8px}' +
    '.toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);background:#16201e;color:#fff;padding:9px 14px;border-radius:10px;font-size:13px;display:none}' +
    '</style>' +
    '<button class="pill" id="pill"></button>' +
    '<div class="ov" id="ov" role="dialog" aria-label="한 번 더 경고"><div class="top"><span class="badge" id="badge"></span><strong>잠깐! 상대방이 상처받을 수 있어요</strong><button class="x" id="x" aria-label="닫기">×</button></div>' +
    '<div class="sub" id="sub"></div><div class="chips" id="chips"></div>' +
    '<div class="acts"><button id="edit">직접 고칠게요</button><button class="ghost" id="keep">그대로 보내기</button></div><div class="brand">한 번 더 · 이 컴퓨터 안에서만 분석해요</div></div>' +
    '<div class="toast" id="toast"></div>';
  (document.body || document.documentElement).appendChild(host);
  var $ = function (id) { return root.getElementById(id); };

  var current = null;      // 지금 보고 있는 입력창
  var result = null;       // 마지막 분석 결과
  var fromEnter = false;
  var bypassText = null;   // '그대로 보내기'를 고른 문장
  var pausedText = null;
  var session = null;      // { word, lv, changed }
  var timer = null, pauseTimer = null;

  function place(el, node, w, h, above) {
    var r = el.getBoundingClientRect();
    var x = Math.min(Math.max(8, r.right - w), window.innerWidth - w - 8);
    var y = above ? r.top - h - 10 : r.bottom - 26;
    if (above && y < 8) y = Math.min(window.innerHeight - h - 8, r.bottom + 10);
    node.style.left = x + "px";
    node.style.top = Math.max(8, y) + "px";
  }

  function analyzeNow() {
    if (!current || !active()) return hidePill();
    var text = getText(current);
    result = engine.analyzeDetailed(text);
    result.text = text;
    if (result.is_harmful && !session) session = { word: result.matches[0].word, lv: LV[result.risk_level], changed: false };
    if (!result.is_harmful) { hideOverlay(); hidePill(); return; }
    var pill = $("pill");
    pill.className = "pill " + result.risk_level;
    pill.textContent = result.risk_level + " · " + result.detected_words.join(", ");
    pill.style.display = "inline-flex";
    place(current, pill, Math.min(260, pill.offsetWidth || 200), 24, false);
  }
  function hidePill() { $("pill").style.display = "none"; }

  function showOverlay(enter) {
    if (!result || !result.is_harmful) return;
    fromEnter = enter;
    var top = result.matches[0];
    result.matches.forEach(function (m) { if (LV[m.risk_level] > LV[top.risk_level]) top = m; });
    $("badge").className = "badge " + result.risk_level;
    $("badge").textContent = result.risk_level;
    $("sub").innerHTML = "<mark></mark> · " + "<span></span>";
    $("sub").querySelector("mark").textContent = top.word;
    $("sub").querySelector("span").textContent = result.category + ". 원래 하려던 " + result.emotion + "의 마음은 살리고 공격적인 말만 빼 볼까요?";
    var chips = $("chips");
    chips.innerHTML = "";
    result.suggestions.forEach(function (s) {
      var b = document.createElement("button");
      b.className = "chip";
      b.textContent = s;
      b.addEventListener("click", function () { choose(s); });
      chips.appendChild(b);
    });
    $("keep").textContent = enter ? "그대로 보내기" : "그대로 쓸게요";
    var ov = $("ov");
    ov.style.display = "block";
    place(current, ov, 360, ov.offsetHeight || 260, true);
  }
  function hideOverlay() { $("ov").style.display = "none"; }

  function toast(t) {
    var el = $("toast");
    el.textContent = t;
    el.style.display = "block";
    clearTimeout(toast.t);
    toast.t = setTimeout(function () { el.style.display = "none"; }, 2400);
  }

  function choose(s) {
    if (!current) return;
    log("detect", result.matches[0].word, LV[result.risk_level]);
    log("change", result.matches[0].word, LV[result.risk_level]);
    if (session) session.changed = true;
    setText(current, s);
    hideOverlay();
    toast(fromEnter ? "순화된 문장으로 바꿨어요. Enter를 누르면 보내져요" : "순화된 문장으로 바꿨어요");
    setTimeout(analyzeNow, 50);
  }

  $("x").addEventListener("click", function () { hideOverlay(); if (current) current.focus(); });
  $("edit").addEventListener("click", function () { hideOverlay(); if (current) current.focus(); });
  $("keep").addEventListener("click", function () {
    hideOverlay();
    if (!current) return;
    bypassText = getText(current);
    current.focus();
    if (fromEnter) toast("Enter를 한 번 더 누르면 그대로 보내져요");
  });
  $("pill").addEventListener("click", function () { analyzeNow(); showOverlay(false); });

  // ---------- 이벤트 ----------
  document.addEventListener("focusin", function (e) {
    var el = editableOf(e.target);
    if (el && el !== current) { current = el; session = null; bypassText = null; analyzeNow(); }
  }, true);

  document.addEventListener("input", function (e) {
    var el = editableOf(e.target);
    if (!el || !active()) return;
    current = el;
    clearTimeout(timer);
    timer = setTimeout(analyzeNow, 150);
    clearTimeout(pauseTimer);
    pauseTimer = setTimeout(function () {
      analyzeNow();
      if (!settings.overlay || !result || !result.is_harmful || LV[result.risk_level] < threshold()) return;
      if (pausedText === result.text || bypassText === result.text) return;
      pausedText = result.text;
      showOverlay(false);
    }, 800);
  }, true);

  // Enter: 사이트가 메시지를 보내기 전에 먼저 가로챈다 (capture 단계)
  window.addEventListener("keydown", function (e) {
    if (e.key === "Escape" && $("ov").style.display === "block") { hideOverlay(); return; }
    if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
    var el = editableOf(e.target);
    if (!el || !active()) return;
    current = el;
    var text = getText(el);
    if (!text.trim()) return;
    result = engine.analyzeDetailed(text);
    result.text = text;
    var harmful = result.is_harmful && LV[result.risk_level] >= threshold();
    if (harmful && settings.overlay && bypassText !== text) {
      e.preventDefault();
      e.stopImmediatePropagation();
      showOverlay(true);
      return;
    }
    // 전송되는 것으로 보고 기록한다
    if (result.is_harmful) {
      log("detect", result.matches[0].word, LV[result.risk_level]);
      log("keep", result.matches[0].word, LV[result.risk_level]);
    } else if (session && !session.changed) {
      log("detect", session.word, session.lv);
      log("change", session.word, session.lv);
    } else if (/수고|고생|대단|잘했|열심히|고마워|덕분|노력|잘하더라|최고야/.test(text)) {
      log("praise");
    }
    log("sent");
    session = null;
    bypassText = null;
    pausedText = null;
    hideOverlay();
    setTimeout(analyzeNow, 120);
  }, true);

  window.addEventListener("scroll", function () { if (current && $("pill").style.display !== "none") analyzeNow(); }, true);
  window.addEventListener("resize", function () { if (current) analyzeNow(); });
})();
