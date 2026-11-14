package sh.molly.phigrosplusplus.installer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

/** the backdrop of phigros.tools and of the game's menus: nine blurred colours, and a few lights drifting over them */
final class Fog extends View {
    // the title screen's colours, a 3x3 grid read row by row
    private static final int[] COLOURS = {0xFF3D4450, 0xFF2A2433, 0xFF2C5356, 0xFF4A3A3C, 0xFF55303A, 0xFF3A2C40,
        0xFF35505A, 0xFF4A1C4C, 0xFF3F5A58};
    // each colour's glow, back to front: its index, centre and radii as fractions of the screen
    private static final float[][] SPOTS = {{8, 1, 1, .55f, .6f}, {6, 0, 1, .55f, .6f}, {2, 1, 0, .55f, .6f},
        {0, 0, 0, .55f, .6f}, {5, 1.04f, .5f, .45f, .55f}, {3, -.04f, .5f, .45f, .55f}, {7, .5f, 1.06f, .5f, .5f},
        {1, .5f, -.06f, .5f, .5f}, {4, .5f, .45f, .55f, .5f}};
    // the lights: where, as fractions of the screen, and their radius in dp
    private static final float[][] LIGHTS = {{.07f, .74f, 30}, {.31f, .9f, 18}, {.86f, .11f, 15}, {.95f, .46f, 27},
        {.51f, .03f, 13}, {.12f, .18f, 23}, {.64f, .62f, 11}};
    // a light is a soft disc inside a fading halo half as wide again
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    private final ValueAnimator drift = ValueAnimator.ofFloat(0, 1).setDuration(24000);
    private Bitmap fog;

    Fog(Context context) {
        super(context);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.addUpdateListener(a -> invalidate());
        light.setShader(new RadialGradient(0, 0, 1.5f, new int[] {0x3DFFFFFF, 0x3DFFFFFF, 0x16FFFFFF, 0},
            new float[] {0, .63f, .69f, 1}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        drift.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        drift.cancel();
    }

    // the colours are painted once, small: stretched over the screen they blur by themselves
    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        bounds.set(0, 0, w, h);
        fog = Bitmap.createBitmap(192, Math.max(1, 192 * h / Math.max(1, w)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(fog);
        int middle = COLOURS[4];
        canvas.drawRGB((middle >> 16 & 255) * 72 / 100, (middle >> 8 & 255) * 72 / 100, (middle & 255) * 72 / 100);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (float[] spot : SPOTS) {
            int colour = COLOURS[(int) spot[0]];
            glow.setShader(new RadialGradient(0, 0, 1, new int[] {colour, colour & 0xFFFFFF}, new float[] {0, .72f},
                Shader.TileMode.CLAMP));
            canvas.save();
            canvas.translate(spot[1] * fog.getWidth(), spot[2] * fog.getHeight());
            canvas.scale(spot[3] * fog.getWidth(), spot[4] * fog.getHeight());
            canvas.drawCircle(0, 0, 1, glow);
            canvas.restore();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawBitmap(fog, null, bounds, paint);
        float dp = getResources().getDisplayMetrics().density, moved = (float) drift.getAnimatedValue();
        for (int i = 0; i < LIGHTS.length; i++) {
            // each light is somewhere else along the same path
            float at = Math.abs((moved + i * .37f) % 2 - 1);
            canvas.save();
            canvas.translate(LIGHTS[i][0] * getWidth() + at * 40 * dp, LIGHTS[i][1] * getHeight() - at * 60 * dp);
            canvas.scale(LIGHTS[i][2] * dp, LIGHTS[i][2] * dp);
            canvas.drawCircle(0, 0, 1.5f, light);
            canvas.restore();
        }
    }
}
