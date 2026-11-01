package com.hanbeonde.keyboard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 한 번 더(Hanbeonde) 감지 엔진 v2 — Java 포팅.
 * engine/hanbeonde-engine.js 와 같은 규칙, 같은 결과. 단어 DB는 assets/words.json.
 * 안드로이드 라이브러리에 기대지 않아서 일반 JVM에서도 시험할 수 있다.
 */
public final class Engine {

    public static final String[] LEVELS = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};
    private static final String PARTICLES = "아야이가는은을를들";
    private static final String TRIM = "\"'“”‘’()[]{}<>.,!?~…·:;";

    private static final Map<String, String[]> SUGGEST = new HashMap<>();
    private static final Map<String, String> EMOTION_LABEL = new HashMap<>();

    static {
        SUGGEST.put("P", new String[]{"가족 얘기는 하지 말자. 지금 나 많이 화났어", "너한테 정말 서운하고 화가 나", "우리 일은 우리끼리 얘기하자"});
        SUGGEST.put("P_joke", new String[]{"야 그건 선 넘었다ㅋㅋ 다른 얘기 하자", "가족 얘기는 장난이어도 좀 그렇다ㅋㅋ", "ㅋㅋㅋ 오늘 왜 이렇게 웃기냐"});
        SUGGEST.put("S_angry", new String[]{"너 때문에 정말 속상하고 화가 나", "일이 이렇게 돼서 너무 답답해", "다음부터는 주의해 줬으면 좋겠어"});
        SUGGEST.put("S_frustrated", new String[]{"일이 이렇게 돼서 너무 답답해", "아 진짜 마음대로 안 돼서 속상하다", "잠깐 쉬었다가 다시 해 보자"});
        SUGGEST.put("S_joke", new String[]{"아 진짜 웃기네ㅋㅋ", "너 오늘 왜 이렇게 웃기냐ㅋㅋ", "야 장난 그만ㅋㅋ"});
        SUGGEST.put("S_surprised", new String[]{"와 진짜 대박이다", "헐 진짜?", "완전 놀랐어"});
        SUGGEST.put("A_angry", new String[]{"너 때문에 좀 속상해", "그 행동은 나한테 좀 서운했어", "지금 기분이 좀 안 좋아. 이따 얘기하자"});
        SUGGEST.put("A_frustrated", new String[]{"아 오늘 일이 잘 안 풀린다", "좀 답답해서 그래", "다음엔 같이 잘해 보자"});
        SUGGEST.put("body", new String[]{"오늘 진짜 맛있게 먹더라", "너랑 밥 먹으면 재밌어", "이따 같이 매점 갈래?"});
        SUGGEST.put("face", new String[]{"오늘 많이 피곤해 보이는데 괜찮아?", "무슨 일 있었어? 분위기 다르다", "오늘 하루 어땠어?"});
        SUGGEST.put("slang", new String[]{"오늘 텐션 뭐야", "너 오늘 말빨 좋다", "오늘 뭔가 기분 좋아 보인다"});
        SUGGEST.put("hair", new String[]{"오늘 뭔가 분위기 달라졌다", "헤어 바꿨어? 새롭다", "오늘 스타일 신경 썼네"});
        SUGGEST.put("height", new String[]{"너 진짜 날쌔다", "너랑 다니면 재밌어", "오늘 뭐 하고 놀까?"});
        SUGGEST.put("thin", new String[]{"밥 같이 먹자", "너 체육 시간에 진짜 잘 뛰더라", "오늘 컨디션 어때?"});
        SUGGEST.put("part", new String[]{"요즘 많이 바쁘지?", "잠은 잘 잤어?", "오늘 하루 어땠어?"});
        SUGGEST.put("idol", new String[]{"너는 너만의 분위기가 있어", "너 그 노래 진짜 잘 부르더라", "주말에 무대 영상 같이 볼래?"});
        EMOTION_LABEL.put("joke", "장난");
        EMOTION_LABEL.put("frustrated", "답답함");
        EMOTION_LABEL.put("angry", "화남");
        EMOTION_LABEL.put("surprised", "놀람");
        EMOTION_LABEL.put("care", "관심");
    }

    private static final Pattern RE_LAUGH = Pattern.compile("[ㅋㅎ]{2,}|lol|\\^\\^", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_FRUSTRATED = Pattern.compile("답답|에휴|어휴|하아|한숨|미치겠|짜증");
    private static final Pattern RE_ANGRY = Pattern.compile("화나|화난|빡치|빡쳐|열받|꺼져|닥쳐|죽을래|!");
    private static final Pattern RE_SURPRISED = Pattern.compile("헐|대박|\\?");
    private static final Pattern RE_ENDS = Pattern.compile("[?ㅋ]$");

    // ---------------- 결과 ----------------
    public static final class Match {
        public final String word, riskLevel, category, matched, type, description;
        public final int start, end, level;
        final String group, sg;

        Match(String word, int start, int end, int level, String group, String category, String matched,
              String type, String description, String sg) {
            this.word = word;
            this.start = start;
            this.end = end;
            this.level = level;
            this.riskLevel = LEVELS[level];
            this.group = group;
            this.category = category;
            this.matched = matched;
            this.type = type;
            this.description = description;
            this.sg = sg;
        }
    }

    public static final class Result {
        public final boolean isHarmful;
        public final String riskLevel;      // CRITICAL / HIGH / MEDIUM / LOW / NONE
        public final int level;             // 3 / 2 / 1 / 0 / -1
        public final List<String> detectedWords;
        public final String category;
        public final List<String> suggestions;
        public final boolean showOverlay;
        public final String highlightTarget;
        public final String emotion;
        public final List<Match> matches;

        Result(boolean isHarmful, String riskLevel, int level, List<String> detectedWords, String category,
               List<String> suggestions, boolean showOverlay, String highlightTarget, String emotion, List<Match> matches) {
            this.isHarmful = isHarmful;
            this.riskLevel = riskLevel;
            this.level = level;
            this.detectedWords = detectedWords;
            this.category = category;
            this.suggestions = suggestions;
            this.showOverlay = showOverlay;
            this.highlightTarget = highlightTarget;
            this.emotion = emotion;
            this.matches = matches;
        }

        /** 요구 규격 JSON 문자열 */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"is_harmful\":").append(isHarmful)
                    .append(",\"risk_level\":").append(q(riskLevel))
                    .append(",\"detected_words\":").append(arr(detectedWords))
                    .append(",\"category\":").append(category == null ? "null" : q(category))
                    .append(",\"suggestions\":").append(arr(suggestions))
                    .append(",\"pc_ui_action\":{\"show_overlay\":").append(showOverlay)
                    .append(",\"highlight_target\":").append(highlightTarget == null ? "null" : q(highlightTarget))
                    .append("}}");
            return sb.toString();
        }

        private static String arr(List<String> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) sb.append(i > 0 ? "," : "").append(q(l.get(i)));
            return sb.append("]").toString();
        }

        private static String q(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
    }

    // ---------------- 사전 ----------------
    private static final class Node {
        final HashMap<Character, Node> next = new HashMap<>();
        int value = -1;
    }

    private static final class Ch {
        final char c;
        final int a, b;

        Ch(char c, int a, int b) {
            this.c = c;
            this.a = a;
            this.b = b;
        }
    }

    private static final class Hit {
        final int i, j, v;

        Hit(int i, int j, int v) {
            this.i = i;
            this.j = j;
            this.v = v;
        }
    }

    private static final class Trie {
        final Node root = new Node();

        void add(String key, int value) {
            Node n = root;
            for (int i = 0; i < key.length(); i++) {
                Node m = n.next.get(key.charAt(i));
                if (m == null) {
                    m = new Node();
                    n.next.put(key.charAt(i), m);
                }
                n = m;
            }
            n.value = value;
        }

        List<Hit> scan(List<Ch> chars) {
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < chars.size(); i++) {
                Node n = root;
                int best = -1, val = -1;
                for (int j = i; j < chars.size(); j++) {
                    n = n.next.get(chars.get(j).c);
                    if (n == null) break;
                    if (n.value >= 0) {
                        best = j + 1;
                        val = n.value;
                    }
                }
                if (best > 0) hits.add(new Hit(i, best, val));
            }
            return hits;
        }
    }

    // ---------------- 데이터 ----------------
    private final List<Object> entries;
    private final List<Object> categories, types, descriptions;
    private final Trie shortTrie = new Trie(), longTrie = new Trie(), allowTrie = new Trie();
    private final List<Pattern> variantPatterns = new ArrayList<>();
    private final List<String> variantReplacements = new ArrayList<>();
    public final int entryCount, excelCount;

    @SuppressWarnings("unchecked")
    public Engine(String wordsJson) {
        Map<String, Object> data = (Map<String, Object>) new Json(wordsJson).value();
        entries = (List<Object>) data.get("entries");
        categories = (List<Object>) data.get("categories");
        types = (List<Object>) data.get("types");
        descriptions = (List<Object>) data.get("descriptions");
        for (int i = 0; i < entries.size(); i++) {
            String key = (String) ((List<Object>) entries.get(i)).get(1);
            if (key.length() <= 2) shortTrie.add(key, i);
            else longTrie.add(key, i);
        }
        List<Object> allow = (List<Object>) data.get("allow");
        for (int i = 0; i < allow.size(); i++) allowTrie.add((String) allow.get(i), i);
        for (Object o : (List<Object>) data.get("variants")) {
            List<Object> v = (List<Object>) o;
            variantPatterns.add(Pattern.compile((String) v.get(0)));
            variantReplacements.add((String) v.get(1));
        }
        entryCount = entries.size();
        Object ec = data.get("excel_count");
        excelCount = ec instanceof Number ? ((Number) ec).intValue() : 0;
    }

    private static boolean isSyl(char c) { return c >= 0xAC00 && c <= 0xD7A3; }
    private static boolean isJamo(char c) { return c >= 0x3131 && c <= 0x318E; }
    private static boolean isHangul(char c) { return isSyl(c) || isJamo(c); }
    private static boolean isLatin(char c) { return c >= 'a' && c <= 'z'; }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '　';
    }
    private static boolean isTrim(char c) { return isSpace(c) || Character.isWhitespace(c) || TRIM.indexOf(c) >= 0; }

    // ---------------- 정규화 ----------------
    private List<Ch> normalize(String text, List<List<Ch>> runs) {
        List<Ch> chars = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isSpace(ch)) {
                chars.add(new Ch(' ', i, i + 1));
                continue;
            }
            String lcs = String.valueOf(ch).toLowerCase(java.util.Locale.ROOT);
            if (lcs.length() != 1) continue;
            char lc = lcs.charAt(0);
            if (isHangul(lc) || isLatin(lc) || isDigit(lc)) chars.add(new Ch(lc, i, i + 1));
        }
        List<Ch> cleaned = new ArrayList<>();
        for (int j = 0; j < chars.size(); j++) {
            Ch cur = chars.get(j);
            if (isDigit(cur.c) || isLatin(cur.c)) {
                int k = j;
                while (k < chars.size() && (isDigit(chars.get(k).c) || isLatin(chars.get(k).c))) k++;
                Ch prev = cleaned.isEmpty() ? null : cleaned.get(cleaned.size() - 1);
                Ch next = k < chars.size() ? chars.get(k) : null;
                boolean between = prev != null && next != null && isHangul(prev.c) && isHangul(next.c);
                boolean allDigits = true;
                for (int r = j; r < k; r++) if (!isDigit(chars.get(r).c)) allDigits = false;
                if (!(between && (allDigits || k - j == 1))) for (int r = j; r < k; r++) cleaned.add(chars.get(r));
                j = k - 1;
                continue;
            }
            cleaned.add(cur);
        }
        List<List<Ch>> tokens = new ArrayList<>();
        List<Ch> tok = new ArrayList<>();
        for (Ch c : cleaned) {
            if (c.c == ' ') {
                if (!tok.isEmpty()) {
                    tokens.add(tok);
                    tok = new ArrayList<>();
                }
            } else tok.add(c);
        }
        if (!tok.isEmpty()) tokens.add(tok);
        List<Ch> spaced = new ArrayList<>();
        for (int u = 0; u < tokens.size(); u++) {
            List<Ch> t = tokens.get(u);
            if (t.size() == 1 && u + 1 < tokens.size() && tokens.get(u + 1).size() == 1
                    && !(u > 0 && tokens.get(u - 1).size() == 1)) {
                List<Ch> run = new ArrayList<>();
                for (int x = u; x < tokens.size() && tokens.get(x).size() == 1; x++) run.add(tokens.get(x).get(0));
                runs.add(run);
            }
            if (u > 0 && !(tokens.get(u - 1).size() == 1 && t.size() == 1)) {
                List<Ch> p = tokens.get(u - 1);
                spaced.add(new Ch(' ', p.get(p.size() - 1).b, t.get(0).a));
            }
            spaced.addAll(t);
        }
        for (int w = 0; w < variantPatterns.size(); w++) {
            spaced = applyVariant(spaced, variantPatterns.get(w), variantReplacements.get(w));
        }
        return spaced;
    }

    private static List<Ch> applyVariant(List<Ch> chars, Pattern p, String rep) {
        StringBuilder sb = new StringBuilder();
        for (Ch c : chars) sb.append(c.c);
        Matcher m = p.matcher(sb);
        List<Ch> out = new ArrayList<>();
        int last = 0;
        boolean any = false;
        while (m.find()) {
            if (m.end() == m.start()) continue;
            any = true;
            for (int k = last; k < m.start(); k++) out.add(chars.get(k));
            int a = chars.get(m.start()).a, b = chars.get(m.end() - 1).b;
            for (int r = 0; r < rep.length(); r++) out.add(new Ch(rep.charAt(r), a, b));
            last = m.end();
        }
        if (!any) return chars;
        for (int z = last; z < chars.size(); z++) out.add(chars.get(z));
        return out;
    }

    // ---------------- 분석 ----------------
    private static final class Info {
        String word, group, category, type, description, sg;
        int level;
    }

    private static final class FoundHit {
        final int a, b;
        final Info info;

        FoundHit(int a, int b, Info info) {
            this.a = a;
            this.b = b;
            this.info = info;
        }
    }

    @SuppressWarnings("unchecked")
    private Info info(int idx) {
        List<Object> e = (List<Object>) entries.get(idx);
        Info in = new Info();
        in.word = (String) e.get(0);
        in.level = ((Number) e.get(2)).intValue();
        in.group = (String) e.get(3);
        in.category = (String) categories.get(((Number) e.get(4)).intValue());
        in.type = (String) types.get(((Number) e.get(5)).intValue());
        int d = ((Number) e.get(6)).intValue();
        in.description = d >= 0 ? (String) descriptions.get(d) : "";
        in.sg = (String) e.get(7);
        return in;
    }

    private static int rank(String g) { return "P".equals(g) ? 3 : "S".equals(g) ? 2 : 1; }

    private static String categoryLabel(Info in) {
        if ("P".equals(in.group)) return "패드립 및 인격 모독";
        if ("S".equals(in.group)) return in.level >= 3 ? "강한 욕설 및 비속어" : in.level == 2 ? "공격적 비속어" : "비속어";
        switch (in.category) {
            case "얼굴/생김새": return "외모 비하 (얼굴/생김새)";
            case "체형/신체조건": return "외모 비하 (체형/신체조건)";
            case "성형/피부/노화": return "외모 비하 (성형/피부/노화)";
            case "젠더/연령대 결합": return "외모·성별 비하";
            case "인터넷/게임 은어": return "외모 비하 은어";
            default: return "외모·체형 비하";
        }
    }

    private static String emotionOf(String text) {
        if (RE_LAUGH.matcher(text).find()) return "joke";
        if (RE_FRUSTRATED.matcher(text).find()) return "frustrated";
        if (RE_ANGRY.matcher(text).find()) return "angry";
        if (RE_SURPRISED.matcher(text).find()) return "surprised";
        return "";
    }

    private static List<String> suggestionsFor(String group, String sg, String emotion) {
        List<String> out = new ArrayList<>();
        String[] set;
        if ("P".equals(group)) set = SUGGEST.get("joke".equals(emotion) ? "P_joke" : "P");
        else if ("S".equals(group)) set = SUGGEST.get("S_" + (emotion.isEmpty() ? "angry" : emotion));
        else if ("angry".equals(emotion)) set = SUGGEST.get("A_angry");
        else if ("frustrated".equals(emotion)) set = SUGGEST.get("A_frustrated");
        else {
            set = SUGGEST.get(sg);
            if (set == null) set = SUGGEST.get("face");
            for (String s : set) out.add("joke".equals(emotion) && !RE_ENDS.matcher(s).find() ? s + "ㅋㅋ" : s);
            return out;
        }
        for (String s : set) out.add(s);
        return out;
    }

    public Result analyze(String input) {
        String text = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFC);
        List<List<Ch>> runs = new ArrayList<>();
        List<Ch> spaced = normalize(text, runs);
        List<Ch> noSpace = new ArrayList<>();
        for (Ch c : spaced) if (c.c != ' ') noSpace.add(c);

        List<FoundHit> hits = new ArrayList<>();
        for (Hit h : shortTrie.scan(spaced)) hits.add(new FoundHit(spaced.get(h.i).a, spaced.get(h.j - 1).b, info(h.v)));
        for (Hit h : longTrie.scan(noSpace)) hits.add(new FoundHit(noSpace.get(h.i).a, noSpace.get(h.j - 1).b, info(h.v)));
        List<int[]> allows = new ArrayList<>();
        for (Hit h : allowTrie.scan(spaced)) allows.add(new int[]{spaced.get(h.i).a, spaced.get(h.j - 1).b});
        List<FoundHit> kept = new ArrayList<>();
        for (FoundHit h : hits) {
            boolean inside = false;
            for (int[] a : allows) if (a[0] <= h.a && h.b <= a[1]) inside = true;
            if (!inside) kept.add(h);
        }
        kept.sort((x, y) -> x.a != y.a ? x.a - y.a : (y.b - y.a) - (x.b - x.a));

        // 겹치는 감지 묶기
        List<int[]> clusterSpan = new ArrayList<>();
        List<List<FoundHit>> clusterHits = new ArrayList<>();
        for (FoundHit h : kept) {
            int n = clusterSpan.size();
            if (n > 0 && h.a < clusterSpan.get(n - 1)[1]) {
                clusterSpan.get(n - 1)[1] = Math.max(clusterSpan.get(n - 1)[1], h.b);
                clusterHits.get(n - 1).add(h);
            } else {
                clusterSpan.add(new int[]{h.a, h.b});
                List<FoundHit> l = new ArrayList<>();
                l.add(h);
                clusterHits.add(l);
            }
        }

        // 어절 단위로 넓히기
        List<int[]> groupSpan = new ArrayList<>();
        List<List<FoundHit>> groupHits = new ArrayList<>();
        for (int c = 0; c < clusterSpan.size(); c++) {
            int s = clusterSpan.get(c)[0], e = clusterSpan.get(c)[1];
            for (List<Ch> run : runs) {
                int first = -1, last = -1;
                for (int i = 0; i < run.size(); i++) {
                    if (run.get(i).a < e && s < run.get(i).b) {
                        if (first < 0) first = i;
                        last = i;
                    }
                }
                if (first < 0) continue;
                while (last + 1 < run.size() && PARTICLES.indexOf(run.get(last + 1).c) >= 0) last++;
                s = Math.min(s, run.get(first).a);
                e = Math.max(e, run.get(last).b);
            }
            while (s > 0 && !isSpace(text.charAt(s - 1))) s--;
            while (e < text.length() && !isSpace(text.charAt(e))) e++;
            while (s < e && isTrim(text.charAt(s))) s++;
            while (e > s && isTrim(text.charAt(e - 1))) e--;
            int n = groupSpan.size();
            if (n > 0 && s < groupSpan.get(n - 1)[1]) {
                groupSpan.get(n - 1)[1] = Math.max(groupSpan.get(n - 1)[1], e);
                groupHits.get(n - 1).addAll(clusterHits.get(c));
            } else {
                groupSpan.add(new int[]{s, e});
                groupHits.add(new ArrayList<>(clusterHits.get(c)));
            }
        }

        List<Match> matches = new ArrayList<>();
        for (int g = 0; g < groupSpan.size(); g++) {
            FoundHit best = null;
            for (FoundHit h : groupHits.get(g)) {
                if (best == null) {
                    best = h;
                    continue;
                }
                Info hi = h.info, bi = best.info;
                if (hi.level > bi.level || (hi.level == bi.level && (rank(hi.group) > rank(bi.group)
                        || (rank(hi.group) == rank(bi.group) && (h.b - h.a) > (best.b - best.a))))) best = h;
            }
            int s = groupSpan.get(g)[0], e = groupSpan.get(g)[1];
            Info in = best.info;
            matches.add(new Match(text.substring(s, e), s, e, in.level, in.group, categoryLabel(in), in.word,
                    in.type, in.description, in.sg));
        }

        if (matches.isEmpty()) {
            return new Result(false, "NONE", -1, new ArrayList<>(), null, new ArrayList<>(), false, null, null, matches);
        }
        Match top = matches.get(0);
        for (Match m : matches) {
            if (m.level > top.level || (m.level == top.level && rank(m.group) > rank(top.group))) top = m;
        }
        String emotion = emotionOf(text);
        String shown = !emotion.isEmpty() ? emotion : ("A".equals(top.group) ? "care" : "angry");
        List<String> words = new ArrayList<>();
        for (Match m : matches) if (!words.contains(m.word)) words.add(m.word);
        return new Result(true, top.riskLevel, top.level, words, top.category,
                suggestionsFor(top.group, top.sg, emotion), top.level >= 1, top.word, EMOTION_LABEL.get(shown), matches);
    }

    // ---------------- 아주 작은 JSON 읽개 (words.json 전용) ----------------
    private static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Map<String, Object> obj() {
            Map<String, Object> m = new HashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') return l;
            }
        }

        private String str() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        private Number num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }
}
