package sh.molly.phigrosplusplus.installer;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** a parallelogram filling its bounds, leaning like phigros.tools' buttons and tabs */
final class Slant extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float lean;

    Slant(int color, float lean) {
        paint.setColor(color);
        this.lean = lean;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        Path path = new Path();
        path.moveTo(b.left + lean, b.top);
        path.lineTo(b.right, b.top);
        path.lineTo(b.right - lean, b.bottom);
        path.lineTo(b.left, b.bottom);
        path.close();
        canvas.drawPath(path, paint);
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
