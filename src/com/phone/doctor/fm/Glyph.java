package com.phone.doctor.fm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Toolbar icons drawn as vectors, so they show on every Android version (old fonts lack ☰ ⚙). */
final class Glyph extends View {
    static final int MENU = 0, PLUS = 1, GEAR = 2, BACK = 3, MIC = 4;
    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int box;

    Glyph(Context c, int type, int color, int sizePx) {
        super(c);
        this.type = type;
        this.box = sizePx;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(box, box);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = box, cx = s / 2f, cy = s / 2f, u = s / 48f;   // 48-unit grid, glyph ~22 units
        p.setStrokeWidth(2.6f * u);
        p.setStyle(Paint.Style.STROKE);
        switch (type) {
            case MENU:
                for (int i = -1; i <= 1; i++) c.drawLine(cx - 10 * u, cy + i * 7 * u, cx + 10 * u, cy + i * 7 * u, p);
                break;
            case PLUS:
                c.drawLine(cx - 10 * u, cy, cx + 10 * u, cy, p);
                c.drawLine(cx, cy - 10 * u, cx, cy + 10 * u, p);
                break;
            case BACK: {   // arrow pointing right (RTL "back")
                c.drawLine(cx - 10 * u, cy, cx + 10 * u, cy, p);
                Path a = new Path();
                a.moveTo(cx + 2 * u, cy - 8 * u);
                a.lineTo(cx + 10 * u, cy);
                a.lineTo(cx + 2 * u, cy + 8 * u);
                c.drawPath(a, p);
                break;
            }
            case MIC: {   // outline microphone: capsule, holder arc, stem
                p.setStrokeWidth(2.2f * u);
                c.drawRoundRect(new android.graphics.RectF(cx - 4.5f * u, cy - 12 * u, cx + 4.5f * u, cy + 3 * u), 4.5f * u, 4.5f * u, p);
                c.drawArc(new android.graphics.RectF(cx - 8.5f * u, cy - 6 * u, cx + 8.5f * u, cy + 7.5f * u), 0, 180, false, p);
                c.drawLine(cx, cy + 7.5f * u, cx, cy + 12 * u, p);
                break;
            }
            case GEAR: {
                p.setStyle(Paint.Style.FILL);
                Path g = new Path();
                int teeth = 8;
                float ro = 11 * u, ri = 8.2f * u;
                for (int i = 0; i < teeth * 4; i++) {
                    double ang = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2;
                    float r = (i % 4 == 0 || i % 4 == 1) ? ro : ri;
                    float x = cx + (float) Math.cos(ang) * r, y = cy + (float) Math.sin(ang) * r;
                    if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
                }
                g.close();
                g.addCircle(cx, cy, 3.6f * u, Path.Direction.CCW);
                g.setFillType(Path.FillType.EVEN_ODD);
                c.drawPath(g, p);
                break;
            }
        }
    }
}
