package org.uzel.app.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small vector glyphs drawn with Canvas: the system font has no emoji for these controls. */
final class ChatIcons {
    static final int PHONE = 0, VIDEO = 1, ATTACH = 2, MIC = 3, SEND = 4;

    private ChatIcons() {}

    static View view(Context context, int kind, int color) {
        return new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
                setColor(color);
                setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 1.7f));
                setStrokeCap(Paint.Cap.ROUND);
                setStrokeJoin(Paint.Join.ROUND);
                setStyle(Paint.Style.STROKE);
            }};

            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), s = Math.min(w, h);
                float cx = w / 2f, cy = h / 2f;
                switch (kind) {
                    case SEND -> {
                        paint.setStyle(Paint.Style.FILL);
                        Path arrow = new Path();
                        arrow.moveTo(cx - s * 0.22f, cy - s * 0.22f);
                        arrow.lineTo(cx + s * 0.26f, cy);
                        arrow.lineTo(cx - s * 0.22f, cy + s * 0.22f);
                        arrow.close();
                        canvas.drawPath(arrow, paint);
                    }
                    case MIC -> {
                        paint.setStyle(Paint.Style.STROKE);
                        RectF capsule = new RectF(cx - s * 0.13f, cy - s * 0.30f, cx + s * 0.13f, cy + s * 0.06f);
                        canvas.drawRoundRect(capsule, s * 0.13f, s * 0.13f, paint);
                        RectF arc = new RectF(cx - s * 0.22f, cy - s * 0.10f, cx + s * 0.22f, cy + s * 0.20f);
                        canvas.drawArc(arc, 0, 180, false, paint);
                        canvas.drawLine(cx, cy + s * 0.20f, cx, cy + s * 0.30f, paint);
                    }
                    case ATTACH -> {
                        paint.setStyle(Paint.Style.STROKE);
                        canvas.save();
                        canvas.rotate(-40, cx, cy);
                        RectF outer = new RectF(cx - s * 0.20f, cy - s * 0.26f, cx + s * 0.20f, cy + s * 0.30f);
                        canvas.drawRoundRect(outer, s * 0.20f, s * 0.20f, paint);
                        RectF inner = new RectF(cx - s * 0.10f, cy - s * 0.12f, cx + s * 0.10f, cy + s * 0.18f);
                        canvas.drawRoundRect(inner, s * 0.10f, s * 0.10f, paint);
                        canvas.restore();
                    }
                    case PHONE -> {
                        paint.setStyle(Paint.Style.STROKE);
                        canvas.save();
                        canvas.rotate(-35, cx, cy);
                        RectF body = new RectF(cx - s * 0.09f, cy - s * 0.22f, cx + s * 0.09f, cy + s * 0.14f);
                        canvas.drawRoundRect(body, s * 0.10f, s * 0.10f, paint);
                        canvas.restore();
                    }
                    case VIDEO -> {
                        paint.setStyle(Paint.Style.STROKE);
                        RectF body = new RectF(cx - s * 0.26f, cy - s * 0.15f, cx + s * 0.06f, cy + s * 0.15f);
                        canvas.drawRoundRect(body, s * 0.07f, s * 0.07f, paint);
                        Path lens = new Path();
                        lens.moveTo(cx + s * 0.08f, cy - s * 0.06f);
                        lens.lineTo(cx + s * 0.26f, cy - s * 0.16f);
                        lens.lineTo(cx + s * 0.26f, cy + s * 0.16f);
                        lens.lineTo(cx + s * 0.08f, cy + s * 0.06f);
                        lens.close();
                        canvas.drawPath(lens, paint);
                    }
                    default -> { }
                }
            }
        };
    }
}
