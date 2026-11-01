(function () {
  "use strict";
  var KEY = "hanbeonde-pc-v2", SKEY = "hanbeonde-settings", DAY = 864e5;
  var settings = { enabled: true, overlay: true, low: false, off: [] };
  var host = "";
  function $(id) { return document.getElementById(id); }
  function save() { var o = {}; o[SKEY] = settings; chrome.storage.local.set(o); }

  chrome.tabs && chrome.tabs.query({ active: true, currentWindow: true }, function (tabs) {
    try { host = new URL(tabs[0].url).hostname; } catch (e) { host = ""; }
    $("host").textContent = host || "이 페이지에서는 쓸 수 없어요";
    $("site").disabled = !host;
    $("site").checked = !!host && settings.off.indexOf(host) < 0;
  });

  chrome.storage.local.get([KEY, SKEY], function (r) {
    Object.assign(settings, r[SKEY] || {});
    settings.off = settings.off || [];
    $("enabled").checked = settings.enabled !== false;
    $("overlay").checked = settings.overlay !== false;
    $("low").checked = !!settings.low;
    $("site").checked = !!host && settings.off.indexOf(host) < 0;
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var d = 0, c = 0;
    ((r[KEY] || {}).events || []).forEach(function (e) {
      var day = new Date(e.t); day.setHours(0, 0, 0, 0);
      if ((today - day) / DAY < 7) { if (e.type === "detect") d++; if (e.type === "change") c++; }
    });
    $("k-detect").textContent = d + "회";
    $("k-rate").textContent = (d ? Math.round(c / d * 100) : 0) + "%";
  });

  $("enabled").addEventListener("change", function (e) { settings.enabled = e.target.checked; save(); });
  $("overlay").addEventListener("change", function (e) { settings.overlay = e.target.checked; save(); });
  $("low").addEventListener("change", function (e) { settings.low = e.target.checked; save(); });
  $("site").addEventListener("change", function (e) {
    settings.off = settings.off.filter(function (h) { return h !== host; });
    if (!e.target.checked && host) settings.off.push(host);
    save();
  });
  $("open").addEventListener("click", function () { chrome.runtime.openOptionsPage(); });
})();
