package com.hanbeonde.keyboard;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 「한 번 더」 키보드.
 * 감지: 입력 중인 문장에서 외모 비하 표현을 찾아 키보드 위 알림줄에 표시
 * 멈춤: '보내기' 키를 누르면 한 번 멈추고 그대로 보낼지 바꿀지 고르게 함
 * 대체: 원래 의도를 살린 표현을 추천하고, 누르면 입력창 문장을 바꿔 줌
 */
public class HanbeondeIME extends InputMethodService {

    private enum Mode { KO, EN, SYM }

    // 색
    private static final int BG = Color.parseColor("#D9DFDD");
    private static final int KEY = Color.WHITE;
    private static final int KEY_PRESSED = Color.parseColor("#C3CCC9");
    private static final int KEY_SPECIAL = Color.parseColor("#B9C3C0");
    private static final int INK = Color.parseColor("#16201E");
    private static final int INK2 = Color.parseColor("#4D5A57");
    private static final int ACCENT = Color.parseColor("#0E6B63");
    private static final int ACCENT_SOFT = Color.parseColor("#DCEFEC");
    private static final int WARN = Color.parseColor("#D6363A");
    private static final int WARN_SOFT = Color.parseColor("#FDECEC");
    private static final int GOLD_SOFT = Color.parseColor("#FBF5E6");

    private static final String[][] KO = {
            {"ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ"},
            {"ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"},
            {"⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"},
    };
    private static final String[][] KO_SHIFT = {
            {"ㅃ", "ㅉ", "ㄸ", "ㄲ", "ㅆ", "ㅛ", "ㅕ", "ㅑ", "ㅒ", "ㅖ"},
            {"ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ"},
            {"⇧", "ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ", "⌫"},
    };
    private static final String[][] EN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"},
    };
    private static final String[][] SYM = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "₩", "&", "@", "\""},
            {"#", ".", ",", "?", "!", "'", "~", "^", "⌫"},
    };

    private Mode mode = Mode.KO;
    private Mode letterMode = Mode.KO;
    private boolean shift = false;
    private final ArrayList<Character> jamo = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout keysBox;
    private TextView statusView;
    private LinearLayout chipRow;
    private HorizontalScrollView chipScroll;
    private EditorInfo editorInfo;
    private boolean passwordField;

    // 분석 상태 (한 메시지 단위)
    private String lastText = "";
    private Engine.Result lastHits = null; // 알림을 띄울 만한 감지 결과 (없으면 null)
    private boolean sessionDetected, sessionChanged, sessionLogged, sessionPaused;
    private boolean pauseFromEnter;
    private String sessionWord = "";
    private long lastDeleteAt;
    private boolean confirmMode;

    private final Runnable analyzeTask = this::analyze;
    /** 멈춤: 비하 표현을 쓰고 손을 멈추면 키보드 자리에 알림을 띄운다 */
    private final Runnable pauseTask = () -> {
        if (lastHits == null || confirmMode) return;
        sessionPaused = true;
        confirmMode = true;
        pauseFromEnter = false;
        renderKeys();
        renderToolbar(lastText, lastHits);
    };
    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            backspace();
            handler.postDelayed(this, 55);
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ---------------- 화면 ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        EngineHolder.load(this); // 단어 DB를 미리 읽어 둔다
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(4), dp(6), dp(4), dp(6));

        // 알림줄: 상태 + 추천
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(6), 0, dp(6), 0);
        TextView brand = new TextView(this);
        brand.setText("한 번 더");
        brand.setTextColor(ACCENT);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusRow.addView(brand);
        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(9), dp(4), dp(9), dp(4));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        statusRow.addView(statusView, sp);
        root.addView(statusRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipRow.setPadding(dp(4), 0, dp(4), 0);
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        cp.bottomMargin = dp(4);
        root.addView(chipScroll, cp);

        keysBox = new LinearLayout(this);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(keysBox);

        renderKeys();
        renderToolbar("", null);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        editorInfo = info;
        jamo.clear();
        shift = false;
        confirmMode = false;
        passwordField = isPassword(info);
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        mode = (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) ? Mode.SYM : letterMode;
        if (!restarting) {
            resetSession();
            lastText = currentText();
        }
        renderKeys();
        scheduleAnalyze();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        jamo.clear();
        handler.removeCallbacks(repeatDelete);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        // 사용자가 커서를 옮기거나 앱이 입력창을 비우면 조합을 끝낸다
        if (!jamo.isEmpty() && (candidatesEnd < 0 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            jamo.clear();
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
        }
        scheduleAnalyze();
    }

    private static boolean isPassword(EditorInfo info) {
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    // ---------------- 키 배치 ----------------

    private void renderKeys() {
        if (keysBox == null) return;
        handler.removeCallbacks(repeatDelete);
        keysBox.removeAllViews();
        if (confirmMode && lastHits != null) {
            renderPausePanel();
            return;
        }
        String[][] rows;
        if (mode == Mode.SYM) rows = SYM;
        else if (mode == Mode.EN) rows = EN;
        else rows = shift ? KO_SHIFT : KO;

        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = newRow();
            boolean shortRow = rows[r].length == 9 && r == 1;
            if (shortRow) addSpacer(row, 0.5f);
            for (String k : rows[r]) {
                float weight = ("⇧".equals(k) || "⌫".equals(k)) ? 1.5f : 1f;
                if (r == 2 && mode == Mode.SYM && "#".equals(k)) weight = 1.5f;
                addKey(row, k, weight);
            }
            if (shortRow) addSpacer(row, 0.5f);
            keysBox.addView(row);
        }
        LinearLayout last = newRow();
        addKey(last, mode == Mode.SYM ? (letterMode == Mode.KO ? "가" : "ABC") : "123", 1.4f);
        if (mode != Mode.SYM) addKey(last, mode == Mode.KO ? "A" : "한", 1.2f);
        addKey(last, ",", 1f);
        addKey(last, "space", mode == Mode.SYM ? 5.2f : 4f);
        addKey(last, ".", 1f);
        addKey(last, "⏎", 1.8f);
        keysBox.addView(last);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return row;
    }

    private void addSpacer(LinearLayout row, float weight) {
        View v = new View(this);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
    }

    private boolean isSpecial(String k) {
        return k.equals("⇧") || k.equals("⌫") || k.equals("123") || k.equals("가") || k.equals("ABC")
                || k.equals("A") || k.equals("한") || k.equals("⏎");
    }

    private Drawable keyBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(7));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(7));
        return new RippleDrawable(ColorStateList.valueOf(KEY_PRESSED), shape, mask);
    }

    private String label(String k) {
        switch (k) {
            case "space":
                return mode == Mode.KO ? "한 번 더" : "space";
            case "⏎":
                return enterLabel();
            case "⇧":
                return shift ? "⬆" : "⇧";
            default:
                if (mode == Mode.EN && shift && k.length() == 1) return k.toUpperCase();
                return k;
        }
    }

    private void addKey(LinearLayout row, final String k, float weight) {
        TextView v = new TextView(this);
        v.setText(label(k));
        v.setGravity(Gravity.CENTER);
        v.setTextColor("⏎".equals(k) ? Color.WHITE : INK);
        boolean special = isSpecial(k);
        boolean small = special || "space".equals(k);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 14 : 20);
        if ("space".equals(k)) v.setTextColor(INK2);
        int bg = "⏎".equals(k) ? ACCENT : (special ? KEY_SPECIAL : KEY);
        if ("⇧".equals(k) && shift) bg = Color.WHITE;
        v.setBackground(keyBackground(bg));
        v.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2.5f), dp(4), dp(2.5f), dp(4));
        row.addView(v, lp);

        if ("⌫".equals(k)) {
            v.setOnTouchListener((view, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setPressed(true);
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        backspace();
                        handler.postDelayed(repeatDelete, 420);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        handler.removeCallbacks(repeatDelete);
                        return true;
                    default:
                        return true;
                }
            });
        } else {
            v.setOnClickListener(view -> {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onKey(k);
            });
        }
    }

    private String enterLabel() {
        if (editorInfo == null) return "⏎";
        if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎";
        switch (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEND:
                return "보내기";
            case EditorInfo.IME_ACTION_SEARCH:
                return "검색";
            case EditorInfo.IME_ACTION_GO:
                return "이동";
            case EditorInfo.IME_ACTION_DONE:
                return "완료";
            case EditorInfo.IME_ACTION_NEXT:
                return "다음";
            default:
                return "⏎";
        }
    }

    // ---------------- 입력 ----------------

    private void onKey(String k) {
        handler.removeCallbacks(pauseTask);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (k) {
            case "⇧":
                shift = !shift;
                renderKeys();
                return;
            case "123":
                finishComposing();
                mode = Mode.SYM;
                shift = false;
                renderKeys();
                return;
            case "가":
            case "ABC":
                mode = letterMode;
                renderKeys();
                return;
            case "A":
            case "한":
                finishComposing();
                mode = mode == Mode.KO ? Mode.EN : Mode.KO;
                letterMode = mode;
                shift = false;
                renderKeys();
                return;
            case "space":
                commitPlain(" ");
                return;
            case "⏎":
                enter();
                return;
            default:
                break;
        }
        char c = k.charAt(0);
        if (mode == Mode.KO && k.length() == 1 && Hangul.isJamo(c)) {
            typeJamo(c);
        } else {
            String out = (mode == Mode.EN && shift) ? k.toUpperCase() : k;
            commitPlain(out);
        }
        if (shift) {
            shift = false;
            renderKeys();
        }
    }

    private void typeJamo(char c) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        jamo.add(c);
        Hangul.Result r = Hangul.compose(jamo);
        ic.beginBatchEdit();
        if (!r.committed.isEmpty()) ic.commitText(r.committed, 1);
        ic.setComposingText(r.composing, 1);
        ic.endBatchEdit();
        if (r.lastStart > 0) {
            List<Character> tail = new ArrayList<>(jamo.subList(r.lastStart, jamo.size()));
            jamo.clear();
            jamo.addAll(tail);
        }
    }

    private void finishComposing() {
        if (jamo.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
        jamo.clear();
    }

    private void commitPlain(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        finishComposing();
        ic.commitText(s, 1);
    }

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastDeleteAt = System.currentTimeMillis();
        handler.removeCallbacks(pauseTask);
        if (!jamo.isEmpty()) {
            jamo.remove(jamo.size() - 1);
            if (jamo.isEmpty()) {
                ic.setComposingText("", 1);
                ic.finishComposingText();
            } else {
                Hangul.Result r = Hangul.compose(jamo);
                ic.setComposingText(r.committed + r.composing, 1);
            }
            return;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }

    private int sendAction() {
        if (editorInfo == null) return EditorInfo.IME_ACTION_NONE;
        if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return EditorInfo.IME_ACTION_NONE;
        int a = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        return (a == EditorInfo.IME_ACTION_UNSPECIFIED) ? EditorInfo.IME_ACTION_NONE : a;
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        finishComposing();
        int action = sendAction();
        if (action == EditorInfo.IME_ACTION_NONE) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            return;
        }
        // 멈춤: 보내기 키를 눌렀는데 비하 표현이 있으면 한 번 멈춘다
        String text = currentText();
        Engine.Result hits = check(text);
        if (action == EditorInfo.IME_ACTION_SEND && hits != null && Stats.alertOn(this) && !confirmMode) {
            handler.removeCallbacks(pauseTask);
            lastText = text;
            lastHits = hits;
            confirmMode = true;
            pauseFromEnter = true;
            renderKeys();
            renderToolbar(text, hits);
            return;
        }
        ic.performEditorAction(action);
    }

    private String currentText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence b = ic.getTextBeforeCursor(1000, 0);
        CharSequence a = ic.getTextAfterCursor(1000, 0);
        return (b == null ? "" : b.toString()) + (a == null ? "" : a.toString());
    }

    /** 입력창 문장 전체를 추천 문장으로 바꾼다 */
    private void replaceAll(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        finishComposing();
        CharSequence b = ic.getTextBeforeCursor(1000, 0);
        CharSequence a = ic.getTextAfterCursor(1000, 0);
        ic.beginBatchEdit();
        ic.deleteSurroundingText(b == null ? 0 : b.length(), a == null ? 0 : a.length());
        ic.commitText(s, 1);
        ic.endBatchEdit();
    }

    // ---------------- 감지와 기록 ----------------

    private void resetSession() {
        sessionDetected = false;
        sessionChanged = false;
        sessionLogged = false;
        sessionPaused = false;
        sessionWord = "";
        confirmMode = false;
        pauseFromEnter = false;
        handler.removeCallbacks(pauseTask);
    }

    private void exitConfirm() {
        confirmMode = false;
        pauseFromEnter = false;
        renderKeys();
        renderToolbar(lastText, lastHits);
    }

    private void scheduleAnalyze() {
        handler.removeCallbacks(analyzeTask);
        handler.postDelayed(analyzeTask, 120);
    }

    private void analyze() {
        if (statusView == null) return;
        if (passwordField) {
            renderToolbar("", null);
            return;
        }
        String text = currentText();
        Engine.Result hits = check(text);
        long now = System.currentTimeMillis();

        // 입력창이 한꺼번에 비워졌다 = 메시지를 보냈다 (지우기 키로 지운 경우는 제외)
        if (text.trim().isEmpty() && !lastText.trim().isEmpty()) {
            boolean deletedByUser = now - lastDeleteAt < 1500;
            if (!deletedByUser) {
                if (!sessionLogged) {
                    if (lastHits != null) {
                        Stats.log(this, Stats.DETECT, lastHits.highlightTarget);
                        Stats.log(this, Stats.KEEP, lastHits.highlightTarget);
                    } else {
                        if (sessionDetected && !sessionChanged) {
                            Stats.log(this, Stats.DETECT, sessionWord);
                            Stats.log(this, Stats.CHANGE, sessionWord);
                        }
                        if (isPraise(lastText)) Stats.log(this, Stats.PRAISE, "");
                    }
                }
                Stats.log(this, Stats.SENT, "");
                showNewBadges();
            }
            boolean wasConfirm = confirmMode;
            resetSession();
            lastHits = null;
            if (wasConfirm) renderKeys();
        }

        if (hits != null && !sessionDetected) {
            sessionDetected = true;
            sessionWord = hits.highlightTarget;
        }
        lastText = text;
        lastHits = hits;
        if (hits == null && confirmMode) {
            exitConfirm();
            return;
        }
        if (hits != null && !sessionPaused && !confirmMode && Stats.alertOn(this)) {
            handler.removeCallbacks(pauseTask);
            handler.postDelayed(pauseTask, 700);
        }
        renderToolbar(text, hits);
    }

    private void showNewBadges() {
        for (String b : Stats.newBadges(this)) {
            Toast.makeText(this, "배지 획득! 「" + b + "」", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 알림줄 ----------------

    private void setStatus(String text, int fg, int bg) {
        statusView.setText(text);
        statusView.setTextColor(fg);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(20));
        statusView.setBackground(d);
    }

    private void renderToolbar(String text, Engine.Result hits) {
        if (statusView == null) return;
        chipRow.removeAllViews();
        if (passwordField) {
            setStatus("비밀번호 칸에서는 분석하지 않아요", INK2, ACCENT_SOFT);
            return;
        }
        if (confirmMode && hits != null) {
            setStatus("멈춤 · 보내기 전에 한 번 더 생각해 봐요", Color.WHITE, WARN);
            return;
        }
        if (hits != null) {
            String first = "‘" + hits.highlightTarget + "’";
            String more = hits.detectedWords.size() > 1 ? " 외 " + (hits.detectedWords.size() - 1) + "개" : "";
            setStatus(hits.riskLevel + " · " + first + more + " · " + hits.category, levelColor(hits.level), WARN_SOFT);
            addLabel("바꿔 볼까요?");
            for (final String s : hits.suggestions) {
                addChip(s, ACCENT_SOFT, ACCENT, v -> {
                    Stats.log(this, Stats.DETECT, sessionWord);
                    Stats.log(this, Stats.CHANGE, sessionWord);
                    sessionChanged = true;
                    replaceAll(s);
                });
            }
            return;
        }
        if (text.trim().isEmpty()) setStatus("보내기 전에 한 번 더 살펴볼게요", ACCENT, ACCENT_SOFT);
        else setStatus("좋아요, 다듬을 표현이 없어요", ACCENT, ACCENT_SOFT);
        addLabel("칭찬 한마디");
        for (final String s : PRAISE_SUGGESTIONS) {
            addChip(s, GOLD_SOFT, INK, v -> {
                Stats.log(this, Stats.PRAISE_CHIP, "");
                commitPlain(text.isEmpty() || text.endsWith(" ") ? s : " " + s);
                showNewBadges();
            });
        }
    }

    /** 키보드 자리에 뜨는 멈춤 알림: 추천 표현 / 직접 고치기 / 그대로 */
    private void renderPausePanel() {
        final String text = lastText;
        final Engine.Result hits = lastHits;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52 * 4 - 8));
        plp.setMargins(dp(2), dp(4), dp(2), dp(4));
        keysBox.addView(panel, plp);

        TextView title = new TextView(this);
        title.setText("잠깐! 상대방이 상처받을 수 있어요");
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        panel.addView(title);
        TextView sub = new TextView(this);
        sub.setText(hits.riskLevel + " · ‘" + hits.highlightTarget + "’ · " + hits.emotion + "의 마음은 살리고 공격적인 말만 빼 볼까요?");
        sub.setTextColor(INK2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(6);
        panel.addView(sub, slp);

        for (final String s : hits.suggestions) {
            TextView b = panelButton(s, ACCENT_SOFT, ACCENT, Gravity.CENTER_VERTICAL | Gravity.START);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                Stats.log(this, Stats.DETECT, sessionWord);
                Stats.log(this, Stats.CHANGE, sessionWord);
                sessionChanged = true;
                boolean send = pauseFromEnter;
                if (send) sessionLogged = true;
                replaceAll(s);
                exitConfirm();
                InputConnection ic = getCurrentInputConnection();
                if (send && ic != null) ic.performEditorAction(EditorInfo.IME_ACTION_SEND);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
            lp.bottomMargin = dp(4);
            panel.addView(b, lp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        rlp.topMargin = dp(2);
        panel.addView(row, rlp);
        TextView edit = panelButton("직접 고칠게요", KEY_SPECIAL, INK, Gravity.CENTER);
        edit.setOnClickListener(v -> exitConfirm());
        LinearLayout.LayoutParams e1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        e1.rightMargin = dp(6);
        row.addView(edit, e1);
        TextView keep = panelButton(pauseFromEnter ? "그대로 보내기" : "그대로 쓸게요", Color.parseColor("#EEF1F0"), INK2, Gravity.CENTER);
        keep.setOnClickListener(v -> {
            if (pauseFromEnter) {
                Stats.log(this, Stats.DETECT, hits.highlightTarget);
                Stats.log(this, Stats.KEEP, hits.highlightTarget);
                sessionLogged = true;
                exitConfirm();
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.performEditorAction(EditorInfo.IME_ACTION_SEND);
            } else {
                exitConfirm();
            }
        });
        row.addView(keep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private TextView panelButton(String s, int bg, int fg, int gravity) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(gravity);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setBackground(keyBackground(bg));
        return t;
    }

    private static final String[] PRAISE_SUGGESTIONS = {
            "발표 준비 진짜 열심히 하더라", "덕분에 조별과제 끝났다 고마워", "오늘 축구 수비 대단했어", "그거 끝까지 해낸 거 멋있다"};
    private static final java.util.regex.Pattern PRAISE = java.util.regex.Pattern.compile(
            "수고|고생|대단|잘했|열심히|고마워|덕분|노력|잘하더라|잘 부르|잘 뛰|최고야|짱이다|멋지게 해");

    private static boolean isPraise(String s) { return s != null && PRAISE.matcher(s).find(); }

    /** 감지 결과 중 알림을 띄울 만한 것만 (기본: MEDIUM 이상, 설정에 따라 LOW 포함) */
    private Engine.Result check(String text) {
        if (passwordField) return null;
        Engine e = EngineHolder.get(this);
        if (e == null) return null;
        Engine.Result r = e.analyze(text);
        return (r.isHarmful && r.level >= (Stats.lowOn(this) ? 0 : 1)) ? r : null;
    }

    private static int levelColor(int level) {
        switch (level) {
            case 3: return Color.parseColor("#C62828");
            case 2: return Color.parseColor("#D9480F");
            case 1: return Color.parseColor("#9A6412");
            default: return Color.parseColor("#5F6F6B");
        }
    }

    private void addLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(INK2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setPadding(dp(4), 0, dp(6), 0);
        chipRow.addView(t);
    }

    private void addChip(String s, int bg, int fg, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(20));
        d.setStroke(dp(1), Color.parseColor("#BCC6C3"));
        t.setBackground(d);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(6);
        chipRow.addView(t, lp);
    }
}
