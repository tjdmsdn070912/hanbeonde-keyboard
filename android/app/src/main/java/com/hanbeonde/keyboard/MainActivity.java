package com.hanbeonde.keyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Map;

/** 「한 번 더」 앱 화면: 키보드 켜기 안내, 말 습관 리포트, 칭찬 미션, 설정 */
public class MainActivity extends Activity {

    private static final int PAGE = Color.parseColor("#EEF2F1");
    private static final int INK = Color.parseColor("#16201E");
    private static final int INK2 = Color.parseColor("#4D5A57");
    private static final int INK3 = Color.parseColor("#7C8784");
    private static final int ACCENT = Color.parseColor("#0E6B63");
    private static final int ACCENT_SOFT = Color.parseColor("#DCEFEC");
    private static final int WARN = Color.parseColor("#D6363A");
    private static final int GOLD = Color.parseColor("#B7791F");
    private static final int LINE = Color.parseColor("#D5DDDB");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        EngineHolder.load(this);
        getWindow().setStatusBarColor(PAGE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setContentView(build());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 키보드 선택 창을 닫고 돌아왔을 때, 상태가 바뀌었으면 화면 갱신
        if (hasFocus && (keyboardEnabled() != shownOn || keyboardSelected() != shownSelected)) setContentView(build());
    }

    private boolean shownOn, shownSelected;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ---------- 키보드 상태 ----------
    private boolean keyboardEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private boolean keyboardSelected() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id != null && id.startsWith(getPackageName() + "/");
    }

    // ---------- 화면 ----------
    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(28), dp(18), dp(32));
        scroll.addView(col);

        TextView title = text("한 번 더", 30, ACCENT, true);
        col.addView(title);
        TextView tag = text("보내기 전에 한 번 더, 내 말을 다듬어 주는 키보드", 15, INK2, false);
        tag.setPadding(0, dp(2), 0, dp(18));
        col.addView(tag);

        buildSetup(col);
        buildReport(col);
        buildMissions(col);
        buildSettings(col);
        return scroll;
    }

    private void buildSetup(LinearLayout col) {
        boolean on = keyboardEnabled(), selected = keyboardSelected();
        shownOn = on;
        shownSelected = selected;
        LinearLayout card = card(col, on && selected ? "키보드 준비 완료" : "시작하기");
        if (!(on && selected)) {
            card.addView(step("1", "설정에서 ‘한 번 더’ 켜기", on));
            card.addView(step("2", "쓰는 키보드를 ‘한 번 더’로 바꾸기", selected));
            TextView warn = text("켤 때 ‘입력하는 모든 텍스트를 수집할 수 있다’는 안드로이드 기본 안내가 떠요. "
                    + "이 앱은 인터넷 권한이 아예 없어서 입력한 글이 휴대폰 밖으로 나갈 수 없어요.", 12.5f, INK3, false);
            warn.setPadding(0, dp(8), 0, dp(10));
            card.addView(warn);
            if (!on) {
                card.addView(button("1. 키보드 켜러 가기", true, v ->
                        startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
            }
            card.addView(button((on ? "" : "2. ") + "‘한 번 더’ 키보드로 바꾸기", on, v -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showInputMethodPicker();
            }));
        } else {
            card.addView(text("이제 카카오톡, 인스타그램 DM 등 어디서든 키보드 위에 알림줄이 보여요. 아래 칸에서 먼저 써 보세요.", 13.5f, INK2, false));
        }
        EditText try_ = new EditText(this);
        try_.setHint("여기서 써 보세요. 예) 너 진짜 돼지야ㅋㅋ");
        try_.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        try_.setSingleLine(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.addView(try_, lp);
    }

    private void buildReport(LinearLayout col) {
        Stats.Week w = Stats.week(this);
        LinearLayout card = card(col, "내 말 습관 리포트");
        card.addView(text("최근 7일 · 대화 내용은 저장하지 않고 횟수만 기록해요", 12.5f, INK3, false));

        TextView big = text(w.prevDetect + "회  →  " + w.detect + "회", 28, INK, true);
        big.setPadding(0, dp(10), 0, 0);
        card.addView(big);
        int diff = w.prevDetect - w.detect;
        String delta = w.prevDetect == 0 && w.detect == 0 ? "아직 기록이 없어요. 키보드를 쓰면 여기에 쌓여요."
                : diff > 0 ? "외모 비하 표현이 지난주보다 " + diff + "회 줄었어요"
                : diff < 0 ? "지난주보다 " + (-diff) + "회 늘었어요" : "지난주와 같아요";
        card.addView(text(delta, 13.5f, diff > 0 ? ACCENT : INK2, false));

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(0, dp(12), 0, dp(8));
        int rate = w.detect == 0 ? 0 : Math.round(w.change * 100f / w.detect);
        tiles.addView(tile(w.change + "회", "바꿔서 보냄"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tiles.addView(tile(Math.min(rate, 100) + "%", "바꾼 비율"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tiles.addView(tile(w.keep + "회", "그대로 보냄"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(tiles);

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.addView(legendItem(ChartView.SERIES_DETECT, "감지"));
        legend.addView(legendItem(ChartView.SERIES_CHANGE, "바꿔서 보냄"));
        card.addView(legend);
        ChartView chart = new ChartView(this);
        chart.setData(w.detectByDay, w.changeByDay, w.dayStart);
        card.addView(chart);

        TextView h = text("자주 쓴 표현", 14, INK, true);
        h.setPadding(0, dp(14), 0, dp(6));
        card.addView(h);
        if (w.topWords.isEmpty()) {
            card.addView(text("이번 주에 감지된 표현이 없어요.", 13, INK3, false));
        } else {
            int max = w.topWords.get(0).getValue();
            for (Map.Entry<String, Integer> e : w.topWords) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));
                row.addView(text(e.getKey(), 13.5f, INK, false), new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
                ProgressBar pb = progress(e.getValue(), max, WARN);
                row.addView(pb, new LinearLayout.LayoutParams(0, dp(8), 1));
                TextView n = text(e.getValue() + "회", 13, INK2, false);
                n.setGravity(Gravity.END);
                row.addView(n, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(row);
            }
        }
    }

    private void buildMissions(LinearLayout col) {
        LinearLayout card = card(col, "칭찬 미션");
        card.addView(text("“잘생겼다”보다 “준비 열심히 했더라”처럼, 그 사람이 한 일을 칭찬해 보세요.", 13, INK2, false));
        for (Stats.Mission m : Stats.missions(this)) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, dp(14), 0, 0);
            TextView t = text((m.done() ? "★ " : "☆ ") + m.title, 15, m.done() ? GOLD : INK, true);
            box.addView(t);
            TextView d = text(m.desc, 12.5f, INK2, false);
            d.setPadding(0, dp(2), 0, dp(6));
            box.addView(d);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(progress(m.current, m.goal, m.done() ? GOLD : ACCENT), new LinearLayout.LayoutParams(0, dp(8), 1));
            TextView s = text(m.done() ? "배지 「" + m.badge + "」" : m.current + " / " + m.goal, 12.5f, m.done() ? GOLD : INK2, m.done());
            s.setPadding(dp(10), 0, 0, 0);
            row.addView(s);
            box.addView(row);
            card.addView(box);
        }
    }

    private void buildSettings(LinearLayout col) {
        LinearLayout card = card(col, "설정");
        Switch alert = toggle("멈춤 알림", "욕설·비속어·패드립·외모 비하 표현을 쓰고 잠깐 멈추면 키보드 자리에 경고와 순화안을 띄워요. 끄면 알림줄 표시만 해요.", Stats.alertOn(this));
        alert.setOnCheckedChangeListener((b, v) -> Stats.setAlert(this, v));
        card.addView(alert);
        Switch low = toggle("가벼운 표현도 알림", "‘오징어’, ‘메주’처럼 가벼운 외모 표현에도 멈춤 알림을 띄워요. 욕설·패드립·비속어는 항상 알려요.", Stats.lowOn(this));
        low.setOnCheckedChangeListener((b, v) -> Stats.setLow(this, v));
        card.addView(low);

        LinearLayout privacy = card(col, "내 대화는 어떻게 다뤄지나요?");
        privacy.addView(bullet("문장 분석은 휴대폰 안에서만 해요. 이 앱에는 인터넷 권한이 없어요."));
        privacy.addView(bullet("리포트에는 감지 횟수, 바꾼 횟수, 표현 이름만 남아요. 대화 내용은 저장하지 않아요."));
        privacy.addView(bullet("비밀번호 칸에서는 분석하지 않아요."));
        privacy.addView(bullet("부모님이나 선생님에게 알리는 기능은 없어요. 나를 위한 도구예요."));

        LinearLayout data = card(col, "기록 관리");
        data.addView(button("발표용 예시 기록 넣기", false, v -> confirm("예시 기록 넣기",
                "지금 기록을 지우고 ‘지난주 12회 → 이번 주 5회’ 예시 기록으로 바꿀까요?", () -> {
                    Stats.loadDemo(this);
                    setContentView(build());
                })));
        data.addView(button("내 기록 모두 지우기", false, v -> confirm("기록 지우기",
                "리포트와 미션 기록을 모두 지울까요? 되돌릴 수 없어요.", () -> {
                    Stats.clear(this);
                    setContentView(build());
                })));
    }

    // ---------- 작은 부품 ----------
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), LINE);
        c.setBackground(bg);
        TextView t = text(title, 17, INK, true);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private View step(String n, String label, boolean done) {
        TextView t = text((done ? "✓ " : n + ". ") + label + (done ? "  완료" : ""), 14.5f, done ? ACCENT : INK, !done);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(primary ? Color.WHITE : INK);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), primary ? ACCENT : LINE);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View tile(String value, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(value, 20, INK, true));
        t.addView(text(label, 12, INK2, false));
        return t;
    }

    private View legendItem(int color, String label) {
        TextView t = text("■ " + label + "   ", 12, INK2, false);
        android.text.SpannableString s = new android.text.SpannableString(t.getText());
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(s);
        return t;
    }

    private ProgressBar progress(int v, int max, int color) {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, max));
        pb.setProgress(v);
        pb.setProgressTintList(ColorStateList.valueOf(color));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E3E8E6")));
        return pb;
    }

    private Switch toggle(String title, String desc, boolean checked) {
        Switch s = new Switch(this);
        s.setText(title + "\n" + desc);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        s.setTextColor(INK);
        s.setChecked(checked);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        s.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, Color.parseColor("#B9C3C0")}));
        return s;
    }

    private View bullet(String s) {
        TextView t = text("•  " + s, 13.5f, INK2, false);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private void confirm(String title, String msg, Runnable ok) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("확인", (d, w) -> ok.run())
                .setNegativeButton("취소", null)
                .show();
    }
}
