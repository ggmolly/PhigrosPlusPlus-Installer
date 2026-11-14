package sh.molly.phigrosplusplus.installer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** a leaning progress bar: it glides to each new value, and a band sweeps across it while waiting on android */
final class Bar extends View {
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), front = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shown, band;
    private ValueAnimator glide, sweep;

    Bar(Context context, int backColor, int frontColor) {
        super(context);
        back.setColor(backColor);
        front.setColor(frontColor);
    }

    void to(float fraction) {
        if (glide != null) glide.cancel();
        glide = ValueAnimator.ofFloat(shown, fraction).setDuration(250);
        glide.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        glide.start();
    }

    void sweeping(boolean on) {
        if (!on && sweep != null) sweep.cancel();
        if (!on) sweep = null;
        // drawn once more without the band, or its last frame would stay
        if (!on) invalidate();
        if (!on || sweep != null) return;
        sweep = ValueAnimator.ofFloat(0, 1).setDuration(1200);
        sweep.setRepeatCount(ValueAnimator.INFINITE);
        sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.addUpdateListener(a -> {
            band = (float) a.getAnimatedValue();
            invalidate();
        });
        sweep.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (glide != null) glide.cancel();
        sweeping(false);
    }

    // the fill starts a lean before the left edge so it meets the track's slanted start, the track clips both
    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), lean = getHeight();
        Path track = slant(0, w);
        canvas.drawPath(track, back);
        canvas.save();
        canvas.clipPath(track);
        front.setAlpha(sweep == null ? 255 : 90);
        canvas.drawPath(slant(-lean, shown * (w + lean)), front);
        if (sweep != null) {
            float width = 0.3f * w, x = -width + band * (w + width);
            front.setAlpha(255);
            canvas.drawPath(slant(x, x + width), front);
        }
        canvas.restore();
    }

    private Path slant(float left, float right) {
        float h = getHeight();
        Path path = new Path();
        path.moveTo(left + h, 0);
        path.lineTo(right, 0);
        path.lineTo(right - h, h);
        path.lineTo(left, h);
        path.close();
        return path;
    }
}
