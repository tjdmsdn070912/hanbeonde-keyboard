import Foundation

/// 두벌식 한글 조합기 (안드로이드 Hangul.java와 같은 방식)
enum Hangul {
    static let cho = Array("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")
    static let jung = Array("ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ")
    static let jong = Array(" ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ") // 0번 = 받침 없음

    private static let vowelPairs: [String: Character] = [
        "ㅗㅏ": "ㅘ", "ㅗㅐ": "ㅙ", "ㅗㅣ": "ㅚ", "ㅜㅓ": "ㅝ", "ㅜㅔ": "ㅞ", "ㅜㅣ": "ㅟ", "ㅡㅣ": "ㅢ",
    ]
    private static let finalPairs: [String: Character] = [
        "ㄱㅅ": "ㄳ", "ㄴㅈ": "ㄵ", "ㄴㅎ": "ㄶ", "ㄹㄱ": "ㄺ", "ㄹㅁ": "ㄻ", "ㄹㅂ": "ㄼ",
        "ㄹㅅ": "ㄽ", "ㄹㅌ": "ㄾ", "ㄹㅍ": "ㄿ", "ㄹㅎ": "ㅀ", "ㅂㅅ": "ㅄ",
    ]

    static func isVowel(_ c: Character) -> Bool { jung.contains(c) }
    static func isConsonant(_ c: Character) -> Bool { cho.contains(c) }
    static func isJamo(_ c: Character) -> Bool { isVowel(c) || isConsonant(c) }

    private static func splitFinal(_ c: Character) -> (Character, Character)? {
        for (k, v) in finalPairs where v == c {
            let parts = Array(k)
            return (parts[0], parts[1])
        }
        return nil
    }

    private struct Block {
        var cho: Character?
        var jung: Character?
        var jong: Character?
        var start: Int

        var isEmpty: Bool { cho == nil && jung == nil }

        var text: String {
            if let c = cho, let v = jung,
               let ci = Hangul.cho.firstIndex(of: c), let vi = Hangul.jung.firstIndex(of: v) {
                let ji = jong.flatMap { Hangul.jong.firstIndex(of: $0) } ?? 0
                let code = 0xAC00 + (ci * 21 + vi) * 28 + ji
                if let scalar = Unicode.Scalar(UInt32(code)) { return String(Character(scalar)) }
            }
            if let c = cho { return String(c) }
            if let v = jung { return String(v) }
            return ""
        }
    }

    struct Result {
        let committed: String   // 확정된 글자들
        let composing: String   // 조합 중인 마지막 글자
        let lastStart: Int      // 조합 중인 글자가 자모 목록 몇 번째부터인지
    }

    static func compose(_ jamo: [Character]) -> Result {
        var blocks: [Block] = []
        var b = Block(start: 0)
        for (i, j) in jamo.enumerated() {
            if isConsonant(j) {
                if b.isEmpty {
                    b.cho = j
                } else if b.jung == nil {
                    blocks.append(b); b = Block(start: i); b.cho = j
                } else if b.jong == nil {
                    if b.cho != nil, let idx = jong.firstIndex(of: j), idx > 0 {
                        b.jong = j
                    } else {
                        blocks.append(b); b = Block(start: i); b.cho = j
                    }
                } else if let cur = b.jong, let combined = finalPairs[String([cur, j])] {
                    b.jong = combined
                } else {
                    blocks.append(b); b = Block(start: i); b.cho = j
                }
            } else {
                if b.isEmpty || b.jung == nil {
                    b.jung = j
                } else if b.jong == nil {
                    if let cur = b.jung, let combined = vowelPairs[String([cur, j])] {
                        b.jung = combined
                    } else {
                        blocks.append(b); b = Block(start: i); b.jung = j
                    }
                } else if let cur = b.jong {
                    // 받침 뒤 모음 → 받침(겹받침이면 뒤쪽)을 다음 글자 초성으로
                    let moved: Character
                    if let pair = splitFinal(cur) {
                        b.jong = pair.0
                        moved = pair.1
                    } else {
                        b.jong = nil
                        moved = cur
                    }
                    blocks.append(b)
                    b = Block(start: i - 1)
                    b.cho = moved
                    b.jung = j
                }
            }
        }
        return Result(committed: blocks.map { $0.text }.joined(), composing: b.text, lastStart: b.start)
    }
}
