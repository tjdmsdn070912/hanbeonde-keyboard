import Foundation

/// 한 번 더(Hanbeonde) 감지 엔진 v2 — Swift 포팅
/// engine/hanbeonde-engine.js 와 같은 규칙, 같은 결과. 단어 DB는 words.json (앱·키보드 타깃 모두에 포함).
/// 위치(start/end)는 JS와 같은 UTF-16 기준이다.
final class Engine {

    static let levels = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]

    struct Match {
        let word: String
        let start: Int, end: Int
        let level: Int
        let riskLevel: String
        let group: String
        let category: String
        let matched: String
        let type: String
        let description: String
        let sg: String
    }

    struct Result {
        let isHarmful: Bool
        let riskLevel: String       // CRITICAL / HIGH / MEDIUM / LOW / NONE
        let level: Int              // 3 / 2 / 1 / 0 / -1
        let detectedWords: [String]
        let category: String?
        let suggestions: [String]
        let showOverlay: Bool
        let highlightTarget: String?
        let emotion: String?
        let matches: [Match]

        /// 요구 규격 JSON
        var json: [String: Any] {
            [
                "is_harmful": isHarmful,
                "risk_level": riskLevel,
                "detected_words": detectedWords,
                "category": category as Any? ?? NSNull(),
                "suggestions": suggestions,
                "pc_ui_action": ["show_overlay": showOverlay, "highlight_target": highlightTarget as Any? ?? NSNull()],
            ]
        }
    }

    // MARK: 순화안
    private static let suggest: [String: [String]] = [
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
    ]
    private static let emotionLabel = ["joke": "장난", "frustrated": "답답함", "angry": "화남", "surprised": "놀람", "care": "관심"]
    private static let particles: Set<UInt16> = Set("아야이가는은을를들".utf16)
    private static let trimChars: Set<UInt16> = Set("\"'“”‘’()[]{}<>.,!?~…·:; \t\n\r\u{00a0}\u{3000}".utf16)
    private static let spaceChars: Set<UInt16> = Set(" \t\n\r\u{00a0}\u{3000}".utf16)

    private static func re(_ p: String, _ opts: NSRegularExpression.Options = []) -> NSRegularExpression {
        try! NSRegularExpression(pattern: p, options: opts)
    }
    private static let reLaugh = re("[ㅋㅎ]{2,}|lol|\\^\\^", .caseInsensitive)
    private static let reFrustrated = re("답답|에휴|어휴|하아|한숨|미치겠|짜증")
    private static let reAngry = re("화나|화난|빡치|빡쳐|열받|꺼져|닥쳐|죽을래|!")
    private static let reSurprised = re("헐|대박|\\?")

    // MARK: 사전
    private final class Node {
        var next: [UInt16: Node] = [:]
        var value = -1
    }

    private struct Ch {
        let c: UInt16
        let a: Int, b: Int
    }

    private final class Trie {
        let root = Node()

        func add(_ key: String, _ value: Int) {
            var n = root
            for u in key.utf16 {
                if let m = n.next[u] { n = m } else { let m = Node(); n.next[u] = m; n = m }
            }
            n.value = value
        }

        func scan(_ chars: [Ch]) -> [(Int, Int, Int)] {
            var hits: [(Int, Int, Int)] = []
            for i in 0..<chars.count {
                var n = root
                var best = -1, val = -1
                var j = i
                while j < chars.count, let m = n.next[chars[j].c] {
                    n = m
                    if n.value >= 0 { best = j + 1; val = n.value }
                    j += 1
                }
                if best > 0 { hits.append((i, best, val)) }
            }
            return hits
        }
    }

    // MARK: 데이터
    private let entries: [[Any]]
    private let categories: [String], types: [String], descriptions: [String]
    private let shortTrie = Trie(), longTrie = Trie(), allowTrie = Trie()
    private let variants: [(NSRegularExpression, String)]
    let entryCount: Int
    let excelCount: Int

    init?(data: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let entries = obj["entries"] as? [[Any]],
              let categories = obj["categories"] as? [String],
              let types = obj["types"] as? [String],
              let descriptions = obj["descriptions"] as? [String],
              let allow = obj["allow"] as? [String],
              let variants = obj["variants"] as? [[String]] else { return nil }
        self.entries = entries
        self.categories = categories
        self.types = types
        self.descriptions = descriptions
        for (i, e) in entries.enumerated() {
            guard let key = e[1] as? String else { continue }
            if key.utf16.count <= 2 { shortTrie.add(key, i) } else { longTrie.add(key, i) }
        }
        for (i, a) in allow.enumerated() { allowTrie.add(a, i) }
        self.variants = variants.compactMap { v in
            guard v.count == 2, let r = try? NSRegularExpression(pattern: v[0]) else { return nil }
            return (r, v[1])
        }
        entryCount = entries.count
        excelCount = obj["excel_count"] as? Int ?? 0
    }

    /// 번들에 들어 있는 words.json 으로 만든 공용 엔진
    static let shared: Engine? = {
        let bundles = [Bundle.main, Bundle(for: Engine.self)]
        for b in bundles {
            if let url = b.url(forResource: "words", withExtension: "json"), let d = try? Data(contentsOf: url) {
                return Engine(data: d)
            }
        }
        return nil
    }()

    // MARK: 글자 종류 (UTF-16)
    private static func isSyl(_ c: UInt16) -> Bool { c >= 0xAC00 && c <= 0xD7A3 }
    private static func isJamo(_ c: UInt16) -> Bool { c >= 0x3131 && c <= 0x318E }
    private static func isHangul(_ c: UInt16) -> Bool { isSyl(c) || isJamo(c) }
    private static func isLatin(_ c: UInt16) -> Bool { c >= 97 && c <= 122 }
    private static func isDigit(_ c: UInt16) -> Bool { c >= 48 && c <= 57 }

    // MARK: 정규화
    private func normalize(_ u: [UInt16], runs: inout [[Ch]]) -> [Ch] {
        var chars: [Ch] = []
        for (i, ch) in u.enumerated() {
            if Engine.spaceChars.contains(ch) { chars.append(Ch(c: 32, a: i, b: i + 1)); continue }
            let lc: UInt16 = (ch >= 65 && ch <= 90) ? ch + 32 : ch
            if Engine.isHangul(lc) || Engine.isLatin(lc) || Engine.isDigit(lc) { chars.append(Ch(c: lc, a: i, b: i + 1)) }
        }
        var cleaned: [Ch] = []
        var j = 0
        while j < chars.count {
            let cur = chars[j]
            if Engine.isDigit(cur.c) || Engine.isLatin(cur.c) {
                var k = j
                while k < chars.count && (Engine.isDigit(chars[k].c) || Engine.isLatin(chars[k].c)) { k += 1 }
                let prev = cleaned.last
                let next: Ch? = k < chars.count ? chars[k] : nil
                let between = prev != nil && next != nil && Engine.isHangul(prev!.c) && Engine.isHangul(next!.c)
                let allDigits = chars[j..<k].allSatisfy { Engine.isDigit($0.c) }
                if !(between && (allDigits || k - j == 1)) { cleaned.append(contentsOf: chars[j..<k]) }
                j = k
                continue
            }
            cleaned.append(cur)
            j += 1
        }
        var tokens: [[Ch]] = []
        var tok: [Ch] = []
        for c in cleaned {
            if c.c == 32 { if !tok.isEmpty { tokens.append(tok); tok = [] } } else { tok.append(c) }
        }
        if !tok.isEmpty { tokens.append(tok) }
        var spaced: [Ch] = []
        for (idx, t) in tokens.enumerated() {
            if t.count == 1 && idx + 1 < tokens.count && tokens[idx + 1].count == 1 && !(idx > 0 && tokens[idx - 1].count == 1) {
                var run: [Ch] = []
                var x = idx
                while x < tokens.count && tokens[x].count == 1 { run.append(tokens[x][0]); x += 1 }
                runs.append(run)
            }
            if idx > 0 && !(tokens[idx - 1].count == 1 && t.count == 1) {
                spaced.append(Ch(c: 32, a: tokens[idx - 1].last!.b, b: t[0].a))
            }
            spaced.append(contentsOf: t)
        }
        for (r, rep) in variants { spaced = Engine.applyVariant(spaced, r, rep) }
        return spaced
    }

    private static func applyVariant(_ chars: [Ch], _ re: NSRegularExpression, _ rep: String) -> [Ch] {
        let s = String(utf16CodeUnits: chars.map { $0.c }, count: chars.count)
        let ms = re.matches(in: s, range: NSRange(location: 0, length: chars.count))
        if ms.isEmpty { return chars }
        var out: [Ch] = []
        var last = 0
        let repUnits = Array(rep.utf16)
        for m in ms where m.range.length > 0 {
            out.append(contentsOf: chars[last..<m.range.location])
            let a = chars[m.range.location].a, b = chars[m.range.location + m.range.length - 1].b
            for u in repUnits { out.append(Ch(c: u, a: a, b: b)) }
            last = m.range.location + m.range.length
        }
        out.append(contentsOf: chars[last...])
        return out
    }

    // MARK: 분석
    private struct Info {
        let word: String, level: Int, group: String, category: String, type: String, description: String, sg: String
    }

    private func info(_ idx: Int) -> Info {
        let e = entries[idx]
        let d = (e[6] as? Int) ?? -1
        return Info(word: e[0] as? String ?? "", level: e[2] as? Int ?? 1, group: e[3] as? String ?? "A",
                    category: categories[(e[4] as? Int) ?? 0], type: types[(e[5] as? Int) ?? 0],
                    description: d >= 0 ? descriptions[d] : "", sg: e[7] as? String ?? "")
    }

    private static func rank(_ g: String) -> Int { g == "P" ? 3 : g == "S" ? 2 : 1 }

    private static func categoryLabel(_ i: Info) -> String {
        if i.group == "P" { return "패드립 및 인격 모독" }
        if i.group == "S" { return i.level >= 3 ? "강한 욕설 및 비속어" : i.level == 2 ? "공격적 비속어" : "비속어" }
        switch i.category {
        case "얼굴/생김새": return "외모 비하 (얼굴/생김새)"
        case "체형/신체조건": return "외모 비하 (체형/신체조건)"
        case "성형/피부/노화": return "외모 비하 (성형/피부/노화)"
        case "젠더/연령대 결합": return "외모·성별 비하"
        case "인터넷/게임 은어": return "외모 비하 은어"
        default: return "외모·체형 비하"
        }
    }

    private static func found(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(location: 0, length: s.utf16.count)) != nil
    }

    private static func emotion(of text: String) -> String {
        if found(reLaugh, text) { return "joke" }
        if found(reFrustrated, text) { return "frustrated" }
        if found(reAngry, text) { return "angry" }
        if found(reSurprised, text) { return "surprised" }
        return ""
    }

    private static func suggestions(group: String, sg: String, emotion: String) -> [String] {
        if group == "P" { return suggest[emotion == "joke" ? "P_joke" : "P"]! }
        if group == "S" { return suggest["S_" + (emotion.isEmpty ? "angry" : emotion)]! }
        if emotion == "angry" { return suggest["A_angry"]! }
        if emotion == "frustrated" { return suggest["A_frustrated"]! }
        let set = suggest[sg] ?? suggest["face"]!
        if emotion == "joke" { return set.map { ($0.hasSuffix("?") || $0.hasSuffix("ㅋ")) ? $0 : $0 + "ㅋㅋ" } }
        return set
    }

    func analyze(_ input: String) -> Result {
        let text = input.precomposedStringWithCanonicalMapping   // NFC
        let u = Array(text.utf16)
        var runs: [[Ch]] = []
        let spaced = normalize(u, runs: &runs)
        let noSpace = spaced.filter { $0.c != 32 }

        var hits: [(a: Int, b: Int, info: Info)] = []
        for (i, j, v) in shortTrie.scan(spaced) { hits.append((spaced[i].a, spaced[j - 1].b, info(v))) }
        for (i, j, v) in longTrie.scan(noSpace) { hits.append((noSpace[i].a, noSpace[j - 1].b, info(v))) }
        let allows = allowTrie.scan(spaced).map { (spaced[$0.0].a, spaced[$0.1 - 1].b) }
        hits = hits.filter { h in !allows.contains { $0.0 <= h.a && h.b <= $0.1 } }
        // 안정 정렬 (JS와 같은 순서)
        hits = hits.enumerated().sorted { x, y in
            if x.element.a != y.element.a { return x.element.a < y.element.a }
            let lx = x.element.b - x.element.a, ly = y.element.b - y.element.a
            if lx != ly { return lx > ly }
            return x.offset < y.offset
        }.map { $0.element }

        var clusters: [(a: Int, b: Int, hits: [(a: Int, b: Int, info: Info)])] = []
        for h in hits {
            if let last = clusters.last, h.a < last.b {
                clusters[clusters.count - 1].b = max(last.b, h.b)
                clusters[clusters.count - 1].hits.append(h)
            } else {
                clusters.append((h.a, h.b, [h]))
            }
        }

        var groups: [(s: Int, e: Int, hits: [(a: Int, b: Int, info: Info)])] = []
        for c in clusters {
            var s = c.a, e = c.b
            for run in runs {
                var first = -1, last = -1
                for (i, ch) in run.enumerated() where ch.a < e && s < ch.b {
                    if first < 0 { first = i }
                    last = i
                }
                if first < 0 { continue }
                while last + 1 < run.count && Engine.particles.contains(run[last + 1].c) { last += 1 }
                s = min(s, run[first].a)
                e = max(e, run[last].b)
            }
            while s > 0 && !Engine.spaceChars.contains(u[s - 1]) { s -= 1 }
            while e < u.count && !Engine.spaceChars.contains(u[e]) { e += 1 }
            while s < e && Engine.trimChars.contains(u[s]) { s += 1 }
            while e > s && Engine.trimChars.contains(u[e - 1]) { e -= 1 }
            if let last = groups.last, s < last.e {
                groups[groups.count - 1].e = max(last.e, e)
                groups[groups.count - 1].hits.append(contentsOf: c.hits)
            } else {
                groups.append((s, e, c.hits))
            }
        }

        var matches: [Match] = []
        for g in groups {
            var best = g.hits[0]
            for h in g.hits.dropFirst() {
                let hi = h.info, bi = best.info
                if hi.level > bi.level || (hi.level == bi.level && (Engine.rank(hi.group) > Engine.rank(bi.group)
                    || (Engine.rank(hi.group) == Engine.rank(bi.group) && (h.b - h.a) > (best.b - best.a)))) {
                    best = h
                }
            }
            let i = best.info
            let word = String(utf16CodeUnits: Array(u[g.s..<g.e]), count: g.e - g.s)
            matches.append(Match(word: word, start: g.s, end: g.e, level: i.level, riskLevel: Engine.levels[i.level],
                                 group: i.group, category: Engine.categoryLabel(i), matched: i.word, type: i.type,
                                 description: i.description, sg: i.sg))
        }

        guard var top = matches.first else {
            return Result(isHarmful: false, riskLevel: "NONE", level: -1, detectedWords: [], category: nil,
                          suggestions: [], showOverlay: false, highlightTarget: nil, emotion: nil, matches: [])
        }
        for m in matches where m.level > top.level || (m.level == top.level && Engine.rank(m.group) > Engine.rank(top.group)) {
            top = m
        }
        let emo = Engine.emotion(of: text)
        let shown = emo.isEmpty ? (top.group == "A" ? "care" : "angry") : emo
        var words: [String] = []
        for m in matches where !words.contains(m.word) { words.append(m.word) }
        return Result(isHarmful: true, riskLevel: top.riskLevel, level: top.level, detectedWords: words,
                      category: top.category, suggestions: Engine.suggestions(group: top.group, sg: top.sg, emotion: emo),
                      showOverlay: top.level >= 1, highlightTarget: top.word, emotion: Engine.emotionLabel[shown],
                      matches: matches)
    }
}
