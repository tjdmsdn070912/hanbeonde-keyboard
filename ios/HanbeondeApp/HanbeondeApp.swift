import SwiftUI

@main
struct HanbeondeApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

private let accent = Color(red: 0.055, green: 0.42, blue: 0.39)
private let warn = Color(red: 0.84, green: 0.21, blue: 0.23)
private let gold = Color(red: 0.72, green: 0.47, blue: 0.12)
private let seriesDetect = Color(red: 0.92, green: 0.41, blue: 0.20)
private let seriesChange = Color(red: 0.16, green: 0.47, blue: 0.84)

/// 「한 번 더」 앱 화면: 키보드 켜기 안내, 말 습관 리포트, 칭찬 미션, 설정
struct ContentView: View {
    @State private var week = Stats.week()
    @State private var missions = Stats.missions()
    @State private var alertOn = Stats.alertOn
    @State private var lowOn = Stats.lowOn
    @State private var tryText = ""
    @State private var askDemo = false
    @State private var askClear = false
    @Environment(\.scenePhase) private var phase

    var body: some View {
        NavigationView {
            List {
                Section(header: Text("시작하기")) {
                    Text("1. 설정 앱 → 일반 → 키보드 → 키보드 → 새로운 키보드 추가 → ‘한 번 더’")
                    Text("2. 추가한 ‘한 번 더’를 눌러 ‘전체 접근 허용’을 켜기 (리포트를 이 앱과 같이 보려면 필요해요. 이 앱은 인터넷을 쓰지 않아요.)")
                    Text("3. 카카오톡에서 키보드의 🌐 버튼을 길게 눌러 ‘한 번 더’ 고르기")
                    TextField("여기서 써 보세요. 예) 너 진짜 돼지야ㅋㅋ", text: $tryText)
                }
                .font(.subheadline)

                Section(header: Text("내 말 습관 리포트 · 최근 7일")) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(week.prevDetect)회  →  \(week.detect)회")
                            .font(.system(size: 30, weight: .bold))
                            .monospacedDigit()
                        Text(deltaText).font(.subheadline).foregroundColor(week.prevDetect > week.detect ? accent : .secondary)
                        HStack {
                            tile("\(week.change)회", "바꿔서 보냄")
                            tile("\(week.detect == 0 ? 0 : min(100, week.change * 100 / week.detect))%", "바꾼 비율")
                            tile("\(week.keep)회", "그대로 보냄")
                        }
                        .padding(.top, 6)
                        HStack(spacing: 14) {
                            Label("감지", systemImage: "square.fill").foregroundColor(seriesDetect)
                            Label("바꿔서 보냄", systemImage: "square.fill").foregroundColor(seriesChange)
                        }
                        .font(.caption)
                        .padding(.top, 6)
                        WeekChart(detect: week.detectByDay, change: week.changeByDay, days: week.dayStart)
                            .frame(height: 150)
                    }
                    .padding(.vertical, 6)
                    if week.topWords.isEmpty {
                        Text("이번 주에 감지된 표현이 없어요.").foregroundColor(.secondary)
                    } else {
                        ForEach(0..<week.topWords.count, id: \.self) { i in
                            let item = week.topWords[i]
                            HStack {
                                Text(item.0).frame(width: 80, alignment: .leading)
                                ProgressView(value: Double(item.1), total: Double(max(1, week.topWords.first?.1 ?? 1)))
                                    .tint(warn)
                                Text("\(item.1)회").monospacedDigit().foregroundColor(.secondary)
                            }
                        }
                    }
                }

                Section(header: Text("칭찬 미션"),
                        footer: Text("“잘생겼다”보다 “준비 열심히 했더라”처럼, 그 사람이 한 일을 칭찬해 보세요.")) {
                    ForEach(missions) { m in
                        VStack(alignment: .leading, spacing: 4) {
                            Text((m.done ? "★ " : "☆ ") + m.title).bold().foregroundColor(m.done ? gold : .primary)
                            Text(m.desc).font(.caption).foregroundColor(.secondary)
                            HStack {
                                ProgressView(value: Double(m.current), total: Double(m.goal)).tint(m.done ? gold : accent)
                                Text(m.done ? "배지 「\(m.badge)」" : "\(m.current) / \(m.goal)")
                                    .font(.caption).monospacedDigit()
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                Section(header: Text("설정")) {
                    Toggle("멈춤 알림", isOn: $alertOn)
                        .onChange(of: alertOn) { Stats.alertOn = $0 }
                    Toggle("가벼운 표현도 알림 (오징어, 메주 등)", isOn: $lowOn)
                        .onChange(of: lowOn) { Stats.lowOn = $0 }
                }
                .tint(accent)

                Section(header: Text("내 대화는 어떻게 다뤄지나요?")) {
                    Text("문장 분석은 휴대폰 안에서만 해요. 서버로 보내지 않아요.")
                    Text("리포트에는 감지 횟수, 바꾼 횟수, 표현 이름만 남아요.")
                    Text("부모님이나 선생님에게 알리는 기능은 없어요.")
                }
                .font(.subheadline)

                Section(header: Text("기록 관리")) {
                    Button("발표용 예시 기록 넣기") { askDemo = true }
                    Button("내 기록 모두 지우기", role: .destructive) { askClear = true }
                }
            }
            .navigationTitle("한 번 더")
            .alert("예시 기록 넣기", isPresented: $askDemo) {
                Button("넣기") { Stats.loadDemo(); refresh() }
                Button("취소", role: .cancel) {}
            } message: { Text("지금 기록을 지우고 ‘지난주 12회 → 이번 주 5회’ 예시로 바꿀까요?") }
            .alert("기록 지우기", isPresented: $askClear) {
                Button("지우기", role: .destructive) { Stats.clear(); refresh() }
                Button("취소", role: .cancel) {}
            } message: { Text("리포트와 미션 기록을 모두 지울까요?") }
        }
        .accentColor(accent)
        .onChange(of: phase) { if $0 == .active { refresh() } }
    }

    private var deltaText: String {
        let diff = week.prevDetect - week.detect
        if week.prevDetect == 0 && week.detect == 0 { return "아직 기록이 없어요. 키보드를 쓰면 여기에 쌓여요." }
        if diff > 0 { return "외모 비하 표현이 지난주보다 \(diff)회 줄었어요" }
        if diff < 0 { return "지난주보다 \(-diff)회 늘었어요" }
        return "지난주와 같아요"
    }

    private func refresh() {
        week = Stats.week()
        missions = Stats.missions()
    }

    private func tile(_ v: String, _ k: String) -> some View {
        VStack(alignment: .leading) {
            Text(v).font(.title3).bold().monospacedDigit()
            Text(k).font(.caption).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 최근 7일 요일별 막대그래프
struct WeekChart: View {
    let detect: [Int]
    let change: [Int]
    let days: [Date]

    private static let dow = ["일", "월", "화", "수", "목", "금", "토"]

    var body: some View {
        let top = max(2, ((detect + change).max() ?? 0) + (((detect + change).max() ?? 0) % 2))
        GeometryReader { geo in
            let left: CGFloat = 20, bottom = geo.size.height - 20
            let h = bottom - 6
            let gw = (geo.size.width - left) / 7
            ZStack(alignment: .topLeading) {
                ForEach([0, top / 2, top], id: \.self) { t in
                    let y = bottom - h * CGFloat(t) / CGFloat(top)
                    Path { p in p.move(to: CGPoint(x: left, y: y)); p.addLine(to: CGPoint(x: geo.size.width, y: y)) }
                        .stroke(Color.gray.opacity(0.2), lineWidth: 1)
                    Text("\(t)").font(.caption2).foregroundColor(.secondary)
                        .position(x: 8, y: y)
                }
                ForEach(0..<7, id: \.self) { i in
                    let cx = left + gw * CGFloat(i) + gw / 2
                    bar(detect[i], top: top, h: h, bottom: bottom).fill(seriesDetect)
                        .frame(width: 10).position(x: cx - 6, y: geo.size.height / 2)
                    bar(change[i], top: top, h: h, bottom: bottom).fill(seriesChange)
                        .frame(width: 10).position(x: cx + 6, y: geo.size.height / 2)
                    Text(i == 6 ? "오늘" : Self.dow[Calendar.current.component(.weekday, from: days[i]) - 1])
                        .font(.caption2).fontWeight(i == 6 ? .bold : .regular)
                        .foregroundColor(i == 6 ? .primary : .secondary)
                        .position(x: cx, y: geo.size.height - 8)
                }
            }
        }
    }

    private func bar(_ v: Int, top: Int, h: CGFloat, bottom: CGFloat) -> Path {
        Path { p in
            guard v > 0 else { return }
            let y = bottom - h * CGFloat(v) / CGFloat(top)
            p.addRoundedRect(in: CGRect(x: 0, y: y, width: 10, height: bottom - y), cornerSize: CGSize(width: 3, height: 3))
        }
    }
}
