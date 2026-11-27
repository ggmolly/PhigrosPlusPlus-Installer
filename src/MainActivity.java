package sh.molly.phigrosplusplus.installer;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.util.Log;
import android.util.SparseArray;
import android.view.ViewPropertyAnimator;
import android.view.animation.PathInterpolator;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String GAME = "com.PigeonGames.Phigros", PACKAGE = "sh.molly.phigrosplusplus", TAG = "phigros++";
    // phigros.tools' colours: glass panels and their hairline, text, secondary and faint text, what went well, and
    // the game state's
    private static final int PANEL = 0x800A050C, HAIRLINE = 0x1FFFFFFF, INK = 0xFFFFFFFF, MUTED = 0xA8FFFFFF,
        FAINT = 0x66FFFFFF, GLOW = 0xFF8FDCFF, WARN = 0xFFF2C65A, NEGATIVE = 0xFFFF9D94;
    // how far a glow reaches around its text, in dp: a text view cuts what's drawn outside its padding
    private static final int GLOW_ROOM = 20;
    // android cuts a text's glow into a box past this many pixels
    private static final int GLOW_MAX = 25;
    // the ease of every entrance, fast then settling
    private static final TimeInterpolator EASE = new PathInterpolator(0.16f, 1, 0.3f, 1);
    private static final String[][] LINKS = {
        {"phigros.tools", "https://phigros.tools"},
        {"Phigros++ on GitHub", "https://github.com/ggmolly/PhigrosPlusPlus"},
        {"phigros.tools on GitHub", "https://github.com/ggmolly/phigros-tools"},
    };
    private TextView game, state, status, percent, log, hint, newer;
    private LinearLayout column, body, main, details;
    private final SparseArray<Typeface> weights = new SparseArray<>();
    private Bar bar;
    private ScrollView scroll;
    private final long started = SystemClock.elapsedRealtime();
    private Button install;
    private boolean busy, installed;
    private volatile Release release; // null while offline or before github answers: the built-in patch is used
    private volatile int permille = -1;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // the fog runs under the system's bars, the content keeps clear of them
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // the wordmark glows like the game's logo, the ++ in Phigros++' pink. the padding leaves room for the glow
        LinearLayout brand = new LinearLayout(this);
        brand.setBaselineAligned(false);
        TextView name = text(46, INK, 500);
        name.setText("Phigros");
        name.setShadowLayer(Math.min(dp(9), GLOW_MAX), 0, 0, 0xFF5CC4FF);
        name.setPadding(dp(GLOW_ROOM), dp(GLOW_ROOM), dp(GLOW_ROOM), dp(GLOW_ROOM));
        TextView plus = text(24, 0xFFFFE3F2, 700);
        plus.setText("++");
        plus.setShadowLayer(Math.min(dp(7), GLOW_MAX), 0, 0, 0xFFFF4FA8);
        plus.setPadding(dp(GLOW_ROOM), dp(GLOW_ROOM + 4), dp(GLOW_ROOM), dp(GLOW_ROOM));
        brand.addView(name);
        LinearLayout.LayoutParams raised = new LinearLayout.LayoutParams(-2, -2);
        raised.leftMargin = -dp(2 * GLOW_ROOM - 3);
        brand.addView(plus, raised);
        TextView subtitle = text(15, MUTED, 400);
        subtitle.setText("Installer " + version(getPackageName()));
        // a newer installer, found by check(): the line opens its release page
        newer = text(15, WARN, 400);
        newer.setVisibility(View.GONE);
        newer.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Release.INSTALLER))));

        LinearLayout card = column();
        card.setBackground(new Slant(PANEL, HAIRLINE));
        card.setPadding(dp(34), dp(16), dp(34), dp(18));
        game = text(22, INK, 400);
        state = text(15, MUTED, 400);
        card.addView(game);
        card.addView(state);

        install = new Button(this);
        install.setAllCaps(false);
        install.setTextSize(18);
        install.setTextColor(0xFF000000);
        install.setTypeface(saira(500));
        install.setStateListAnimator(null);
        install.setBackground(new Slant(INK));
        install.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33000000), null, new Slant(INK)));
        install.setOnClickListener(v -> {
            if (installed) startActivity(getPackageManager().getLaunchIntentForPackage(PACKAGE));
            else install();
        });
        LinearLayout.LayoutParams button = spaced(18);
        button.height = dp(58);

        // what's happening, with how far the install is at its end, over the bar
        LinearLayout progress = new LinearLayout(this);
        progress.setGravity(Gravity.BOTTOM);
        status = text(15, INK, 400);
        percent = text(30, INK, 300);
        progress.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        progress.addView(percent);
        bar = new Bar(this, 0x24FFFFFF, INK);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams track = spaced(8);
        track.height = dp(8);

        main = column();
        LinearLayout.LayoutParams pulled = spaced(-GLOW_ROOM);
        pulled.leftMargin = -dp(GLOW_ROOM);
        main.addView(brand, pulled);
        main.addView(subtitle, spaced(-GLOW_ROOM + 2));
        main.addView(newer, spaced(6));
        main.addView(card, spaced(26));
        main.addView(install, button);
        main.addView(progress, spaced(18));
        main.addView(bar, track);

        TextView heading = text(13, MUTED, 500);
        heading.setText("Details");
        heading.setLetterSpacing(0.08f);
        log = text(11, MUTED, 400);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(PANEL);
        glass.setStroke(1, HAIRLINE);
        scroll = new ScrollView(this);
        scroll.setBackground(glass);
        scroll.setPadding(dp(14), dp(12), dp(14), dp(12));
        scroll.addView(log);
        details = column();
        details.addView(heading);
        details.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ((LinearLayout.LayoutParams) scroll.getLayoutParams()).topMargin = dp(8);

        TextView notice = text(13, FAINT, 400);
        notice.setText("Phigros++ is an unofficial mod, not affiliated with or endorsed by Pigeon Games.");
        TextView links = text(15, MUTED, 400);
        SpannableStringBuilder line = new SpannableStringBuilder();
        for (String[] link : LINKS) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(link[0], new URLSpan(link[1]), 0);
        }
        links.setText(line);
        links.setLinkTextColor(INK);
        links.setMovementMethod(LinkMovementMethod.getInstance());

        // the installer beside its details on a wide screen, over them on a narrow one (see fit())
        body = new LinearLayout(this);
        body.addView(main);
        body.addView(details);
        column = column();
        column.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        column.addView(notice, spaced(16));
        column.addView(links, spaced(6));

        FrameLayout content = new FrameLayout(this);
        content.setFitsSystemWindows(true);
        hint = text(15, MUTED, 400);
        hint.setGravity(Gravity.CENTER);
        hint.setAlpha(0);
        content.addView(hint, new FrameLayout.LayoutParams(-1, dp(PULL), Gravity.TOP));
        content.addView(column);
        FrameLayout root = new FrameLayout(this);
        root.addView(new Fog(this));
        root.addView(content);
        setContentView(root);
        fit();
        // everything comes up in turn. the button keeps its own alpha, which says whether it can be tapped
        View[] entering = {brand, subtitle, card, install, progress, details, notice, links};
        for (int i = 0; i < entering.length; i++) {
            View view = entering[i];
            view.setTranslationY(dp(22));
            if (view != install) view.setAlpha(0);
            ViewPropertyAnimator rise = view.animate().translationY(0).setStartDelay(80 + i * 70).setDuration(700)
                .setInterpolator(EASE);
            if (view != install) rise.alpha(1);
        }
        environment();
        check(false);
    }

    // asks github for the latest release, at launch and when the screen is pulled down (pulled says so on screen)
    private void check(boolean pulled) {
        new Thread(() -> {
            String result;
            try {
                release = Release.fetch(key());
                result = "Latest Phigros++: " + release.version;
                log("release " + release.version + " for phigros " + TextUtils.join(" ", release.games));
            } catch (Exception e) {
                result = "Couldn't reach GitHub, the built-in patch is used";
                log("no release, using the built-in patch: " + e.getMessage());
            }
            String latest = null;
            try {
                latest = Release.installer();
                log("installer " + latest + " is the latest");
            } catch (Exception e) {
                log("couldn't check for a newer installer: " + e.getMessage());
            }
            String shown = result + " · checked at " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            String ours = getPackageVersion();
            String offered = latest != null && Release.newer(latest, ours) ? latest : null;
            runOnUiThread(() -> {
                newer.setVisibility(offered == null ? View.GONE : View.VISIBLE);
                if (offered != null) {
                    SpannableStringBuilder line = new SpannableStringBuilder("Installer " + offered + " is available, ");
                    line.append("download it", new UnderlineSpan(), 0);
                    newer.setText(line);
                }
                if (pulled) {
                    status.setTextColor(INK);
                    status.setText(shown);
                    settle();
                }
                refresh();
            });
        }).start();
    }

    // pulling the screen down past PULL checks again, like android's pull to refresh: the column follows the finger at
    // half its pace over a hint, and stays down while checking. the log only pulls once scrolled to its top
    private static final float PULL = 72;
    private float pullFrom = -1;
    private boolean checking;

    private void settle() {
        checking = false;
        column.animate().translationY(0).setDuration(200).start();
        hint.animate().alpha(0).setDuration(200).start();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN)
            pullFrom = busy || checking || scroll.getScrollY() > 0 && touches(scroll, e) ? -1 : e.getRawY();
        float pulled = pullFrom < 0 ? 0 : (e.getRawY() - pullFrom) / 2;
        if (pulled > dp(8) && column.getTranslationY() == 0 && action == MotionEvent.ACTION_MOVE) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        if (pulled > dp(8) || column.getTranslationY() > 0) {
            if (checking) return true;
            if (action == MotionEvent.ACTION_MOVE) {
                column.setTranslationY(Math.min(Math.max(pulled, 0), dp(PULL * 1.5f)));
                hint.setAlpha(Math.min(1, pulled / dp(PULL)));
                hint.setText(pulled >= dp(PULL) ? "Release to check for updates" : "Pull to check for updates");
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pullFrom = -1;
                if (pulled < dp(PULL)) {
                    settle();
                    return true;
                }
                checking = true;
                hint.setText("Checking for updates…");
                column.animate().translationY(dp(PULL)).setDuration(150).start();
                check(true);
            }
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    private static boolean touches(View view, MotionEvent e) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return e.getRawY() >= at[1] && e.getRawY() < at[1] + view.getHeight();
    }

    // the public half of the release key, mod/phigrospp/release.pub
    private byte[] key() throws IOException {
        String hex = new String(asset("release.pub"), StandardCharsets.US_ASCII).trim();
        byte[] key = new byte[hex.length() / 2];
        for (int i = 0; i < key.length; i++) key[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return key;
    }

    // a wide screen (a tablet held sideways) puts the details beside the installer, a narrow one under it. either
    // way the content stays readable: centred, and no wider than it needs
    private void fit() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        boolean wide = screen.widthPixels >= dp(840) && screen.widthPixels > screen.heightPixels;
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lead = wide ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-1, -2);
        lead.gravity = Gravity.CENTER_VERTICAL;
        main.setLayoutParams(lead);
        LinearLayout.LayoutParams rest = wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, 0, 1);
        rest.leftMargin = wide ? dp(44) : 0;
        rest.topMargin = wide ? dp(12) : dp(24);
        details.setLayoutParams(rest);
        int side = Math.max(dp(24), (screen.widthPixels - dp(wide ? 1080 : 640)) / 2);
        column.setPadding(side, dp(wide ? 40 : 24), side, dp(20));
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        fit();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(float size, int color, int weight) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(saira(weight));
        return view;
    }

    // Saira, the typeface of phigros.tools, at a weight: android 8 and newer can set it, older ones get the regular
    private Typeface saira(int weight) {
        Typeface face = weights.get(weight);
        if (face == null) {
            face = Build.VERSION.SDK_INT < 26 ? Typeface.createFromAsset(getAssets(), "Saira.ttf")
                : new Typeface.Builder(getAssets(), "Saira.ttf").setFontVariationSettings("'wght' " + weight).build();
            weights.put(weight, face);
        }
        return face;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    // what a bug report needs, shown on screen so a screenshot carries it
    private void environment() {
        log("installer " + version(getPackageName()) + ", " + Build.MANUFACTURER + " " + Build.MODEL + ", Android "
            + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + "), " + TextUtils.join(" ", Build.SUPPORTED_ABIS));
        log("phigros " + version(GAME) + ", from " + source(GAME) + "; phigros++ " + version(PACKAGE));
        try {
            for (String apk : apks()) log("  " + new File(apk).getName() + " " + mb(new File(apk).length()));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        log("free " + mb(getFilesDir().getUsableSpace()) + ", key " + (new File(getFilesDir(), "key").exists() ? "kept" : "not made yet"));
    }

    private String version(String name) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(name, 0);
            return info.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    private String source(String name) {
        try {
            String from = Build.VERSION.SDK_INT >= 30
                ? getPackageManager().getInstallSourceInfo(name).getInstallingPackageName()
                : getPackageManager().getInstallerPackageName(name);
            return from == null ? "unknown" : from;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private List<String> apks() throws PackageManager.NameNotFoundException {
        ApplicationInfo app = getPackageManager().getApplicationInfo(GAME, 0);
        List<String> apks = new ArrayList<>();
        apks.add(app.sourceDir);
        if (app.splitSourceDirs != null) apks.addAll(Arrays.asList(app.splitSourceDirs));
        return apks;
    }

    private void log(String line) {
        Log.i(TAG, line);
        String stamped = String.format(Locale.ROOT, "%7.1fs %s%n", (SystemClock.elapsedRealtime() - started) / 1000.0, line);
        runOnUiThread(() -> {
            log.append(stamped);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // the button installs, updates (a new patch, or the game was updated) or opens an up to date Phigros++
    private void refresh() {
        try {
            String version = getPackageManager().getPackageInfo(GAME, 0).versionName;
            List<String> games = games();
            boolean supported = games.contains(version);
            game.setText("Phigros " + version);
            state.setText(supported ? "Supported" + (release == null ? "" : " by Phigros++ " + release.version)
                    : older(version, games.get(games.size() - 1)) ? "Update Phigros from the Play Store first"
                    : "Not supported by this Phigros++ yet");
            state.setTextColor(supported ? GLOW : WARN);
            String ours = installedVersion();
            // lua updates arrive in game, so only a new game or native version calls for patching again
            installed = version.equals(ours) && target() == installedNative();
            install.setText((installed ? "Open" : ours == null ? "Install" : "Update") + " Phigros++  →");
            enable(!busy && (installed || supported));
        } catch (PackageManager.NameNotFoundException e) {
            game.setText("Phigros isn't installed");
            state.setText("Get it from the Play Store first, then come back here.");
            state.setTextColor(WARN);
            enable(false);
        }
    }

    private void enable(boolean on) {
        install.setEnabled(on);
        install.setAlpha(on ? 1 : 0.35f);
    }

    private static boolean older(String version, String than) {
        String[] a = version.split("\\."), b = than.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0, y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x < y;
        }
        return false;
    }

    /** the game versions Phigros++ runs on: the release's, or mod/phigrospp/GAMES when the installer was built */
    private List<String> games() {
        if (release != null) return release.games;
        List<String> games = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(getAssets().open("GAMES")))) {
            for (String line; (line = in.readLine()) != null; ) games.add(line);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return games;
    }

    /** libphigrospp.so: the release's, checked against its manifest, or the one built in */
    private byte[] payload() throws IOException {
        Release latest = release;
        if (latest == null) return asset("libphigrospp.so");
        log("downloading libphigrospp.so " + latest.version);
        return latest.lib();
    }

    // the native version of the libphigrospp.so payload() gives
    private int target() {
        Release latest = release;
        try {
            return latest != null ? latest.nativeVersion : nativeOf(asset("libphigrospp.so"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // a lib's built-in bundle starts with "phigros++ <native> <version>": its native version, -1 without one
    private static int nativeOf(byte[] lib) {
        Matcher line = Pattern.compile("phigros\\+\\+ (\\d+) \\d+\\.\\d+\\.\\d+")
            .matcher(new String(lib, StandardCharsets.ISO_8859_1));
        return line.find() ? Integer.parseInt(line.group(1)) : -1;
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private String getPackageVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    // the installed Phigros++ is named after the game version it was patched from
    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // the native version of the libphigrospp.so inside the installed Phigros++, -1 when there's none
    private int installedNative() {
        try {
            ApplicationInfo app = getPackageManager().getApplicationInfo(PACKAGE, 0);
            List<String> apks = new ArrayList<>(Arrays.asList(app.sourceDir));
            if (app.splitSourceDirs != null) apks.addAll(Arrays.asList(app.splitSourceDirs));
            for (String apk : apks) {
                try (FileChannel in = new FileInputStream(apk).getChannel()) {
                    for (Zip.Entry e : Zip.entries(in))
                        if (e.name.equals("lib/arm64-v8a/libphigrospp.so")) return nativeOf(Zip.read(in, e));
                }
            }
        } catch (PackageManager.NameNotFoundException | IOException ignored) {
        }
        return -1;
    }

    private void install() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow this app to install apps, then tap Install again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        busy = true;
        permille = -1;
        enable(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(this::patch).start();
    }

    // each split is patched and signed straight into the install session, so no copy of the game is left on disk
    private void patch() {
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            List<String> apks = apks();
            long total = 0, before = 0;
            for (String apk : apks) total += new File(apk).length();
            byte[] lib = payload();
            log("payload libphigrospp.so " + mb(lib.length));
            Signer signer = new Signer(new File(getFilesDir(), "key"));
            id = installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            log("session " + id);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                boolean patched = false;
                for (int i = 0; i < apks.size(); i++) {
                    File apk = new File(apks.get(i));
                    String step = "Patching " + apk.getName() + " (" + (i + 1) + "/" + apks.size() + ")";
                    long start = SystemClock.elapsedRealtime(), offset = before, all = total;
                    try (OutputStream out = session.openWrite(apk.getName(), 0, -1)) {
                        patched |= Apk.patch(apk, out, PACKAGE, lib, signer, read -> progress(step, (offset + read) * 1000 / all));
                        session.fsync(out);
                    }
                    before += apk.length();
                    log(apk.getName() + " patched in " + (SystemClock.elapsedRealtime() - start) + " ms");
                }
                if (!patched) throw new IOException("this Phigros has no arm64 libmain.so");
                runOnUiThread(() -> {
                    bar.to(1);
                    bar.sweeping(true);
                    status.setText("Installing…");
                    percent.setText("");
                });
                log("committing, free " + mb(getFilesDir().getUsableSpace()));
                Intent result = new Intent(this, MainActivity.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getActivity(this, 0, result, flags).getIntentSender());
            }
        } catch (Exception e) {
            log("patching failed: " + Log.getStackTraceString(e).trim());
            if (id != -1) installer.abandonSession(id);
            failed("Patching failed: " + e);
        }
    }

    // the install session reports here, with launchMode singleTop
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!intent.hasExtra(PackageInstaller.EXTRA_STATUS)) return;
        int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        log("install status " + code + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) startActivity((Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT));
        else if (code == PackageInstaller.STATUS_SUCCESS) done("Phigros++ is installed.");
        else if (code == PackageInstaller.STATUS_FAILURE_CONFLICT)
            failed("Phigros++ is already installed with another signature, uninstall it first.");
        else failed("Installing failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }

    // permille of all the splits' bytes, so the bar fills once across the whole install
    private void progress(String step, long permille) {
        if (permille == this.permille) return;
        this.permille = (int) permille;
        runOnUiThread(() -> {
            bar.setVisibility(View.VISIBLE);
            bar.to(permille / 1000f);
            status.setTextColor(INK);
            status.setText(step);
            percent.setText(permille / 10 + "%");
        });
    }

    private void done(String text) {
        finish(text, GLOW, true);
    }

    private void failed(String text) {
        finish(text, NEGATIVE, false);
    }

    private void finish(String text, int color, boolean ok) {
        runOnUiThread(() -> {
            busy = false;
            bar.sweeping(false);
            bar.setVisibility(ok ? View.VISIBLE : View.GONE);
            status.setTextColor(color);
            status.setText(text);
            percent.setText("");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            refresh();
        });
    }
}
