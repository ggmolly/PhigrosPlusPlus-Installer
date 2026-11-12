package sh.molly.phigrosplusplus.installer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** the installer's ++ tab drawn in the corner of the game's icon, like mod/scripts/patch_apk.py's badge() */
final class Badge {
    // the tab in its own units: 60 wide, 40 high, leaning 12, with a + centred at (23, 20) and (42, 20)
    private static final float WIDTH = 60, HEIGHT = 40, LEAN = 12, ARM = 8, BAR = 2.5f;
    private static final float[] PLUSES = {23, 42};

    static boolean icon(String name) {
        return name.matches("res/mipmap-[^/]+/app_icon(_round)?\\.png");
    }

    static byte[] png(byte[] data) throws IOException {
        Bitmap icon = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (icon == null) throw new IOException("can't read the game's icon");
        Bitmap out = icon.copy(Bitmap.Config.ARGB_8888, true);
        draw(new Canvas(out), out.getWidth());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, png);
        return png.toByteArray();
    }

    // about half the icon wide, 3% in from the bottom right corner, over a shadow offset by 2%
    private static void draw(Canvas canvas, float size) {
        float k = 0.52f * size / WIDTH, x = 0.97f * size - WIDTH * k, y = 0.97f * size - HEIGHT * k, shadow = 0.02f * size;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x80000000);
        canvas.drawPath(tab(x + shadow, y + shadow, k), paint);
        paint.setColor(0xFFF4F2F6);
        canvas.drawPath(tab(x, y, k), paint);
        paint.setColor(0xFF0D0A0E);
        for (float cx : PLUSES) {
            float px = x + cx * k, py = y + HEIGHT / 2 * k;
            canvas.drawRect(new RectF(px - ARM * k, py - BAR * k, px + ARM * k, py + BAR * k), paint);
            canvas.drawRect(new RectF(px - BAR * k, py - ARM * k, px + BAR * k, py + ARM * k), paint);
        }
    }

    private static Path tab(float x, float y, float k) {
        Path path = new Path();
        path.moveTo(x + LEAN * k, y);
        path.lineTo(x + WIDTH * k, y);
        path.lineTo(x + (WIDTH - LEAN) * k, y + HEIGHT * k);
        path.lineTo(x, y + HEIGHT * k);
        path.close();
        return path;
    }
}
