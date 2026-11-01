package com.hanbeonde.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

import java.util.Calendar;

/** 최근 7일 요일별 '감지'와 '바꿔서 보냄' 막대그래프 */
public class ChartView extends View {

    static final int SERIES_DETECT = Color.parseColor("#EB6834");
    static final int SERIES_CHANGE = Color.parseColor("#2A78D6");
    private static final String[] DOW = {"일", "월", "화", "수", "목", "금", "토"};

    private int[] detect = new int[7];
    private int[] change = new int[7];
    private long[] days = new long[7];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ChartView(Context c) {
        super(c);
        text.setTextSize(sp(11));
        text.setColor(Color.parseColor("#7C8784"));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    public void setData(int[] detect, int[] change, long[] days) {
        this.detect = detect;
        this.change = change;
        this.days = days;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) dp(170), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = dp(22), bottom = getHeight() - dp(24), top = dp(8), right = getWidth();
        int max = 2;
        for (int v : detect) max = Math.max(max, v);
        for (int v : change) max = Math.max(max, v);
        if (max % 2 == 1) max++;
        float h = bottom - top;

        paint.setColor(Color.parseColor("#E3E8E6"));
        paint.setStrokeWidth(dp(1));
        text.setTextAlign(Paint.Align.RIGHT);
        int[] ticks = {0, max / 2, max};
        for (int t : ticks) {
            float y = bottom - h * t / max;
            c.drawLine(left, y, right, y, paint);
            c.drawText(String.valueOf(t), left - dp(6), y + dp(4), text);
        }

        float gw = (right - left) / 7f, bw = dp(10), gap = dp(2), r = dp(4);
        text.setTextAlign(Paint.Align.CENTER);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            float cx = left + gw * i + gw / 2;
            bar(c, cx - bw - gap / 2, bw, detect[i], max, bottom, h, r, SERIES_DETECT);
            bar(c, cx + gap / 2, bw, change[i], max, bottom, h, r, SERIES_CHANGE);
            cal.setTimeInMillis(days[i]);
            String label = i == 6 ? "오늘" : DOW[cal.get(Calendar.DAY_OF_WEEK) - 1];
            text.setFakeBoldText(i == 6);
            text.setColor(i == 6 ? Color.parseColor("#16201E") : Color.parseColor("#7C8784"));
            c.drawText(label, cx, getHeight() - dp(6), text);
        }
        text.setFakeBoldText(false);
        text.setColor(Color.parseColor("#7C8784"));
    }

    private void bar(Canvas c, float x, float w, int v, int max, float bottom, float h, float r, int color) {
        if (v <= 0) return;
        float top = bottom - h * v / max;
        paint.setColor(color);
        float rr = Math.min(r, bottom - top);
        c.drawRoundRect(new RectF(x, top, x + w, bottom), rr, rr, paint);
        c.drawRect(x, Math.max(top, bottom - rr), x + w, bottom, paint); // 아래쪽은 각지게
    }
}
