package com.hanbeonde.keyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * 두벌식 한글 조합기.
 * 지금 조합 중인 자모 목록을 받아 완성된 글자와 조합 중인 마지막 글자로 나눈다.
 */
public final class Hangul {

    static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    // 0번 자리는 '받침 없음'
    static final String JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

    private static final String[][] V_PAIRS = {
            {"ㅗㅏ", "ㅘ"}, {"ㅗㅐ", "ㅙ"}, {"ㅗㅣ", "ㅚ"},
            {"ㅜㅓ", "ㅝ"}, {"ㅜㅔ", "ㅞ"}, {"ㅜㅣ", "ㅟ"}, {"ㅡㅣ", "ㅢ"}
    };
    private static final String[][] J_PAIRS = {
            {"ㄱㅅ", "ㄳ"}, {"ㄴㅈ", "ㄵ"}, {"ㄴㅎ", "ㄶ"}, {"ㄹㄱ", "ㄺ"}, {"ㄹㅁ", "ㄻ"},
            {"ㄹㅂ", "ㄼ"}, {"ㄹㅅ", "ㄽ"}, {"ㄹㅌ", "ㄾ"}, {"ㄹㅍ", "ㄿ"}, {"ㄹㅎ", "ㅀ"}, {"ㅂㅅ", "ㅄ"}
    };

    private Hangul() {}

    public static boolean isVowel(char c) { return JUNG.indexOf(c) >= 0; }
    public static boolean isConsonant(char c) { return CHO.indexOf(c) >= 0; }
    public static boolean isJamo(char c) { return isVowel(c) || isConsonant(c); }

    private static char pair(String[][] table, char a, char b) {
        String key = "" + a + b;
        for (String[] p : table) if (p[0].equals(key)) return p[1].charAt(0);
        return 0;
    }

    private static char[] splitFinal(char c) {
        for (String[] p : J_PAIRS) if (p[1].charAt(0) == c) return new char[]{p[0].charAt(0), p[0].charAt(1)};
        return null;
    }

    private static final class Block {
        char cho, jung, jong;
        int start;

        Block(int start) { this.start = start; }

        boolean empty() { return cho == 0 && jung == 0; }

        String text() {
            if (cho != 0 && jung != 0) {
                int code = 0xAC00 + (CHO.indexOf(cho) * 21 + JUNG.indexOf(jung)) * 28
                        + (jong == 0 ? 0 : JONG.indexOf(jong));
                return String.valueOf((char) code);
            }
            if (cho != 0) return String.valueOf(cho);
            if (jung != 0) return String.valueOf(jung) + (jong != 0 ? String.valueOf(jong) : "");
            return "";
        }
    }

    public static final class Result {
        /** 확정해서 내보낼 글자들 */
        public final String committed;
        /** 아직 조합 중인 마지막 글자 */
        public final String composing;
        /** 조합 중인 글자가 자모 목록의 몇 번째부터 시작하는지 */
        public final int lastStart;

        Result(String committed, String composing, int lastStart) {
            this.committed = committed;
            this.composing = composing;
            this.lastStart = lastStart;
        }
    }

    public static Result compose(List<Character> jamo) {
        List<Block> blocks = new ArrayList<>();
        Block b = new Block(0);
        for (int i = 0; i < jamo.size(); i++) {
            char j = jamo.get(i);
            if (isConsonant(j)) {
                if (b.empty()) {
                    b.cho = j;
                } else if (b.jung == 0) {
                    // 자음만 있는 글자 다음 자음 → 새 글자
                    blocks.add(b);
                    b = new Block(i);
                    b.cho = j;
                } else if (b.jong == 0) {
                    if (b.cho != 0 && JONG.indexOf(j) > 0) {
                        b.jong = j;
                    } else {
                        blocks.add(b);
                        b = new Block(i);
                        b.cho = j;
                    }
                } else {
                    char c = pair(J_PAIRS, b.jong, j);
                    if (c != 0) {
                        b.jong = c;
                    } else {
                        blocks.add(b);
                        b = new Block(i);
                        b.cho = j;
                    }
                }
            } else { // 모음
                if (b.empty()) {
                    b.jung = j;
                } else if (b.jung == 0) {
                    b.jung = j;
                } else if (b.jong == 0) {
                    char c = b.cho != 0 || b.jung != 0 ? pair(V_PAIRS, b.jung, j) : 0;
                    if (c != 0) {
                        b.jung = c;
                    } else {
                        blocks.add(b);
                        b = new Block(i);
                        b.jung = j;
                    }
                } else {
                    // 받침 뒤에 모음 → 받침(또는 겹받침의 뒤쪽)을 다음 글자 초성으로 옮긴다
                    char[] split = splitFinal(b.jong);
                    char moved;
                    if (split != null) {
                        b.jong = split[0];
                        moved = split[1];
                    } else {
                        moved = b.jong;
                        b.jong = 0;
                    }
                    blocks.add(b);
                    b = new Block(i - 1);
                    b.cho = moved;
                    b.jung = j;
                }
            }
        }
        StringBuilder done = new StringBuilder();
        for (Block x : blocks) done.append(x.text());
        return new Result(done.toString(), b.text(), b.start);
    }
}
