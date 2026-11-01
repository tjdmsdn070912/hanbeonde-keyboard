import UIKit

/// 「한 번 더」 키보드 (아이폰)
/// 감지: 입력 중인 문장에서 외모 비하 표현을 찾아 키보드 위 알림줄에 표시
/// 멈춤: 비하 표현을 쓰고 손을 멈추거나 '보내기'를 누르면 키보드 자리에 알림
/// 대체: 원래 의도를 살린 표현을 추천하고, 누르면 입력창 문장을 바꿔 줌
final class KeyboardViewController: UIInputViewController {

    private enum Mode { case ko, en, sym }

    // 색
    private let bgColor = UIColor(red: 0.85, green: 0.87, blue: 0.87, alpha: 1)
    private let keyColor = UIColor.white
    private let specialColor = UIColor(red: 0.73, green: 0.76, blue: 0.75, alpha: 1)
    private let ink = UIColor(red: 0.09, green: 0.13, blue: 0.12, alpha: 1)
    private let ink2 = UIColor(red: 0.30, green: 0.35, blue: 0.34, alpha: 1)
    private let accent = UIColor(red: 0.055, green: 0.42, blue: 0.39, alpha: 1)
    private let accentSoft = UIColor(red: 0.86, green: 0.94, blue: 0.93, alpha: 1)
    private let warn = UIColor(red: 0.84, green: 0.21, blue: 0.23, alpha: 1)
    private let warnSoft = UIColor(red: 0.99, green: 0.93, blue: 0.93, alpha: 1)
    private let goldSoft = UIColor(red: 0.98, green: 0.96, blue: 0.90, alpha: 1)

    private let ko: [[String]] = [
        ["ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"],
        ["ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"],
        ["⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"],
    ]
    private let koShift: [[String]] = [
        ["ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "ㅛ", "ㅕ", "ㅑ", "ㅒ", "ㅖ"],
        ["ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"],
        ["⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"],
    ]
    private let en: [[String]] = [
        ["q", "w", "e", "r", "t", "y", "u", "i", "o", "p"],
        ["a", "s", "d", "f", "g", "h", "j", "k", "l"],
        ["⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"],
    ]
    private let sym: [[String]] = [
        ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
        ["-", "/", ":", ";", "(", ")", "₩", "&", "@", "\""],
        ["#", ".", ",", "?", "!", "'", "~", "^", "⌫"],
    ]

    private var mode: Mode = .ko
    private var letterMode: Mode = .ko
    private var shift = false

    // 한글 조합
    private var jamo: [Character] = []
    private var composing = ""   // 입력창에 넣어 둔 '조합 중' 글자

    // 화면
    private let statusLabel = PaddedLabel()
    private let chipStack = UIStackView()
    private let keysArea = UIView()
    private let keysStack = UIStackView()

    // 한 메시지 단위 상태
    private var lastText = ""
    private var lastHits: Engine.Result?   // 알림을 띄울 만한 감지 결과
    private var sessionDetected = false, sessionChanged = false, sessionLogged = false, sessionPaused = false
    private var sessionWord = ""
    private var lastDeleteAt = Date.distantPast
    private var confirmMode = false
    private var pauseFromReturn = false
    private var pauseWork: DispatchWorkItem?
    private var deleteTimer: Timer?

    // MARK: - 화면 만들기

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = bgColor
        let h = view.heightAnchor.constraint(equalToConstant: 292)
        h.priority = UILayoutPriority(999)
        h.isActive = true

        let root = UIStackView()
        root.axis = .vertical
        root.spacing = 2
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 3),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -3),
            root.topAnchor.constraint(equalTo: view.topAnchor, constant: 6),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -4),
        ])

        // 알림줄
        let statusRow = UIStackView()
        statusRow.axis = .horizontal
        statusRow.spacing = 8
        statusRow.alignment = .center
        statusRow.isLayoutMarginsRelativeArrangement = true
        statusRow.layoutMargins = UIEdgeInsets(top: 0, left: 6, bottom: 0, right: 6)
        let brand = UILabel()
        brand.text = "한 번 더"
        brand.font = .boldSystemFont(ofSize: 13)
        brand.textColor = accent
        brand.setContentHuggingPriority(.required, for: .horizontal)
        statusLabel.font = .systemFont(ofSize: 12.5)
        statusLabel.layer.cornerRadius = 11
        statusLabel.clipsToBounds = true
        statusLabel.lineBreakMode = .byTruncatingTail
        statusRow.addArrangedSubview(brand)
        statusRow.addArrangedSubview(statusLabel)
        statusRow.addArrangedSubview(UIView())
        statusRow.heightAnchor.constraint(equalToConstant: 30).isActive = true
        root.addArrangedSubview(statusRow)

        // 추천 칩
        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        chipStack.axis = .horizontal
        chipStack.spacing = 6
        chipStack.alignment = .center
        chipStack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(chipStack)
        NSLayoutConstraint.activate([
            chipStack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 4),
            chipStack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -4),
            chipStack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            chipStack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            chipStack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
        scroll.heightAnchor.constraint(equalToConstant: 40).isActive = true
        root.addArrangedSubview(scroll)

        // 키
        keysStack.axis = .vertical
        keysStack.distribution = .fillEqually
        keysStack.translatesAutoresizingMaskIntoConstraints = false
        keysArea.addSubview(keysStack)
        NSLayoutConstraint.activate([
            keysStack.leadingAnchor.constraint(equalTo: keysArea.leadingAnchor),
            keysStack.trailingAnchor.constraint(equalTo: keysArea.trailingAnchor),
            keysStack.topAnchor.constraint(equalTo: keysArea.topAnchor),
            keysStack.bottomAnchor.constraint(equalTo: keysArea.bottomAnchor),
        ])
        root.addArrangedSubview(keysArea)

        renderKeys()
        analyze()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        resetComposition()
        resetSession()
        lastText = currentText()
        renderKeys()
        analyze()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        scheduleAnalyze()
    }

    // MARK: - 키 배치

    private func renderKeys() {
        deleteUp()
        keysStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        keysArea.subviews.filter { $0 !== keysStack }.forEach { $0.removeFromSuperview() }
        keysStack.isHidden = false
        if confirmMode && lastHits != nil {
            keysStack.isHidden = true
            renderPausePanel()
            return
        }
        let rows: [[String]]
        switch mode {
        case .sym: rows = sym
        case .en: rows = en
        case .ko: rows = shift ? koShift : ko
        }
        for (r, keys) in rows.enumerated() {
            let row = RowView()
            let short = r == 1 && keys.count == 9
            if short { row.add(UIView(), weight: 0.5) }
            for k in keys {
                var w: CGFloat = (k == "⇧" || k == "⌫") ? 1.5 : 1
                if mode == .sym && k == "#" { w = 1.5 }
                row.add(makeKey(k), weight: w)
            }
            if short { row.add(UIView(), weight: 0.5) }
            keysStack.addArrangedSubview(row)
        }
        let last = RowView()
        last.add(makeKey(mode == .sym ? (letterMode == .ko ? "가" : "ABC") : "123"), weight: 1.4)
        if needsInputModeSwitchKey { last.add(makeKey("🌐"), weight: 1.1) }
        if mode != .sym { last.add(makeKey(mode == .ko ? "A" : "한"), weight: 1.1) }
        last.add(makeKey("space"), weight: 4)
        last.add(makeKey("."), weight: 1)
        last.add(makeKey("⏎"), weight: 1.8)
        keysStack.addArrangedSubview(last)
    }

    private func isSpecial(_ k: String) -> Bool {
        ["⇧", "⌫", "123", "가", "ABC", "A", "한", "⏎", "🌐"].contains(k)
    }

    private func label(for k: String) -> String {
        switch k {
        case "space": return mode == .ko ? "한 번 더" : "space"
        case "⏎": return textDocumentProxy.returnKeyType == .send ? "보내기" : "⏎"
        case "⇧": return shift ? "⬆︎" : "⇧"
        default: return (mode == .en && shift) ? k.uppercased() : k
        }
    }

    private func makeKey(_ k: String) -> UIButton {
        let b = KeyButton(type: .custom)
        b.key = k
        b.setTitle(label(for: k), for: .normal)
        let special = isSpecial(k)
        b.titleLabel?.font = .systemFont(ofSize: (special || k == "space") ? 15 : 22)
        b.setTitleColor(k == "⏎" ? .white : (k == "space" ? ink2 : ink), for: .normal)
        b.backgroundColor = k == "⏎" ? accent : (special ? specialColor : keyColor)
        if k == "⇧" && shift { b.backgroundColor = .white }
        b.layer.cornerRadius = 7
        b.layer.shadowColor = UIColor.black.cgColor
        b.layer.shadowOpacity = 0.18
        b.layer.shadowOffset = CGSize(width: 0, height: 1)
        b.layer.shadowRadius = 0
        if k == "🌐" {
            b.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        } else if k == "⌫" {
            b.addTarget(self, action: #selector(deleteDown), for: .touchDown)
            b.addTarget(self, action: #selector(deleteUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        } else {
            b.addTarget(self, action: #selector(keyTapped(_:)), for: .touchUpInside)
        }
        return b
    }

    // MARK: - 입력

    @objc private func keyTapped(_ sender: KeyButton) {
        UIDevice.current.playInputClick()
        cancelPause()
        let k = sender.key
        switch k {
        case "⇧":
            shift.toggle(); renderKeys(); return
        case "123":
            resetComposition(); mode = .sym; shift = false; renderKeys(); return
        case "가", "ABC":
            mode = letterMode; renderKeys(); return
        case "A", "한":
            resetComposition()
            mode = (mode == .ko) ? .en : .ko
            letterMode = mode
            shift = false
            renderKeys(); return
        case "space":
            insertPlain(" "); return
        case "⏎":
            returnTapped(); return
        default:
            break
        }
        if mode == .ko, k.count == 1, let c = k.first, Hangul.isJamo(c) {
            typeJamo(c)
        } else {
            insertPlain((mode == .en && shift) ? k.uppercased() : k)
        }
        if shift { shift = false; renderKeys() }
        scheduleAnalyze()
    }

    /// 입력창 끝이 우리가 넣어 둔 조합 글자와 다르면(커서 이동 등) 조합을 새로 시작
    private func compositionStillValid() -> Bool {
        guard !composing.isEmpty else { return jamo.isEmpty }
        return textDocumentProxy.documentContextBeforeInput?.hasSuffix(composing) ?? false
    }

    private func typeJamo(_ c: Character) {
        if !compositionStillValid() { resetComposition() }
        for _ in 0..<composing.count { textDocumentProxy.deleteBackward() }
        jamo.append(c)
        let r = Hangul.compose(jamo)
        textDocumentProxy.insertText(r.committed + r.composing)
        composing = r.composing
        if r.lastStart > 0 { jamo = Array(jamo[r.lastStart...]) }
    }

    private func resetComposition() {
        jamo.removeAll()
        composing = ""
    }

    private func insertPlain(_ s: String) {
        resetComposition()
        textDocumentProxy.insertText(s)
        scheduleAnalyze()
    }

    private func backspace() {
        lastDeleteAt = Date()
        cancelPause()
        if !jamo.isEmpty && compositionStillValid() {
            for _ in 0..<composing.count { textDocumentProxy.deleteBackward() }
            jamo.removeLast()
            if jamo.isEmpty {
                composing = ""
            } else {
                let r = Hangul.compose(jamo)
                composing = r.committed + r.composing
                textDocumentProxy.insertText(composing)
            }
        } else {
            resetComposition()
            textDocumentProxy.deleteBackward()
        }
        scheduleAnalyze()
    }

    @objc private func deleteDown() {
        UIDevice.current.playInputClick()
        backspace()
        deleteTimer?.invalidate()
        deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in
            self?.deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
                self?.backspace()
            }
        }
    }

    @objc private func deleteUp() {
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    private func returnTapped() {
        resetComposition()
        let text = currentText()
        let hits = check(text)
        if textDocumentProxy.returnKeyType == .send && hits != nil && Stats.alertOn && !confirmMode {
            cancelPause()
            lastText = text
            lastHits = hits
            confirmMode = true
            pauseFromReturn = true
            renderKeys()
            renderToolbar(text, hits)
            return
        }
        textDocumentProxy.insertText("\n")
    }

    private func currentText() -> String {
        (textDocumentProxy.documentContextBeforeInput ?? "") + (textDocumentProxy.documentContextAfterInput ?? "")
    }

    /// 입력창 문장 전체를 추천 문장으로 바꾼다
    private func replaceAll(with s: String) {
        resetComposition()
        let p = textDocumentProxy
        if let after = p.documentContextAfterInput, !after.isEmpty {
            p.adjustTextPosition(byCharacterOffset: after.count)
        }
        let before = p.documentContextBeforeInput ?? ""
        for _ in 0..<before.count { p.deleteBackward() }
        p.insertText(s)
    }

    // MARK: - 감지와 기록

    private func resetSession() {
        sessionDetected = false
        sessionChanged = false
        sessionLogged = false
        sessionPaused = false
        sessionWord = ""
        confirmMode = false
        pauseFromReturn = false
        cancelPause()
    }

    private func cancelPause() {
        pauseWork?.cancel()
        pauseWork = nil
    }

    private func scheduleAnalyze() {
        DispatchQueue.main.async { [weak self] in self?.analyze() }
    }

    private func analyze() {
        let text = currentText()
        let hits = check(text)

        // 입력창이 한꺼번에 비워졌다 = 보냈다 (지우기 키로 지운 경우는 제외)
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !lastText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let deletedByUser = Date().timeIntervalSince(lastDeleteAt) < 1.5
            if !deletedByUser {
                if !sessionLogged {
                    if let last = lastHits, let w = last.highlightTarget {
                        Stats.log(Stats.detect, w)
                        Stats.log(Stats.keep, w)
                    } else {
                        if sessionDetected && !sessionChanged {
                            Stats.log(Stats.detect, sessionWord)
                            Stats.log(Stats.change, sessionWord)
                        }
                        if KeyboardViewController.isPraise(lastText) { Stats.log(Stats.praise) }
                    }
                }
                Stats.log(Stats.sent)
                showNewBadges()
            }
            let wasConfirm = confirmMode
            resetSession()
            lastHits = nil
            resetComposition()
            if wasConfirm { renderKeys() }
        }

        if let h = hits, !sessionDetected {
            sessionDetected = true
            sessionWord = h.highlightTarget ?? ""
        }

        lastText = text
        lastHits = hits
        if hits == nil && confirmMode {
            exitConfirm()
            return
        }
        if hits != nil && !sessionPaused && !confirmMode && Stats.alertOn {
            cancelPause()
            let work = DispatchWorkItem { [weak self] in
                guard let self = self, self.lastHits != nil, !self.confirmMode else { return }
                self.sessionPaused = true
                self.confirmMode = true
                self.pauseFromReturn = false
                self.renderKeys()
                self.renderToolbar(self.lastText, self.lastHits)
            }
            pauseWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: work)
        }
        renderToolbar(text, hits)
    }

    private func exitConfirm() {
        confirmMode = false
        pauseFromReturn = false
        renderKeys()
        renderToolbar(lastText, lastHits)
    }

    private func showNewBadges() {
        let badges = Stats.newBadges()
        guard let b = badges.first else { return }
        setStatus("배지 획득! 「\(b)」", fg: .white, bg: accent)
    }

    // MARK: - 알림줄

    private func setStatus(_ s: String, fg: UIColor, bg: UIColor) {
        statusLabel.text = s
        statusLabel.textColor = fg
        statusLabel.backgroundColor = bg
    }

    private func renderToolbar(_ text: String, _ hits: Engine.Result?) {
        chipStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if confirmMode && hits != nil {
            setStatus("멈춤 · 보내기 전에 한 번 더 생각해 봐요", fg: .white, bg: warn)
            return
        }
        if let h = hits {
            let more = h.detectedWords.count > 1 ? " 외 \(h.detectedWords.count - 1)개" : ""
            setStatus("\(h.riskLevel) · ‘\(h.highlightTarget ?? "")’\(more) · \(h.category ?? "")", fg: levelColor(h.level), bg: warnSoft)
            addLabel("바꿔 볼까요?")
            for s in h.suggestions {
                addChip(s, bg: accentSoft, fg: accent) { [weak self] in
                    guard let self = self else { return }
                    Stats.log(Stats.detect, self.sessionWord)
                    Stats.log(Stats.change, self.sessionWord)
                    self.sessionChanged = true
                    self.replaceAll(with: s)
                    self.scheduleAnalyze()
                }
            }
            return
        }
        if text.trimmingCharacters(in: .whitespaces).isEmpty {
            setStatus("보내기 전에 한 번 더 살펴볼게요", fg: accent, bg: accentSoft)
        } else {
            setStatus("좋아요, 다듬을 표현이 없어요", fg: accent, bg: accentSoft)
        }
        addLabel("칭찬 한마디")
        for s in KeyboardViewController.praiseSuggestions {
            addChip(s, bg: goldSoft, fg: ink) { [weak self] in
                guard let self = self else { return }
                Stats.log(Stats.praiseChip)
                let t = self.currentText()
                self.insertPlain(t.isEmpty || t.hasSuffix(" ") ? s : " " + s)
                self.showNewBadges()
            }
        }
    }

    static let praiseSuggestions = ["발표 준비 진짜 열심히 하더라", "덕분에 조별과제 끝났다 고마워", "오늘 축구 수비 대단했어", "그거 끝까지 해낸 거 멋있다"]
    private static let praiseRegex = try! NSRegularExpression(pattern: "수고|고생|대단|잘했|열심히|고마워|덕분|노력|잘하더라|잘 부르|잘 뛰|최고야|짱이다|멋지게 해")

    static func isPraise(_ s: String) -> Bool {
        praiseRegex.firstMatch(in: s, range: NSRange(location: 0, length: s.utf16.count)) != nil
    }

    /// 감지 결과 중 알림을 띄울 만한 것만 (기본: MEDIUM 이상, 설정에 따라 LOW 포함)
    private func check(_ text: String) -> Engine.Result? {
        guard let engine = Engine.shared else { return nil }
        let r = engine.analyze(text)
        return (r.isHarmful && r.level >= (Stats.lowOn ? 0 : 1)) ? r : nil
    }

    private func levelColor(_ level: Int) -> UIColor {
        switch level {
        case 3: return UIColor(red: 0.78, green: 0.16, blue: 0.16, alpha: 1)
        case 2: return UIColor(red: 0.85, green: 0.28, blue: 0.06, alpha: 1)
        case 1: return UIColor(red: 0.60, green: 0.39, blue: 0.07, alpha: 1)
        default: return ink2
        }
    }

    private func addLabel(_ s: String) {
        let l = UILabel()
        l.text = s
        l.font = .systemFont(ofSize: 11.5)
        l.textColor = ink2
        chipStack.addArrangedSubview(l)
    }

    private func addChip(_ s: String, bg: UIColor, fg: UIColor, action: @escaping () -> Void) {
        let b = ClosureButton(type: .custom)
        b.setTitle(s, for: .normal)
        b.setTitleColor(fg, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 14)
        b.backgroundColor = bg
        b.layer.cornerRadius = 16
        b.layer.borderWidth = 1
        b.layer.borderColor = UIColor(red: 0.74, green: 0.78, blue: 0.76, alpha: 1).cgColor
        b.contentEdgeInsets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        b.heightAnchor.constraint(equalToConstant: 32).isActive = true
        b.action = action
        chipStack.addArrangedSubview(b)
    }

    // MARK: - 멈춤 알림 (키보드 자리)

    private func renderPausePanel() {
        guard let hits = lastHits, let target = hits.highlightTarget else { return }

        let panel = UIStackView()
        panel.axis = .vertical
        panel.spacing = 5
        panel.backgroundColor = .white
        panel.layer.cornerRadius = 14
        panel.isLayoutMarginsRelativeArrangement = true
        panel.layoutMargins = UIEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)
        panel.translatesAutoresizingMaskIntoConstraints = false
        keysArea.addSubview(panel)
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: keysArea.leadingAnchor, constant: 2),
            panel.trailingAnchor.constraint(equalTo: keysArea.trailingAnchor, constant: -2),
            panel.topAnchor.constraint(equalTo: keysArea.topAnchor, constant: 4),
            panel.bottomAnchor.constraint(equalTo: keysArea.bottomAnchor, constant: -4),
        ])

        let title = UILabel()
        title.text = "잠깐! 상대방이 상처받을 수 있어요"
        title.font = .boldSystemFont(ofSize: 16)
        title.textColor = ink
        panel.addArrangedSubview(title)
        let sub = UILabel()
        sub.text = "\(hits.riskLevel) · ‘\(target)’ · \(hits.emotion ?? "")의 마음은 살리고 공격적인 말만 빼 볼까요?"
        sub.font = .systemFont(ofSize: 12.5)
        sub.textColor = ink2
        sub.lineBreakMode = .byTruncatingTail
        panel.addArrangedSubview(sub)

        for s in hits.suggestions {
            let b = panelButton(s, bg: accentSoft, fg: accent, align: .left) { [weak self] in
                guard let self = self else { return }
                Stats.log(Stats.detect, self.sessionWord)
                Stats.log(Stats.change, self.sessionWord)
                self.sessionChanged = true
                let send = self.pauseFromReturn
                if send { self.sessionLogged = true }
                self.replaceAll(with: s)
                self.exitConfirm()
                if send { self.textDocumentProxy.insertText("\n") }
                self.scheduleAnalyze()
            }
            panel.addArrangedSubview(b)
        }

        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 6
        row.distribution = .fillEqually
        row.addArrangedSubview(panelButton("직접 고칠게요", bg: specialColor, fg: ink, align: .center) { [weak self] in
            self?.exitConfirm()
        })
        row.addArrangedSubview(panelButton(pauseFromReturn ? "그대로 보내기" : "그대로 쓸게요",
                                           bg: UIColor(red: 0.93, green: 0.95, blue: 0.94, alpha: 1), fg: ink2, align: .center) { [weak self] in
            guard let self = self else { return }
            if self.pauseFromReturn {
                Stats.log(Stats.detect, target)
                Stats.log(Stats.keep, target)
                self.sessionLogged = true
                self.exitConfirm()
                self.textDocumentProxy.insertText("\n")
            } else {
                self.exitConfirm()
            }
        })
        panel.addArrangedSubview(row)
    }

    private func panelButton(_ s: String, bg: UIColor, fg: UIColor, align: UIControl.ContentHorizontalAlignment,
                             action: @escaping () -> Void) -> UIButton {
        let b = ClosureButton(type: .custom)
        b.setTitle(s, for: .normal)
        b.setTitleColor(fg, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 15)
        b.titleLabel?.lineBreakMode = .byTruncatingTail
        b.backgroundColor = bg
        b.layer.cornerRadius = 8
        b.contentHorizontalAlignment = align
        b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 12, bottom: 0, right: 12)
        b.heightAnchor.constraint(equalToConstant: 34).isActive = true
        b.action = action
        return b
    }
}

// MARK: - 작은 부품

/// 가중치대로 가로 폭을 나누는 키 한 줄
final class RowView: UIView {
    private var items: [(UIView, CGFloat)] = []

    func add(_ v: UIView, weight: CGFloat) {
        items.append((v, weight))
        addSubview(v)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let total = items.reduce(0) { $0 + $1.1 }
        guard total > 0 else { return }
        let unit = bounds.width / total
        var x: CGFloat = 0
        for (v, w) in items {
            v.frame = CGRect(x: x, y: 0, width: unit * w, height: bounds.height).insetBy(dx: 2.5, dy: 4)
            x += unit * w
        }
    }
}

final class KeyButton: UIButton {
    var key = ""
}

final class ClosureButton: UIButton {
    var action: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        addTarget(self, action: #selector(run), for: .touchUpInside)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        addTarget(self, action: #selector(run), for: .touchUpInside)
    }

    @objc private func run() {
        UIDevice.current.playInputClick()
        action?()
    }
}

final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: UIEdgeInsets(top: 3, left: 9, bottom: 3, right: 9)))
    }

    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + 18, height: s.height + 6)
    }
}
