(function(){
"use strict";
var W = window.HANBEONDE_WORDS;
var engine = HanbeondeEngine.create(W);
var $ = function(id){ return document.getElementById(id); };
var LV = {CRITICAL:3,HIGH:2,MEDIUM:1,LOW:0,NONE:-1};
var LV_KO = {CRITICAL:"최상위 경고",HIGH:"상위 경고",MEDIUM:"중위 경고",LOW:"가벼운 표현",NONE:"안전"};
function esc(t){ return String(t).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c];}); }
function marked(text, matches){
  var o="", i=0;
  matches.forEach(function(m){ o+=esc(text.slice(i,m.start))+'<mark class="'+m.risk_level+'">'+esc(text.slice(m.start,m.end))+"</mark>"; i=m.end; });
  return o+esc(text.slice(i));
}
var settings={overlay:true, low:false};
function alertLevel(){ return settings.low ? 0 : 1; }

// ---------- 기록 (이 브라우저 안에만) ----------
var KEY="hanbeonde-pc-v2", DAY=864e5;
function sod(t){ var d=new Date(t); d.setHours(0,0,0,0); return d.getTime(); }
var TODAY=sod(Date.now());
function seed(){
  var ev=[], prev=[["씨발련아",3,0],["존나",2,1],["돼지야",1,1],["병신",3,0],["느금마",3,0],["존못",1,1],["지랄",2,0],["오크",1,1],["개새끼야",3,1],["존나",2,1],["멸치",1,0],["와꾸 씹창",3,0]];
  prev.forEach(function(p,i){ var t=TODAY-(8+i%6)*DAY+10*36e5; ev.push({t:t,type:"detect",w:p[0],lv:p[1]}); ev.push({t:t+1,type:p[2]?"change":"keep",w:p[0],lv:p[1]}); });
  var cur=[[6,"존나",2,1],[5,"돼지야",1,1],[4,"지랄",2,0],[2,"씨발",3,1],[1,"존나",2,1]];
  cur.forEach(function(p){ var t=TODAY-p[0]*DAY+15*36e5; ev.push({t:t,type:"detect",w:p[1],lv:p[2]}); ev.push({t:t+1,type:p[3]?"change":"keep",w:p[1],lv:p[2]}); });
  ev.push({t:TODAY-3*DAY+16*36e5,type:"praise"});
  return {events:ev,badges:[],seeded:true};
}
// 크롬 확장 안에서 열리면 확장 프로그램 저장소(chrome.storage)를 같이 쓴다
var EXT=!!(window.chrome && chrome.runtime && chrome.runtime.id && chrome.storage && chrome.storage.local);
var SKEY="hanbeonde-settings", store, currentView="checker";
function save(){
  if(EXT){ var o={}; o[KEY]=store; chrome.storage.local.set(o); return; }
  try{ localStorage.setItem(KEY,JSON.stringify(store)); }catch(e){}
}
function saveSettings(){ if(EXT){ var o={}; o[SKEY]=settings; chrome.storage.local.set(o); } }
function log(type,w,lv){ store.events.push({t:Date.now(),type:type,w:w||"",lv:lv==null?-1:lv}); save(); checkBadges(); }

// ---------- 1. 텍스트 검사기 ----------
var ed=$("ed"), edBack=$("ed-back"), panel=$("panel"), showJson=false, lastChecker=null;
var SAMPLES=["씨발련아","씨 발 련 아","ㅆㅂ련 진짜 짜증나","c발 뭐하냐","느금마 생김새 돼지","너 진짜 돼지야ㅋㅋ","와꾸 씹창남","발표 준비 열심히 했더라"];
$("samples").innerHTML='<span style="color:var(--ink-3)">예시</span>'+SAMPLES.map(function(s,i){return '<button class="sample" data-i="'+i+'">'+esc(s)+'</button>';}).join("");
$("samples").addEventListener("click",function(e){ var b=e.target.closest(".sample"); if(!b) return; ed.value=SAMPLES[+b.dataset.i]; runChecker(); ed.focus(); });
ed.value="야 이 씨 발 련 아 너 진짜 돼지같이 생겼네ㅋㅋ";
var tmr; ed.addEventListener("input",function(){ syncBack(ed,edBack,lastChecker); clearTimeout(tmr); tmr=setTimeout(runChecker,120); });
ed.addEventListener("scroll",function(){ edBack.scrollTop=ed.scrollTop; });
function syncBack(ta,back,res){ back.innerHTML=(res&&res.text===ta.value?marked(ta.value,res.matches):esc(ta.value))+(ta.value.endsWith("\n")?" ":""); }
function runChecker(){
  var text=ed.value, r=engine.analyzeDetailed(text); r.text=text; lastChecker=r;
  syncBack(ed,edBack,r);
  $("char-count").textContent=text.length+"자";
  renderPanel(r);
}
function renderPanel(r){
  var lv=r.risk_level;
  var left='<div><h3>검사 결과</h3><div class="status-line"><span class="badge lv-'+lv+'">'+lv+'</span><span class="cat">'+(r.is_harmful?esc(r.category):"상처 줄 표현이 없어요")+'</span>'+(r.is_harmful?'<span class="emo">· '+LV_KO[lv]+' · 감정: '+esc(r.emotion)+'</span>':"")+'</div>';
  if(r.is_harmful){
    left+='<div class="found">'+r.matches.map(function(m){return '<span class="w lv-'+m.risk_level+'">'+esc(m.word)+'<small>'+m.risk_level+'</small></span>';}).join("")+'</div>';
    var d=r.matches.filter(function(m){return m.description;})[0];
    left+='<div class="desc">'+(d?"‘"+esc(d.matched)+"’: "+esc(d.description):"‘"+esc(r.matches[0].matched)+"’ 표현이 사전에 있어요.")+'</div>';
  } else left+='<p class="safe-note">이대로 보내도 좋아요. 칭찬을 더하고 싶다면 “준비 열심히 했더라”처럼 상대가 한 일을 말해 보세요.</p>';
  left+='<button class="json-toggle" id="json-toggle">'+(showJson?"JSON 결과 숨기기":"JSON 결과 보기")+'</button>'+(showJson?'<pre class="json">'+esc(JSON.stringify(engine.analyze(r.text),null,2))+'</pre>':"")+'</div>';
  var right='<div><h3>'+(r.is_harmful?"이렇게 바꿔 보면 어때요? 누르면 문장이 바뀌어요":"순화 제안")+'</h3>';
  right+= r.is_harmful ? '<div class="swaps">'+r.suggestions.map(function(s,i){return '<button class="swap" data-i="'+i+'"><span>'+esc(s)+'</span><b>이 문장으로 교체</b></button>';}).join("")+'</div>' : '<p class="safe-note">바꿀 부분이 없어요.</p>';
  panel.innerHTML=left+right+"</div>";
}
panel.addEventListener("click",function(e){
  if(e.target.id==="json-toggle"){ showJson=!showJson; renderPanel(lastChecker); return; }
  var b=e.target.closest(".swap"); if(!b) return;
  var r=lastChecker; log("detect",r.matches[0].word,LV[r.risk_level]); log("change",r.matches[0].word,LV[r.risk_level]);
  ed.value=r.suggestions[+b.dataset.i]; runChecker(); toast("순화된 문장으로 바꿨어요"); ed.focus();
});

// ---------- 2. 메신저 + 오버레이 ----------
var FR={"민준":"#3f6fb5","서연":"#b5527a","지호":"#5b8a3a","하은":"#8a5bb5"};
var msgs=[{who:"민준",text:"오늘 PC방 갈 사람?"},{who:"서연",text:"나 오늘 머리 잘랐다 ㅋㅋ 어때"},{who:"지호",text:"아 과제 또 날렸어;; 진짜 짜증나"},{who:"하은",text:"수행평가 발표 끝났다ㅠ 진짜 떨렸어"}];
var ms=$("ms"), msBack=$("ms-back"), ov=$("overlay"), msRes=null, pauseT=null, pausedFor="", keepOnce=false;
function renderMsgs(){
  $("msgs").innerHTML=msgs.map(function(m){
    if(m.me) return '<div class="m me"><div><div class="bub'+(m.kept?" kept":"")+'">'+esc(m.text)+'</div>'+(m.note?'<div class="note">'+m.note+'</div>':"")+'</div></div>';
    return '<div class="m"><div class="av" style="background:'+FR[m.who]+'">'+m.who[0]+'</div><div><div class="who">'+m.who+'</div><div class="bub">'+esc(m.text)+'</div></div></div>';
  }).join("");
  $("msgs").scrollTop=$("msgs").scrollHeight;
}
function msUpdate(){
  var text=ms.value; msRes=engine.analyzeDetailed(text); msRes.text=text;
  syncBack(ms,msBack,msRes);
  ms.style.height="auto"; ms.style.height=Math.min(ms.scrollHeight,120)+"px";
  $("ms-send").disabled=!text.trim();
  if(!msRes.is_harmful || LV[msRes.risk_level]<alertLevel()){ hideOverlay(); return; }
}
ms.addEventListener("input",function(){
  msUpdate(); keepOnce=false;
  clearTimeout(pauseT);
  // 손을 멈추면(0.8초) 검사 결과를 오버레이로
  pauseT=setTimeout(function(){
    if(msRes && msRes.is_harmful && LV[msRes.risk_level]>=alertLevel() && settings.overlay && pausedFor!==ms.value){ pausedFor=ms.value; showOverlay(msRes,false); }
  },800);
});
ms.addEventListener("scroll",function(){ msBack.scrollTop=ms.scrollTop; });
ms.addEventListener("keydown",function(e){
  if(e.key==="Enter" && !e.shiftKey && !e.isComposing){ e.preventDefault(); trySend(); }
  if(e.key==="Escape" && ov.classList.contains("on")){ hideOverlay(); }
});
$("ms-send").addEventListener("click",trySend);
var editedFrom=null;
function trySend(){
  var text=ms.value.trim(); if(!text) return;
  msUpdate();
  if(msRes.is_harmful && LV[msRes.risk_level]>=alertLevel() && settings.overlay && !keepOnce){ showOverlay(msRes,true); return; }
  if(msRes.is_harmful){ log("detect",msRes.matches[0].word,LV[msRes.risk_level]); log("keep",msRes.matches[0].word,LV[msRes.risk_level]); post(text,{kept:true}); return; }
  if(editedFrom){ log("detect",editedFrom.w,editedFrom.lv); log("change",editedFrom.w,editedFrom.lv); editedFrom=null; post(text,{note:"직접 고쳐서 보냄"}); return; }
  post(text,{});
}
var PRAISE=/수고|고생|대단|잘했|열심히|고마워|덕분|노력|잘하더라|최고야/;
function post(text,o){
  msgs.push({me:1,text:text,kept:o.kept,note:o.note});
  ms.value=""; keepOnce=false; pausedFor=""; msUpdate(); renderMsgs(); hideOverlay();
  if(!o.kept && PRAISE.test(text)) log("praise");
  log("sent");
  setTimeout(function(){
    var names=Object.keys(FR), who=names.filter(function(n){return text.indexOf(n)>-1;})[0]||names[Math.floor(Math.random()*4)];
    var r=o.kept?["...","아 좀 그렇다","그 말은 좀 아니지 않냐","왜 그렇게 말해"]:PRAISE.test(text)?["헐 고마워ㅠㅠ","너밖에 없다 진짜"]:["ㅋㅋㅋ 인정","오 진짜?","ㄱㄱ","고마워ㅋㅋ"];
    msgs.push({who:who,text:r[Math.floor(Math.random()*r.length)]}); renderMsgs();
  },900);
}
function showOverlay(r,fromEnter){
  var top=r.matches.reduce(function(a,m){return LV[m.risk_level]>LV[a.risk_level]?m:a;},r.matches[0]);
  $("ov-badge").className="badge lv-"+r.risk_level; $("ov-badge").textContent=r.risk_level;
  $("ov-sub").innerHTML="<mark>"+esc(top.word)+"</mark> · "+esc(r.category)+". 원래 하려던 <b>"+esc(r.emotion)+"</b>의 마음은 살리고 공격적인 말만 빼 볼까요?";
  $("ov-chips").innerHTML=r.suggestions.map(function(s,i){return '<button class="chip" data-i="'+i+'">'+esc(s)+'</button>';}).join("");
  $("ov-keep").textContent=fromEnter?"그대로 보내기":"그대로 쓸게요";
  ov.dataset.enter=fromEnter?"1":"";
  ov.classList.add("on");
}
function hideOverlay(){ ov.classList.remove("on"); }
$("ov-chips").addEventListener("click",function(e){
  var b=e.target.closest(".chip"); if(!b) return;
  var r=msRes, s=r.suggestions[+b.dataset.i];
  log("detect",r.matches[0].word,LV[r.risk_level]); log("change",r.matches[0].word,LV[r.risk_level]);
  if(ov.dataset.enter){ ms.value=s; post(s,{note:"한 번 더 다듬어서 보냄"}); }
  else { ms.value=s; msUpdate(); hideOverlay(); ms.focus(); toast("바꿨어요. Enter로 보내세요"); }
});
$("ov-edit").addEventListener("click",function(){ if(msRes&&msRes.is_harmful) editedFrom={w:msRes.matches[0].word,lv:LV[msRes.risk_level]}; hideOverlay(); ms.focus(); });
$("ov-close").addEventListener("click",function(){ hideOverlay(); ms.focus(); });
$("ov-keep").addEventListener("click",function(){
  if(ov.dataset.enter){ keepOnce=true; trySend(); }
  else { hideOverlay(); keepOnce=true; ms.focus(); }
});

// ---------- 3. 대시보드 ----------
var DOW=["일","월","화","수","목","금","토"], LVN=["LOW","MEDIUM","HIGH","CRITICAL"];
function stats(){
  var days=[]; for(var i=6;i>=0;i--) days.push({t:TODAY-i*DAY,detect:0,change:0});
  var c={detect:0,change:0,keep:0,praise:0,sent:0}, prev=0, words={}, lvc=[0,0,0,0];
  store.events.forEach(function(e){
    var ago=Math.round((TODAY-sod(e.t))/DAY);
    if(ago>=0&&ago<7){
      c[e.type]=(c[e.type]||0)+1;
      if(e.type==="detect"){ days[6-ago].detect++; var k=e.w; if(!words[k]) words[k]={n:0,lv:e.lv}; words[k].n++; words[k].lv=Math.max(words[k].lv,e.lv); if(e.lv>=0) lvc[e.lv]++; }
      if(e.type==="change") days[6-ago].change++;
    } else if(ago>=7&&ago<14&&e.type==="detect") prev++;
  });
  return {days:days,c:c,prev:prev,words:words,lvc:lvc};
}
function missionDefs(){
  var st=stats(), today=store.events.filter(function(e){return sod(e.t)===TODAY;});
  var sentToday=today.filter(function(e){return e.type==="sent";}).length, keepToday=today.filter(function(e){return e.type==="keep";}).length;
  return [
    {id:"praise3",t:"친구의 노력 칭찬하기",d:"외모 말고 노력·행동을 칭찬하는 메시지 3번 보내기",cur:st.c.praise||0,goal:3,badge:"칭찬 장인"},
    {id:"change5",t:"한 번 더 다듬기",d:"경고가 떴을 때 순화해서 보내기 5번",cur:st.c.change||0,goal:5,badge:"말 다듬기 고수"},
    {id:"clean",t:"오늘은 상처 0",d:"오늘 메시지 5개 이상 보내고 그대로 보낸 비속어 0번",cur:keepToday?0:Math.min(sentToday,5),goal:5,badge:"클린 데이"},
    {id:"nocrit",t:"선 넘지 않기",d:"이번 주 최상위 경고(욕설·패드립)를 그대로 보내지 않기",cur:store.events.some(function(e){return e.type==="keep"&&e.lv===3&&Math.round((TODAY-sod(e.t))/DAY)<7;})?0:1,goal:1,badge:"선 지키기"}
  ];
}
function checkBadges(){
  missionDefs().forEach(function(m){ if(m.cur>=m.goal && store.badges.indexOf(m.id)<0){ store.badges.push(m.id); save(); toast("배지 획득! 「"+m.badge+"」"); } });
}
function renderDash(){
  var st=stats(), c=st.c, rate=c.detect?Math.round(c.change/c.detect*100):0, diff=st.prev-c.detect;
  $("dash-note").textContent="최근 7일 기록이에요. 대화 내용은 저장하지 않고 감지 횟수, 바꾼 횟수, 표현만 남겨요."+(store.seeded?" 지난 기록에는 시연용 예시가 들어 있어요.":"");
  $("kpis").innerHTML=[
    ["이번 주 감지",c.detect+"회",st.prev?(diff>0?"지난주 "+st.prev+"회보다 "+diff+"회 줄었어요":diff<0?"지난주보다 "+(-diff)+"회 늘었어요":"지난주와 같아요"):"지난주 기록 없음",diff>0],
    ["순화 전환율",rate+"%","감지 "+c.detect+"번 중 "+c.change+"번 바꿔서 보냄",rate>=50],
    ["그대로 보냄",c.keep+"회","경고를 보고도 그대로 보낸 횟수",false],
    ["칭찬 메시지",(c.praise||0)+"회","노력·행동을 칭찬한 메시지",false]
  ].map(function(k){return '<div class="card kpi"><div class="k">'+k[0]+'</div><div class="v">'+k[1]+'</div><div class="s'+(k[3]?" good":"")+'">'+k[2]+'</div></div>';}).join("");
  $("chart").innerHTML=chartSvg(st.days)+'<div class="tip" id="tip"></div>';
  bindChart(st.days);
  var ws=Object.keys(st.words).map(function(k){return [k,st.words[k]];}).sort(function(a,b){return b[1].n-a[1].n||b[1].lv-a[1].lv;}).slice(0,6), max=ws.length?ws[0][1].n:1;
  var colors=["var(--low)","var(--medium)","var(--high)","var(--critical)"];
  $("rank").innerHTML=ws.length?ws.map(function(w,i){return '<div class="r"><span class="n">'+(i+1)+'</span><span class="w">'+esc(w[0])+'</span><div class="bar"><span style="width:'+(w[1].n/max*100)+'%;background:'+colors[Math.max(0,w[1].lv)]+'"></span></div><span class="c">'+w[1].n+'회</span></div>';}).join(""):'<p class="sub">이번 주에 감지된 표현이 없어요.</p>';
  $("levels").innerHTML=[3,2,1,0].map(function(l){return '<div class="lv-'+LVN[l]+'">'+LVN[l]+'<b>'+st.lvc[l]+'</b></div>';}).join("");
  $("missions").innerHTML=missionDefs().map(function(m){var v=Math.min(m.cur,m.goal),ok=v>=m.goal;return '<div class="card mission'+(ok?" done":"")+'"><div class="t">'+(ok?"★ ":"")+m.t+'</div><div class="d">'+m.d+'</div><div class="prog"><div class="b"><span style="width:'+(v/m.goal*100)+'%"></span></div>'+(ok?"「"+m.badge+"」":v+" / "+m.goal)+'</div></div>';}).join("");
}
var CH={w:620,h:220,l:28,b:26,t:10};
function chartSvg(days){
  var max=2; days.forEach(function(d){max=Math.max(max,d.detect,d.change);}); if(max%2) max++;
  var iw=CH.w-CH.l, ih=CH.h-CH.b-CH.t, gw=iw/7, bw=16, gap=2, y=function(v){return CH.t+ih-v/max*ih;};
  var o='<svg viewBox="0 0 '+CH.w+' '+CH.h+'" role="img" aria-label="최근 7일 요일별 감지 횟수와 순화해서 보낸 횟수">';
  [0,max/2,max].forEach(function(v){o+='<line x1="'+CH.l+'" x2="'+CH.w+'" y1="'+y(v)+'" y2="'+y(v)+'" stroke="#e3e9e7"/><text x="'+(CH.l-8)+'" y="'+(y(v)+4)+'" text-anchor="end">'+v+'</text>';});
  days.forEach(function(d,i){
    var cx=CH.l+gw*i+gw/2;
    [[d.detect,"var(--series-2)",-1],[d.change,"var(--series-1)",1]].forEach(function(s){
      if(!s[0]) return; var x=s[2]<0?cx-bw-gap/2:cx+gap/2, top=y(s[0]), h=CH.t+ih-top, r=Math.min(4,h);
      o+='<path d="M'+x+' '+(CH.t+ih)+'V'+(top+r)+'q0 -'+r+' '+r+' -'+r+'h'+(bw-2*r)+'q'+r+' 0 '+r+' '+r+'V'+(CH.t+ih)+'z" fill="'+s[1]+'"/>';
    });
    var dt=new Date(d.t);
    o+='<text x="'+cx+'" y="'+(CH.h-6)+'" text-anchor="middle"'+(i===6?' style="fill:var(--ink);font-weight:700"':"")+'>'+(i===6?"오늘":DOW[dt.getDay()])+'</text>';
    o+='<rect class="hit" data-i="'+i+'" x="'+(CH.l+gw*i)+'" y="'+CH.t+'" width="'+gw+'" height="'+ih+'" fill="transparent"/>';
  });
  return o+"</svg>";
}
function bindChart(days){
  var box=$("chart"), tip=$("tip");
  box.querySelectorAll(".hit").forEach(function(r){
    function show(){ var i=+r.dataset.i, d=days[i], sc=box.querySelector("svg").getBoundingClientRect().width/CH.w, dt=new Date(d.t);
      tip.innerHTML=(dt.getMonth()+1)+"월 "+dt.getDate()+"일 ("+DOW[dt.getDay()]+")<br>감지 "+d.detect+"회 · 순화 "+d.change+"회";
      tip.style.left=((CH.l+(CH.w-CH.l)/7*(i+.5))*sc)+"px"; tip.style.top=(CH.t*sc)+"px"; tip.style.display="block"; }
    r.addEventListener("mouseenter",show); r.addEventListener("click",show);
    r.addEventListener("mouseleave",function(){tip.style.display="none";});
  });
}

// ---------- 4. 단어 DB ----------
var ENT=W.entries;
$("db-note").textContent="엑셀에서 가져온 표현 "+W.excel_count.toLocaleString()+"개(원본 2,892줄, 띄어쓰기만 다른 8쌍은 하나로 합침)와 핵심 욕설·변형 "+(ENT.length-W.excel_count).toLocaleString()+"개, 모두 "+ENT.length.toLocaleString()+"개 표현으로 검사해요.";
$("db-cat").innerHTML+=W.categories.map(function(c,i){return '<option value="'+i+'">'+esc(c)+'</option>';}).join("");
function renderDb(){
  var q=$("db-q").value.replace(/\s+/g,""), lv=$("db-lv").value, cat=$("db-cat").value;
  var rows=ENT.filter(function(e){ return (!q||e[1].indexOf(q)>-1||e[0].indexOf(q)>-1) && (lv===""||e[2]===+lv) && (cat===""||e[4]===+cat); });
  $("db-cnt").textContent=rows.length.toLocaleString()+"개"+(rows.length>300?" 중 300개 표시":"");
  $("db-body").innerHTML=rows.slice(0,300).map(function(e){return '<tr><td>'+esc(e[0])+'</td><td><span class="badge lv-'+LVN[e[2]]+'">'+LVN[e[2]]+'</span></td><td>'+esc(W.categories[e[4]])+'</td><td>'+esc(W.types[e[5]])+'</td><td class="desc-c">'+(e[6]>=0?esc(W.descriptions[e[6]]):"핵심 토큰 · 변형 감지용")+'</td></tr>';}).join("");
}
["db-q","db-lv","db-cat"].forEach(function(id){ $(id).addEventListener("input",renderDb); });

// ---------- 공통 ----------
var tt; function toast(t){ var el=$("toast"); el.textContent=t; el.classList.add("on"); clearTimeout(tt); tt=setTimeout(function(){el.classList.remove("on");},2200); }
function show(v){
  currentView=v;
  document.querySelectorAll(".nav button").forEach(function(b){ if(b.dataset.v===v) b.setAttribute("aria-current","page"); else b.removeAttribute("aria-current"); });
  document.querySelectorAll(".view").forEach(function(s){ s.classList.toggle("on",s.id==="v-"+v); });
  if(v==="dashboard") renderDash();
  if(v==="db") renderDb();
  if(v==="messenger") setTimeout(function(){ ms.focus(); },0);
  try{ if(location.hash!=="#"+v) history.replaceState(null,"","#"+v); }catch(e){}
}
document.querySelectorAll(".nav button").forEach(function(b){ b.addEventListener("click",function(){ show(b.dataset.v); }); });
window.addEventListener("hashchange",function(){ var v=location.hash.slice(1); if($("v-"+v)) show(v); });
$("opt-overlay").addEventListener("change",function(e){ settings.overlay=e.target.checked; saveSettings(); });
$("opt-low").addEventListener("change",function(e){ settings.low=e.target.checked; saveSettings(); });
var clearArmed=false;
$("rec-demo").addEventListener("click",function(){ store=seed(); save(); renderDash(); toast("시연용 예시 기록을 넣었어요"); });
$("rec-clear").addEventListener("click",function(){
  if(!clearArmed){ clearArmed=true; $("rec-clear").textContent="한 번 더 누르면 지워져요"; setTimeout(function(){ clearArmed=false; $("rec-clear").textContent="기록 지우기"; },3000); return; }
  clearArmed=false; $("rec-clear").textContent="기록 지우기"; store={events:[],badges:[],seeded:false}; save(); renderDash(); toast("기록을 모두 지웠어요");
});

function boot(){
  $("opt-overlay").checked=settings.overlay; $("opt-low").checked=settings.low;
  renderMsgs(); runChecker(); msUpdate();
  var start=location.hash.slice(1); show($("v-"+start)?start:"checker");
}
if(EXT){
  chrome.storage.local.get([KEY,SKEY],function(r){
    store=r[KEY]||{events:[],badges:[],seeded:false};
    var s=r[SKEY]||{}; settings.overlay=s.overlay!==false; settings.low=!!s.low;
    boot();
  });
  chrome.storage.onChanged.addListener(function(ch,area){
    if(area==="local" && ch[KEY] && ch[KEY].newValue){ store=ch[KEY].newValue; if(currentView==="dashboard") renderDash(); }
  });
} else {
  // 윈도우 트레이 앱이 연 대시보드면 그 기록(desktop-data.js)을 쓴다
  if(window.HANBEONDE_DESKTOP_STORE) store=window.HANBEONDE_DESKTOP_STORE;
  else { try{ store=JSON.parse(localStorage.getItem(KEY)) || seed(); }catch(e){ store=seed(); } }
  boot();
}
})();
