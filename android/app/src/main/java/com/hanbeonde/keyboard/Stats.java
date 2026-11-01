package com.hanbeonde.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 말 습관 기록. 대화 내용은 저장하지 않고 "언제, 무슨 일(감지/바꿈/그대로/칭찬/전송), 어떤 표현"만 기기 안에 남긴다.
 * 이 앱에는 인터넷 권한이 없어서 기록이 밖으로 나갈 수 없다.
 */
public final class Stats {

    public static final String DETECT = "detect";   // 비하 표현이 감지됨
    public static final String CHANGE = "change";   // 바꿔서 보냄 (추천 사용 또는 직접 고침)
    public static final String KEEP = "keep";       // 그대로 보냄
    public static final String PRAISE = "praise";   // 노력·행동 칭찬을 보냄
    public static final String SENT = "sent";       // 메시지를 보냄
    public static final String PRAISE_CHIP = "praiseChip"; // 칭찬 한마디 추천 사용

    private static final String PREFS = "hanbeonde";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_BADGES = "badges";
    private static final long DAY = 24L * 60 * 60 * 1000;

    public static final class Event {
        public final long t;
        public final String type;
        public final String word;

        Event(long t, String type, String word) {
            this.t = t;
            this.type = type;
            this.word = word;
        }
    }

    public static final class Mission {
        public final String id, title, desc, badge;
        public final int current, goal;

        Mission(String id, String title, String desc, String badge, int current, int goal) {
            this.id = id;
            this.title = title;
            this.desc = desc;
            this.badge = badge;
            this.current = Math.min(current, goal);
            this.goal = goal;
        }

        public boolean done() { return current >= goal; }
    }

    public static final class Week {
        public final int[] detectByDay = new int[7]; // [0]=6일 전 … [6]=오늘
        public final int[] changeByDay = new int[7];
        public final long[] dayStart = new long[7];
        public int detect, change, keep, praise, prevDetect;
        public final List<Map.Entry<String, Integer>> topWords = new ArrayList<>();
    }

    private Stats() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 설정 ----------
    public static boolean alertOn(Context c) { return prefs(c).getBoolean("alert", true); }
    public static boolean lowOn(Context c) { return prefs(c).getBoolean("low", false); }
    public static void setAlert(Context c, boolean v) { prefs(c).edit().putBoolean("alert", v).apply(); }
    public static void setLow(Context c, boolean v) { prefs(c).edit().putBoolean("low", v).apply(); }

    // ---------- 기록 ----------
    public static synchronized void log(Context c, String type, String word) {
        log(c, type, word, System.currentTimeMillis());
    }

    private static synchronized void log(Context c, String type, String word, long t) {
        List<Event> list = events(c);
        list.add(new Event(t, type, word));
        save(c, list);
    }

    public static synchronized List<Event> events(Context c) {
        List<Event> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_EVENTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Event(o.getLong("t"), o.getString("type"), o.optString("w", "")));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Event> list) {
        long cutoff = System.currentTimeMillis() - 60 * DAY; // 60일 지난 기록은 지운다
        JSONArray arr = new JSONArray();
        try {
            for (Event e : list) {
                if (e.t < cutoff) continue;
                JSONObject o = new JSONObject();
                o.put("t", e.t);
                o.put("type", e.type);
                if (e.word != null && !e.word.isEmpty()) o.put("w", e.word);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(KEY_EVENTS, arr.toString()).apply();
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().remove(KEY_EVENTS).remove(KEY_BADGES).apply();
    }

    /** 발표·시연용 예시 기록: 지난주 12회 감지 → 이번 주 5회 감지 */
    public static synchronized void loadDemo(Context c) {
        clear(c);
        List<Event> list = new ArrayList<>();
        long today = startOfDay(System.currentTimeMillis());
        String[] prevWords = {"씨발련아", "존나", "돼지야", "병신", "느금마", "존못", "지랄", "오크", "개새끼야", "존나", "멸치", "와꾸 씹창"};
        boolean[] prevChanged = {true, false, true, false, false, true, false, true, false, false, true, false};
        for (int i = 0; i < prevWords.length; i++) {
            long t = today - (8 + i % 6) * DAY + 10 * 60 * 60 * 1000L;
            list.add(new Event(t, DETECT, prevWords[i]));
            list.add(new Event(t + 1, prevChanged[i] ? CHANGE : KEEP, prevWords[i]));
        }
        String[] curWords = {"존나", "돼지야", "지랄", "씨발", "존나"};
        int[] curAgo = {6, 5, 4, 2, 1};
        boolean[] curChanged = {true, true, false, true, true};
        for (int i = 0; i < curWords.length; i++) {
            long t = today - curAgo[i] * DAY + 14 * 60 * 60 * 1000L;
            list.add(new Event(t, DETECT, curWords[i]));
            list.add(new Event(t + 1, curChanged[i] ? CHANGE : KEEP, curWords[i]));
        }
        list.add(new Event(today - 3 * DAY + 15 * 60 * 60 * 1000L, PRAISE, ""));
        save(c, list);
        markEarnedBadges(c);
    }

    // ---------- 집계 ----------
    public static long startOfDay(long t) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static int daysAgo(long today, long t) {
        return (int) Math.round((today - startOfDay(t)) / (double) DAY);
    }

    public static Week week(Context c) {
        Week w = new Week();
        long today = startOfDay(System.currentTimeMillis());
        for (int i = 0; i < 7; i++) w.dayStart[i] = today - (6 - i) * DAY;
        Map<String, Integer> words = new HashMap<>();
        for (Event e : events(c)) {
            int ago = daysAgo(today, e.t);
            if (ago >= 0 && ago < 7) {
                int idx = 6 - ago;
                switch (e.type) {
                    case DETECT:
                        w.detect++;
                        w.detectByDay[idx]++;
                        if (!e.word.isEmpty()) words.put(e.word, words.getOrDefault(e.word, 0) + 1);
                        break;
                    case CHANGE:
                        w.change++;
                        w.changeByDay[idx]++;
                        break;
                    case KEEP:
                        w.keep++;
                        break;
                    case PRAISE:
                        w.praise++;
                        break;
                    default:
                        break;
                }
            } else if (ago >= 7 && ago < 14 && DETECT.equals(e.type)) {
                w.prevDetect++;
            }
        }
        w.topWords.addAll(words.entrySet());
        Collections.sort(w.topWords, (a, b) -> b.getValue() - a.getValue());
        while (w.topWords.size() > 5) w.topWords.remove(w.topWords.size() - 1);
        return w;
    }

    // ---------- 칭찬 미션 ----------
    public static List<Mission> missions(Context c) {
        Week w = week(c);
        long today = startOfDay(System.currentTimeMillis());
        int sentToday = 0, keepToday = 0, praiseChip = 0;
        for (Event e : events(c)) {
            if (startOfDay(e.t) == today) {
                if (SENT.equals(e.type)) sentToday++;
                if (KEEP.equals(e.type)) keepToday++;
            }
            if (PRAISE_CHIP.equals(e.type)) praiseChip++;
        }
        List<Mission> list = new ArrayList<>();
        list.add(new Mission("praise3", "친구의 노력 칭찬하기",
                "외모 말고 노력·행동을 칭찬하는 메시지 3번 보내기 (이번 주)", "칭찬 장인", w.praise, 3));
        list.add(new Mission("change5", "한 번 더 다듬기",
                "비하 표현이 감지됐을 때 바꿔서 보내기 5번 (이번 주)", "말 다듬기 고수", w.change, 5));
        list.add(new Mission("clean", "오늘은 상처 0",
                "오늘 메시지 5개 이상 보내고, 비하 표현은 한 번도 그대로 보내지 않기", "클린 데이",
                keepToday > 0 ? 0 : sentToday, 5));
        list.add(new Mission("chip", "칭찬으로 먼저 말 걸기",
                "키보드의 ‘칭찬 한마디’ 추천을 한 번 써 보기", "먼저 다가가기", praiseChip, 1));
        return list;
    }

    private static Set<String> badges(Context c) {
        return new HashSet<>(prefs(c).getStringSet(KEY_BADGES, new HashSet<>()));
    }

    /** 새로 달성한 미션의 배지 이름을 돌려주고 저장한다. */
    public static List<String> newBadges(Context c) {
        Set<String> have = badges(c);
        List<String> fresh = new ArrayList<>();
        for (Mission m : missions(c)) {
            if (m.done() && !have.contains(m.id)) {
                have.add(m.id);
                fresh.add(m.badge);
            }
        }
        if (!fresh.isEmpty()) prefs(c).edit().putStringSet(KEY_BADGES, have).apply();
        return fresh;
    }

    private static void markEarnedBadges(Context c) {
        newBadges(c);
    }
}
