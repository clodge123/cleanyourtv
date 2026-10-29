package com.gathin.tvhome.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Jarvis hero panel: animated arc-reactor face + readout text, drawn in one View.
 * States: idle / listening / thinking / speaking. The face reacts to live mic
 * amplitude while listening, and to a synthetic wave while speaking.
 */
public final class JarvisView extends View {
    public static final String STATE_IDLE = "idle";
    public static final String STATE_LISTENING = "listening";
    public static final String STATE_THINKING = "thinking";
    public static final String STATE_SPEAKING = "speaking";

    private static final int CYAN = Color.parseColor("#7FD1FF");
    private static final int DIM = Color.parseColor("#3E6A85");
    private static final int BG = Color.parseColor("#B3060A14");

    private String mode = "face";
    private String state = STATE_IDLE;
    private String title = "";
    private String body = "";
    public volatile float amplitude = 0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase = 0f;
    private float ampSmooth = 0f;
    private long stateAge = System.currentTimeMillis();
    private long typewriterAt = System.currentTimeMillis();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            phase += 0.045f;
            float target = STATE_SPEAKING.equals(state)
                    ? (float) (0.35 + 0.45 * Math.abs(Math.sin(phase * 3.1)))
                    : Math.max(0f, Math.min(1f, amplitude));
            ampSmooth += (target - ampSmooth) * 0.18f;
            long age = System.currentTimeMillis() - stateAge;
            if ((STATE_SPEAKING.equals(state) && age > 25_000)
                    || (STATE_THINKING.equals(state) && age > 45_000)) {
                setState(STATE_IDLE);
            }
            invalidate();
            handler.postDelayed(this, 33);
        }
    };

    public JarvisView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
        setState(STATE_IDLE);
    }

    public void setContent(String m, String t, String b, String colorHex) {
        mode = m == null ? "face" : m;
        title = t == null ? "" : t;
        body = b == null ? "" : b;
        try { if (colorHex != null) paintColorCache = Color.parseColor(colorHex); } catch (Exception ignored) { }
        typewriterAt = System.currentTimeMillis();
        invalidate();
    }
    private int paintColorCache = CYAN;

    public void setState(String s) {
        if (s != null && !s.equals(state)) {
            state = s;
            stateAge = System.currentTimeMillis();
            invalidate();
        }
    }

    public String getState() { return state; }
    public String getTitle() { return title; }
    public String getBody() { return body; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(tick, 16);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BG);
        canvas.drawRoundRect(new RectF(0, 0, W, H), 24f, 24f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(DIM);
        canvas.drawRoundRect(new RectF(1, 1, W - 1, H - 1), 24f, 24f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0f);

        if ("data".equals(mode)) {
            drawData(canvas, W, H);
        } else {
            // face left-anchored, sized by band height; text fills the rest
            drawFace(canvas, W, H, H * 0.55f, H * 0.5f, H * 0.38f);
            drawSideText(canvas, W, H, H * 1.15f);
        }

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(H * 0.055f);
        paint.setColor(DIM);
        canvas.drawText("JARVIS · " + state.toUpperCase(), W * 0.03f, H * 0.1f, textPaint);
    }

    private void drawSideText(Canvas canvas, float W, float H, float left) {
        if (title.isEmpty() && body.isEmpty()) return;
        float titleSize = Math.max(H * 0.16f, 34f);
        float bodySize = Math.max(H * 0.11f, 26f);
        float lineH = bodySize * 1.25f;
        float top = H * 0.30f;
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(titleSize);
        paint.setColor(Color.WHITE);
        canvas.drawText(title, left, top, textPaint);
        if (!body.isEmpty()) {
            textPaint.setTextSize(bodySize);
            paint.setColor(Color.parseColor("#D6E4F0"));
            int shown = typewriterChars(body);
            wrapText(canvas, body.substring(0, Math.min(shown, body.length())),
                    left, top + titleSize * 0.9f, W - left - H * 0.04f, lineH);
        }
    }

    private int typewriterChars(String s) {
        long age = System.currentTimeMillis() - typewriterAt;
        return (int) Math.min(s.length(), age / 18L);
    }

    private void wrapText(Canvas canvas, String text, float x, float y, float maxW, float lineH) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float cy = y;
        for (String w : words) {
            String trial = line.length() == 0 ? w : line + " " + w;
            if (textPaint.measureText(trial) > maxW && line.length() > 0) {
                canvas.drawText(line.toString(), x, cy, textPaint);
                cy += lineH;
                line = new StringBuilder(w);
            } else {
                line = new StringBuilder(trial);
            }
        }
        if (line.length() > 0) canvas.drawText(line.toString(), x, cy, textPaint);
    }

    private void drawData(Canvas canvas, float W, float H) {
        // progress bar + sparkline + text, face badge small top-right
        drawFace(canvas, W, H, W * 0.86f, H * 0.16f, H * 0.10f);
        float barX = W * 0.05f, barW = W * 0.76f, barY = H * 0.62f, barH = H * 0.10f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#12314A"));
        canvas.drawRoundRect(new RectF(barX, barY, barX + barW, barY + barH), barH / 2f, barH / 2f, paint);
        float frac = Math.max(0f, Math.min(1f, value / 100f));
        paint.setColor(paintColorCache);
        canvas.drawRoundRect(new RectF(barX, barY, barX + barW * frac, barY + barH), barH / 2f, barH / 2f, paint);
        textPaint.setTextSize(Math.max(H * 0.11f, 26f));
        paint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(String.format(java.util.Locale.US, "%.0f%%", value), barX + barW + W * 0.01f, barY + barH * 0.9f, textPaint);
        drawSideText(canvas, W, H, W * 0.05f);
    }
    public volatile double value = 0;

    private void drawFace(Canvas canvas, float W, float H, float cx, float cy, float r) {
        float amp = ampSmooth;
        // outer glow ring
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.04f + amp * r * 0.10f);
        paint.setColor(blend(paintColorCache, CYAN, amp));
        canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                -90 + phase * 20, 200 + amp * 120, false, paint);
        // inner arcs (reactor blades)
        for (int i = 1; i <= 3; i++) {
            paint.setStrokeWidth(r * 0.03f);
            paint.setColor(blend(CYAN, Color.WHITE, i * 0.15f));
            float rr = r * (1f - i * 0.18f);
            float start = -90 - phase * (30 + i * 14);
            canvas.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr),
                    start, 120 + amp * 100, false, paint);
        }
        // core
        paint.setStyle(Paint.Style.FILL);
        float coreR = r * (0.42f + amp * 0.10f);
        canvas.drawCircle(cx, cy, coreR, paintWith(paintColorCache, 220));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.02f);
        paint.setColor(Color.parseColor("#BFE7FF"));
        canvas.drawCircle(cx, cy, coreR * 1.15f, paint);

        // state rings
        if (STATE_LISTENING.equals(state)) {
            paint.setStrokeWidth(3f);
            paint.setColor(CYAN);
            float rr = r * (1.25f + amp * 0.35f);
            canvas.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr),
                    phase * 90, 100, false, paint);
            canvas.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr),
                    phase * 90 + 180, 100, false, paint);
        } else if (STATE_THINKING.equals(state)) {
            paint.setStrokeWidth(4f);
            paint.setColor(Color.parseColor("#FFC866"));
            float rr = r * 1.3f;
            canvas.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr),
                    phase * 200, 70, false, paint);
        } else if (STATE_SPEAKING.equals(state)) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(paintColorCache, Color.WHITE, amp));
            int bars = 7;
            for (int i = 0; i < bars; i++) {
                float bh = r * (0.10f + amp * 0.5f * Math.abs(Math.sin(phase * 3 + i * 0.9f)));
                float bx = cx - r + (2 * r / (bars - 1)) * i;
                canvas.drawRect(bx - r * 0.03f, cy + r * 1.25f - bh,
                        bx + r * 0.03f, cy + r * 1.25f + bh, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(0f);
    }

    private Paint paintWith(int color, int alpha) {
        paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
        return paint;
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb(
                (int) (Color.red(a) * (1 - t) + Color.red(b) * t),
                (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
}
