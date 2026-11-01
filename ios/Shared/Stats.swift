import Foundation

/// 말 습관 기록. 대화 내용은 저장하지 않고 "언제, 무슨 일, 어떤 표현"만 남긴다.
/// 키보드와 앱이 같은 기록을 보려면 두 타깃 모두에 App Group(group.com.hanbeonde.shared)을 켜야 한다.
enum Stats {
    static let appGroup = "group.com.hanbeonde.shared"
    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    static let detect = "detect", change = "change", keep = "keep"
    static let praise = "praise", sent = "sent", praiseChip = "praiseChip"

    struct Event: Codable {
        let t: Double
        let type: String
        let w: String
    }

    struct Week {
        var detectByDay = [Int](repeating: 0, count: 7) // [0]=6일 전 … [6]=오늘
        var changeByDay = [Int](repeating: 0, count: 7)
        var dayStart = [Date](repeating: Date(), count: 7)
        var detect = 0, change = 0, keep = 0, praise = 0, prevDetect = 0
        var topWords: [(String, Int)] = []
    }

    struct Mission: Identifiable {
        let id: String, title: String, desc: String, badge: String
        let current: Int, goal: Int
        var done: Bool { current >= goal }
    }

    // MARK: 설정
    static var alertOn: Bool {
        get { defaults.object(forKey: "alert") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "alert") }
    }
    /// 가벼운 표현(LOW)에도 알림
    static var lowOn: Bool {
        get { defaults.object(forKey: "low") as? Bool ?? false }
        set { defaults.set(newValue, forKey: "low") }
    }

    // MARK: 기록
    static func events() -> [Event] {
        guard let data = defaults.data(forKey: "events"),
              let list = try? JSONDecoder().decode([Event].self, from: data) else { return [] }
        return list
    }

    private static func save(_ list: [Event]) {
        let cutoff = Date().addingTimeInterval(-60 * 86400).timeIntervalSince1970
        let kept = list.filter { $0.t >= cutoff }
        if let data = try? JSONEncoder().encode(kept) { defaults.set(data, forKey: "events") }
    }

    static func log(_ type: String, _ word: String = "", at date: Date = Date()) {
        var list = events()
        list.append(Event(t: date.timeIntervalSince1970, type: type, w: word))
        save(list)
    }

    static func clear() {
        defaults.removeObject(forKey: "events")
        defaults.removeObject(forKey: "badges")
    }

    /// 발표·시연용 예시 기록: 지난주 12회 → 이번 주 5회
    static func loadDemo() {
        clear()
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        var list: [Event] = []
        func at(_ daysAgo: Int, _ hour: Int) -> Double {
            cal.date(byAdding: .hour, value: hour, to: cal.date(byAdding: .day, value: -daysAgo, to: today)!)!.timeIntervalSince1970
        }
        let prev: [(String, Bool)] = [("씨발련아", false), ("존나", true), ("돼지야", true), ("병신", false), ("느금마", false), ("존못", true),
                                      ("지랄", false), ("오크", true), ("개새끼야", true), ("존나", true), ("멸치", false), ("와꾸 씹창", false)]
        for (i, p) in prev.enumerated() {
            let t = at(8 + i % 6, 10)
            list.append(Event(t: t, type: detect, w: p.0))
            list.append(Event(t: t + 1, type: p.1 ? change : keep, w: p.0))
        }
        let cur: [(Int, String, Bool)] = [(6, "존나", true), (5, "돼지야", true), (4, "지랄", false), (2, "씨발", true), (1, "존나", true)]
        for c in cur {
            let t = at(c.0, 14)
            list.append(Event(t: t, type: detect, w: c.1))
            list.append(Event(t: t + 1, type: c.2 ? change : keep, w: c.1))
        }
        list.append(Event(t: at(3, 15), type: praise, w: ""))
        save(list)
        _ = newBadges()
    }

    // MARK: 집계
    static func week() -> Week {
        var w = Week()
        let cal = Calendar.current
        let today = cal.startOfDay(for: Date())
        for i in 0..<7 { w.dayStart[i] = cal.date(byAdding: .day, value: i - 6, to: today)! }
        var words: [String: Int] = [:]
        for e in events() {
            let day = cal.startOfDay(for: Date(timeIntervalSince1970: e.t))
            let ago = cal.dateComponents([.day], from: day, to: today).day ?? 99
            if ago >= 0 && ago < 7 {
                let idx = 6 - ago
                switch e.type {
                case detect:
                    w.detect += 1; w.detectByDay[idx] += 1
                    if !e.w.isEmpty { words[e.w, default: 0] += 1 }
                case change: w.change += 1; w.changeByDay[idx] += 1
                case keep: w.keep += 1
                case praise: w.praise += 1
                default: break
                }
            } else if ago >= 7 && ago < 14 && e.type == detect {
                w.prevDetect += 1
            }
        }
        w.topWords = Array(words.sorted { $0.value > $1.value }.prefix(5)).map { ($0.key, $0.value) }
        return w
    }

    static func missions() -> [Mission] {
        let w = week()
        let cal = Calendar.current
        var sentToday = 0, keepToday = 0, chip = 0
        for e in events() {
            let d = Date(timeIntervalSince1970: e.t)
            if cal.isDateInToday(d) {
                if e.type == sent { sentToday += 1 }
                if e.type == keep { keepToday += 1 }
            }
            if e.type == praiseChip { chip += 1 }
        }
        return [
            Mission(id: "praise3", title: "친구의 노력 칭찬하기", desc: "외모 말고 노력·행동을 칭찬하는 메시지 3번 보내기 (이번 주)",
                    badge: "칭찬 장인", current: min(w.praise, 3), goal: 3),
            Mission(id: "change5", title: "한 번 더 다듬기", desc: "비하 표현이 감지됐을 때 바꿔서 보내기 5번 (이번 주)",
                    badge: "말 다듬기 고수", current: min(w.change, 5), goal: 5),
            Mission(id: "clean", title: "오늘은 상처 0", desc: "오늘 메시지 5개 이상 보내고, 비하 표현은 한 번도 그대로 보내지 않기",
                    badge: "클린 데이", current: keepToday > 0 ? 0 : min(sentToday, 5), goal: 5),
            Mission(id: "chip", title: "칭찬으로 먼저 말 걸기", desc: "키보드의 ‘칭찬 한마디’ 추천을 한 번 써 보기",
                    badge: "먼저 다가가기", current: min(chip, 1), goal: 1),
        ]
    }

    /// 새로 달성한 배지 이름
    static func newBadges() -> [String] {
        var have = Set(defaults.stringArray(forKey: "badges") ?? [])
        var fresh: [String] = []
        for m in missions() where m.done && !have.contains(m.id) {
            have.insert(m.id)
            fresh.append(m.badge)
        }
        if !fresh.isEmpty { defaults.set(Array(have), forKey: "badges") }
        return fresh
    }
}
