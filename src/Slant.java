package sh.molly.phigrosplusplus.installer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * a parallelogram filling its bounds, leaning like phigros.tools' buttons and tabs whatever its height, with a
 * hairline when it's glass
 */
final class Slant extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    // the tangent of the one angle everything leans at, 14 degrees
    static final float LEAN = 0.25f;

    Slant(int color) {
        this(color, 0);
    }

    Slant(int color, int outline) {
        paint.setColor(color);
        edge.setColor(outline);
        edge.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float lean = b.height() * LEAN;
        Path path = new Path();
        path.moveTo(b.left + lean, b.top);
        path.lineTo(b.right, b.top);
        path.lineTo(b.right - lean, b.bottom);
        path.lineTo(b.left, b.bottom);
        path.close();
        canvas.drawPath(path, paint);
        if (edge.getColor() != 0) canvas.drawPath(path, edge);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
