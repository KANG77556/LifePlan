package kr.lifeplan.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Draws the same 0-24 hour layout as the planner, suitable for RemoteViews. */
public final class LifePlanDialRenderer {
    // Bitmap payload stays below Android's per-transaction Binder limit.
    public static final int SIZE = 360;
    private static final int INK = Color.rgb(45, 62, 100);
    private static final int BLUE = Color.rgb(86, 115, 236);
    private static final int RED = Color.rgb(222, 72, 102);

    private LifePlanDialRenderer() { }

    public static int minute(String value) {
        if (value == null || !value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) return -1;
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }

    public static int duration(int from, int to) {
        if (from < 0 || to < 0 || from == to) return 0;
        return to > from ? to - from : 1440 - from + to;
    }

    private static int activityColor(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return BLUE;
        try { return Color.parseColor(value); } catch (IllegalArgumentException error) { return BLUE; }
    }

    private static Paint paint(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.BUTT);
        return p;
    }

    private static void text(Canvas canvas, String value, float x, float y,
                             int color, float size, boolean bold) {
        Paint p = paint(color, 0);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        canvas.drawText(value, x, y, p);
    }

    /** Native bitmap: blue pastel circular timetable with real task colors and a live time hand. */
    public static Bitmap render(Calendar now, List<JSONObject> todayTasks) {
        Bitmap image = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        float cx = SIZE / 2f, cy = SIZE / 2f;
        float radius = 130f, thickness = 48f;
        RectF ring = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);

        // Soft edge and full-day baseline.
        Paint rim = paint(Color.rgb(220, 229, 255), 6f);
        rim.setStyle(Paint.Style.STROKE);
        rim.setShader(new SweepGradient(cx, cy, new int[]{
            Color.rgb(223, 232, 255), Color.rgb(167, 190, 250),
            Color.rgb(232, 218, 255), Color.rgb(223, 232, 255)}, null));
        canvas.drawCircle(cx, cy, 162f, rim);
        rim.setShader(null);

        Paint arc = paint(Color.rgb(232, 235, 249), thickness);
        arc.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, radius, arc);

        // 00:00 is at the top. One degree = four minutes; midnight-spanning events wrap.
        for (JSONObject task : todayTasks) {
            int start = minute(task.optString("start", ""));
            int end = minute(task.optString("end", ""));
            int span = duration(start, end);
            if (span <= 0) continue;
            arc.setColor(activityColor(task.optString("color", "")));
            canvas.drawArc(ring, -90f + start / 4f, span / 4f, false, arc);
        }

        Paint innerEdge = paint(Color.rgb(245, 247, 254), 2.5f);
        innerEdge.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, radius - thickness / 2f - 2f, innerEdge);

        for (int hour = 0; hour < 24; hour += 3) {
            double radians = Math.toRadians(hour * 15.0 - 90);
            float x = cx + (float) Math.cos(radians) * 86f;
            float y = cy + (float) Math.sin(radians) * 86f + 5f;
            text(canvas, Integer.toString(hour), x, y, Color.rgb(122, 138, 172), 16f, false);
        }

        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        double radians = Math.toRadians(nowMinutes / 4.0 - 90.0);
        float ux = (float) Math.cos(radians), uy = (float) Math.sin(radians);
        Paint hand = paint(RED, 3.6f);
        hand.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx + ux * 34f, cy + uy * 34f,
                cx + ux * 161f, cy + uy * 161f, hand);
        Paint tip = paint(RED, 0);
        canvas.drawCircle(cx + ux * 161f, cy + uy * 161f, 6.7f, tip);

        // Center calendar icon is drawn on top of the needle.
        Paint center = paint(Color.rgb(250, 251, 255), 0);
        canvas.drawCircle(cx, cy, 52f, center);
        Paint icon = paint(BLUE, 0);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(4.5f);
        icon.setStrokeJoin(Paint.Join.ROUND);
        icon.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(new RectF(cx - 20f, cy - 33f, cx + 20f, cy + 6f), 6f, 6f, icon);
        canvas.drawLine(cx - 11f, cy - 36f, cx - 11f, cy - 27f, icon);
        canvas.drawLine(cx + 11f, cy - 36f, cx + 11f, cy - 27f, icon);
        Path check = new Path();
        check.moveTo(cx - 10f, cy - 12f);
        check.lineTo(cx - 3f, cy - 5f);
        check.lineTo(cx + 12f, cy - 21f);
        canvas.drawPath(check, icon);
        text(canvas, "라이프플랜", cx, cy + 29f, INK, 15f, true);
        text(canvas, new SimpleDateFormat("HH:mm", Locale.KOREAN).format(now.getTime()),
                cx, cy + 48f, Color.rgb(121, 139, 183), 12f, false);
        return image;
    }
}
