package org.uzel.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Small, dependency-free vector artwork used by the Uzelio interface. */
public final class PeerArtwork extends View {
    private static final float ART_SIZE = 100f;
    private static final int NAVY = Color.rgb(8, 21, 36);
    private static final int CYAN = Color.rgb(40, 226, 221);
    private static final int AMBER = Color.rgb(255, 201, 120);
    private static final int MUTED = Color.rgb(165, 187, 207);
    private static final int RIM = Color.rgb(54, 84, 108);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF arc = new RectF();
    private final Path path = new Path();
    private final Path gear = new Path();
    private final float density;

    private String kind;
    private int tint;
    private Shader haloShader;
    private Shader globeShader;

    public PeerArtwork(Context context, String kind, int tint) {
        super(context);
        this.density = getResources().getDisplayMetrics().density;
        this.kind = normalizeKind(kind);
        this.tint = tint;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.FILL);
        shade.setStyle(Paint.Style.FILL);
        buildGear();
        updateShaders();
        int dp = isBadge(this.kind) ? 100 : "network".equals(this.kind) ? 80 : 24;
        setMinimumWidth(Math.round(dp * density));
        setMinimumHeight(Math.round(dp * density));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setKind(String kind) {
        String next = normalizeKind(kind);
        if (!next.equals(this.kind)) {
            this.kind = next;
            int dp = isBadge(next) ? 100 : "network".equals(next) ? 80 : 24;
            setMinimumWidth(Math.round(dp * density));
            setMinimumHeight(Math.round(dp * density));
            updateShaders();
            requestLayout();
            invalidate();
        }
    }

    public void setTint(int tint) {
        if (this.tint != tint) {
            this.tint = tint;
            updateShaders();
            invalidate();
        }
    }

    private static String normalizeKind(String value) {
        if (value == null) return "network";
        switch (value) {
            case "home": case "map": case "traffic": case "profile": case "settings":
            case "back": case "chevron": case "network": case "search": case "offline":
            case "loading": case "apps": case "share":
                return value;
            default:
                return "network";
        }
    }

    private static boolean isBadge(String value) {
        return "search".equals(value) || "offline".equals(value) || "loading".equals(value);
    }

    private void updateShaders() {
        haloShader = new RadialGradient(50, 50, 48,
                new int[]{withAlpha(tint, 0x50), withAlpha(tint, 0x1c), withAlpha(tint, 0x00)},
                new float[]{0f, .58f, 1f}, Shader.TileMode.CLAMP);
        globeShader = new RadialGradient(34, 29, 78,
                new int[]{0xa82b465d, 0x9c1b3851, 0xe0081524},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00ffffff) | (alpha << 24);
    }

    private void buildGear() {
        gear.reset();
        for (int i = 0; i < 32; i++) {
            double angle = -Math.PI / 2 + (Math.PI * 2 * i / 32.0);
            int tooth = i % 4;
            float radius = (tooth == 0 || tooth == 1) ? 34f : 27f;
            float x = 50f + (float) Math.cos(angle) * radius;
            float y = 50f + (float) Math.sin(angle) * radius;
            if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
        }
        gear.close();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int base = isBadge(kind) ? 100 : "network".equals(kind) ? 80 : 24;
        int desired = Math.round(base * density);
        int width = resolveSize(Math.max(desired, getSuggestedMinimumWidth()), widthMeasureSpec);
        int height = resolveSize(Math.max(desired, getSuggestedMinimumHeight()), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float side = Math.min(getWidth(), getHeight());
        if (side <= 0) return;
        int save = canvas.save();
        canvas.translate((getWidth() - side) * .5f, (getHeight() - side) * .5f);
        canvas.scale(side / ART_SIZE, side / ART_SIZE);
        if (isBadge(kind)) drawBadge(canvas);
        else if ("network".equals(kind)) drawNetwork(canvas);
        else drawSmallIcon(canvas);
        canvas.restoreToCount(save);
    }

    private void drawBadge(Canvas canvas) {
        oval.set(5, 5, 95, 95);
        fill.setColor(0x301B3851);
        canvas.drawCircle(50, 50, 46, fill);
        stroke.setStrokeWidth(1.7f);
        stroke.setColor(RIM);
        canvas.drawCircle(50, 50, 44.5f, stroke);
        glow.setShader(haloShader);
        canvas.drawCircle(50, 50, 43, glow);
        glow.setShader(null);

        if ("loading".equals(kind)) {
            stroke.setStrokeWidth(4.2f);
            stroke.setColor(withAlpha(tint, 0x28));
            canvas.drawCircle(50, 50, 34, stroke);
            stroke.setColor(tint);
            arc.set(16, 16, 84, 84);
            canvas.drawArc(arc, -45, 270, false, stroke);
            drawMagnifier(canvas, 50, 50, 1f, tint);
        } else if ("offline".equals(kind)) {
            drawOffline(canvas, 50, 50, 1f);
        } else {
            drawMagnifier(canvas, 50, 50, 1f, tint);
        }
    }

    private void drawNetwork(Canvas canvas) {
        // An opaque plate keeps the artwork readable on any translucent card.
        fill.setColor(0xFF0E1E2E);
        fill.setShader(null);
        canvas.drawCircle(50, 50, 49, fill);
        glow.setShader(haloShader);
        canvas.drawCircle(50, 50, 49, glow);
        glow.setShader(null);

        oval.set(13, 13, 87, 87);
        fill.setShader(globeShader);
        canvas.drawCircle(50, 50, 36.5f, fill);
        fill.setShader(null);

        // A translucent navy crescent keeps the globe dark and gives it depth.
        shade.setColor(0x52030d18);
        path.reset();
        path.moveTo(60, 15);
        path.cubicTo(78, 20, 89, 35, 85, 54);
        path.cubicTo(81, 72, 67, 85, 48, 87);
        path.cubicTo(63, 72, 68, 57, 64, 43);
        path.cubicTo(61, 31, 55, 22, 60, 15);
        path.close();
        canvas.drawPath(path, shade);

        stroke.setColor(withAlpha(tint, 0x55));
        stroke.setStrokeWidth(1.15f);
        oval.set(13.5f, 13.5f, 86.5f, 86.5f);
        canvas.drawOval(oval, stroke);
        oval.set(31, 13.5f, 69, 86.5f);
        canvas.drawOval(oval, stroke);
        oval.set(13.5f, 31, 86.5f, 69);
        canvas.drawOval(oval, stroke);
        path.reset();
        path.moveTo(20, 29); path.quadTo(50, 43, 80, 29);
        path.moveTo(20, 71); path.quadTo(50, 57, 80, 71);
        canvas.drawPath(path, stroke);

        // Fine cyan links cross the globe's latitude and longitude lines.
        stroke.setColor(withAlpha(tint, 0xb0));
        stroke.setStrokeWidth(1.7f);
        path.reset();
        path.moveTo(50, 18); path.lineTo(74, 34); path.lineTo(71, 63);
        path.lineTo(50, 81); path.lineTo(28, 64); path.lineTo(26, 36);
        path.close();
        path.moveTo(50, 18); path.lineTo(71, 63);
        path.moveTo(74, 34); path.lineTo(28, 64);
        path.moveTo(26, 36); path.lineTo(71, 63);
        canvas.drawPath(path, stroke);
        drawNode(canvas, 50, 18, 3.2f);
        drawNode(canvas, 74, 34, 3.2f);
        drawNode(canvas, 71, 63, 3.2f);
        drawNode(canvas, 50, 81, 3.2f);
        drawNode(canvas, 28, 64, 3.2f);
        drawNode(canvas, 26, 36, 3.2f);
    }

    private void drawNode(Canvas canvas, float x, float y, float radius) {
        fill.setColor(withAlpha(tint, 0x18));
        canvas.drawCircle(x, y, radius + 6.5f, fill);
        fill.setColor(withAlpha(tint, 0x35));
        canvas.drawCircle(x, y, radius + 3.5f, fill);
        fill.setColor(withAlpha(tint, 0x70));
        canvas.drawCircle(x, y, radius + 1.4f, fill);
        fill.setColor(tint);
        canvas.drawCircle(x, y, radius, fill);
        fill.setColor(0xaaffffff);
        canvas.drawCircle(x - radius * .24f, y - radius * .24f, radius * .34f, fill);
    }

    private void drawSmallIcon(Canvas canvas) {
        stroke.setColor(tint);
        stroke.setStrokeWidth(7f);
        fill.setColor(tint);
        switch (kind) {
            case "home":
                path.reset();
                path.moveTo(17, 47); path.lineTo(50, 20); path.lineTo(83, 47);
                path.moveTo(25, 42); path.lineTo(25, 79); path.quadTo(25, 83, 29, 83);
                path.lineTo(71, 83); path.quadTo(75, 83, 75, 79); path.lineTo(75, 42);
                canvas.drawPath(path, stroke);
                stroke.setStrokeWidth(6f);
                canvas.drawLine(45, 83, 45, 61, stroke);
                canvas.drawLine(55, 83, 55, 61, stroke);
                break;
            case "map":
                path.reset();
                path.moveTo(50, 86);
                path.cubicTo(44, 77, 25, 56, 25, 40);
                path.cubicTo(25, 24, 36, 14, 50, 14);
                path.cubicTo(64, 14, 75, 24, 75, 40);
                path.cubicTo(75, 56, 56, 77, 50, 86);
                path.close();
                canvas.drawPath(path, fill);
                fill.setColor(NAVY);
                canvas.drawCircle(50, 40, 8, fill);
                break;
            case "traffic":
                fill.setColor(tint);
                oval.set(18, 55, 34, 82); canvas.drawRoundRect(oval, 5, 5, fill);
                oval.set(42, 38, 58, 82); canvas.drawRoundRect(oval, 5, 5, fill);
                oval.set(66, 18, 82, 82); canvas.drawRoundRect(oval, 5, 5, fill);
                break;
            case "profile":
                fill.setColor(tint);
                canvas.drawCircle(50, 34, 16, fill);
                oval.set(22, 53, 78, 87);
                canvas.drawRoundRect(oval, 18, 18, fill);
                break;
            case "settings":
                fill.setColor(tint);
                canvas.drawPath(gear, fill);
                fill.setColor(NAVY);
                canvas.drawCircle(50, 50, 12, fill);
                break;
            case "back":
                path.reset(); path.moveTo(56, 22); path.lineTo(28, 50); path.lineTo(56, 78);
                canvas.drawPath(path, stroke);
                canvas.drawLine(30, 50, 78, 50, stroke);
                break;
            case "chevron":
                path.reset(); path.moveTo(37, 24); path.lineTo(63, 50); path.lineTo(37, 76);
                canvas.drawPath(path, stroke);
                break;
            case "apps":
                fill.setColor(tint);
                canvas.drawRoundRect(19, 19, 43, 43, 7, 7, fill);
                canvas.drawRoundRect(57, 19, 81, 43, 7, 7, fill);
                canvas.drawRoundRect(19, 57, 43, 81, 7, 7, fill);
                canvas.drawRoundRect(57, 57, 81, 81, 7, 7, fill);
                break;
            case "share":
                fill.setColor(tint);
                canvas.drawCircle(25, 50, 9, fill);
                canvas.drawCircle(73, 27, 9, fill);
                canvas.drawCircle(73, 73, 9, fill);
                stroke.setStrokeWidth(6f);
                canvas.drawLine(33, 46, 65, 31, stroke);
                canvas.drawLine(33, 54, 65, 69, stroke);
                break;
            case "search":
                drawMagnifier(canvas, 50, 50, .6f, tint);
                break;
            case "offline":
                drawOffline(canvas, 50, 50, .6f);
                break;
            case "loading":
                stroke.setStrokeWidth(6f);
                stroke.setColor(withAlpha(tint, 0x38));
                canvas.drawCircle(50, 50, 32, stroke);
                stroke.setColor(tint);
                arc.set(18, 18, 82, 82);
                canvas.drawArc(arc, -45, 270, false, stroke);
                break;
            default:
                drawNetwork(canvas);
        }
    }

    private void drawMagnifier(Canvas canvas, float cx, float cy, float scale, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(6f);
        canvas.drawCircle(cx - 5 * scale, cy - 5 * scale, 19 * scale, stroke);
        canvas.drawLine(cx + 9 * scale, cy + 9 * scale,
                cx + 28 * scale, cy + 28 * scale, stroke);
    }

    private void drawOffline(Canvas canvas, float cx, float cy, float scale) {
        stroke.setColor(AMBER);
        stroke.setStrokeWidth(5.2f * scale);
        stroke.setStyle(Paint.Style.STROKE);
        arc.set(cx - 31 * scale, cy - 34 * scale, cx + 31 * scale, cy + 28 * scale);
        canvas.drawArc(arc, 225, 90, false, stroke);
        arc.set(cx - 21 * scale, cy - 24 * scale, cx + 21 * scale, cy + 18 * scale);
        canvas.drawArc(arc, 225, 90, false, stroke);
        arc.set(cx - 11 * scale, cy - 14 * scale, cx + 11 * scale, cy + 8 * scale);
        canvas.drawArc(arc, 225, 90, false, stroke);
        fill.setColor(AMBER);
        canvas.drawCircle(cx, cy + 27 * scale, 3.5f * scale, fill);
        path.reset();
        path.moveTo(cx - 25 * scale, cy - 27 * scale);
        path.lineTo(cx + 25 * scale, cy + 22 * scale);
        canvas.drawPath(path, stroke);
    }
}
