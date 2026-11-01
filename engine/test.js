// node engine/test.js
const fs = require("fs"), path = require("path");
const E = require("./hanbeonde-engine.js");
const engine = E.create(JSON.parse(fs.readFileSync(path.join(__dirname, "words.json"), "utf8")));
const cases = JSON.parse(fs.readFileSync(path.join(__dirname, "test-cases.json"), "utf8"));
let fail = 0;
for (const c of cases) {
  const r = engine.analyze(c.in);
  const ok = r.risk_level === c.risk && JSON.stringify(r.detected_words) === JSON.stringify(c.words) &&
    (!c.sug0 || r.suggestions[0] === c.sug0);
  if (!ok) { fail++; console.log("FAIL", JSON.stringify(c.in), "=>", r.risk_level, JSON.stringify(r.detected_words), "| expected", c.risk, JSON.stringify(c.words), "|", r.category); }
}
console.log(`${cases.length - fail}/${cases.length} 통과 · 사전 ${engine.entryCount}개 (엑셀 ${engine.excelCount}개)`);
console.log(JSON.stringify(engine.analyze("씨발련아"), null, 2));
process.exit(fail ? 1 : 0);
