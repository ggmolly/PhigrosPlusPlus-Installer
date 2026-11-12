package sh.molly.phigrosplusplus.installer;

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
import android.graphics.drawable.ColorDrawable;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.util.Log;
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
    // phigros.tools' colours: background, panels, text, secondary text, and the game state's
    private static final int BG = 0xFF16121A, PANEL = 0xFF0D0A0E, INK = 0xFFF4F2F6, MUTED = 0xFFB3ACB8,
        OK = 0xFF7BD88F, WARN = 0xFFF2C65A, NEGATIVE = 0xFFFF9D94;
    private static final String[][] LINKS = {
        {"phigros.tools", "https://phigros.tools"},
        {"Phigros++ on GitHub", "https://github.com/ggmolly/PhigrosPlusPlus"},
        {"phigros.tools on GitHub", "https://github.com/ggmolly/phigros-tools"},
    };
    private TextView game, state, status, log, hint;
    private LinearLayout column;
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
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = text(32, INK);
        title.setText("Phigros++");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView subtitle = text(14, MUTED);
        subtitle.setText("Installer " + version(getPackageName()));
        column.addView(title);
        column.addView(subtitle);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new Slant(PANEL, dp(10)));
        card.setPadding(dp(24), dp(14), dp(24), dp(14));
        game = text(18, INK);
        state = text(14, MUTED);
        card.addView(game);
        card.addView(state);
        column.addView(card, spaced(20));

        install = new Button(this);
        install.setText("Install Phigros++");
        install.setAllCaps(false);
        install.setTextSize(16);
        install.setTextColor(PANEL);
        install.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        install.setStateListAnimator(null);
        install.setBackground(new Slant(INK, dp(10)));
        install.setForeground(new RippleDrawable(ColorStateList.valueOf(0x33000000), null, new Slant(INK, dp(10))));
        install.setOnClickListener(v -> {
            if (installed) startActivity(getPackageManager().getLaunchIntentForPackage(PACKAGE));
            else install();
        });
        LinearLayout.LayoutParams button = spaced(16);
        button.height = dp(52);
        column.addView(install, button);

        bar = new Bar(this, PANEL, INK);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams track = spaced(16);
        track.height = dp(10);
        column.addView(bar, track);
        status = text(14, INK);
        column.addView(status, spaced(8));

        TextView details = text(12, MUTED);
        details.setText("Details");
        details.setLetterSpacing(0.08f);
        column.addView(details, spaced(20));
        log = text(11, MUTED);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        scroll = new ScrollView(this);
        scroll.setBackground(new ColorDrawable(PANEL));
        scroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll.addView(log);
        LinearLayout.LayoutParams box = spaced(6);
        box.height = 0;
        box.weight = 1;
        column.addView(scroll, box);

        TextView notice = text(12, MUTED);
        notice.setText("Phigros++ is an unofficial mod, not affiliated with or endorsed by Pigeon Games.");
        column.addView(notice, spaced(14));
        TextView links = text(14, MUTED);
        SpannableStringBuilder line = new SpannableStringBuilder();
        for (String[] link : LINKS) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(link[0], new URLSpan(link[1]), 0);
        }
        links.setText(line);
        links.setLinkTextColor(INK);
        links.setMovementMethod(LinkMovementMethod.getInstance());
        column.addView(links, spaced(8));

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new ColorDrawable(BG));
        root.setFitsSystemWindows(true);
        hint = text(14, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setAlpha(0);
        root.addView(hint, new FrameLayout.LayoutParams(-1, dp(PULL), Gravity.TOP));
        root.addView(column);
        setContentView(root);
        fit();
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
            String shown = result + " · checked at " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            runOnUiThread(() -> {
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

    // the column stays readable on a tablet: at most 640dp wide, centred
    private void fit() {
        int side = Math.max(dp(24), (getResources().getDisplayMetrics().widthPixels - dp(640)) / 2);
        column.setPadding(side, dp(28), side, dp(20));
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        fit();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(float size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
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
            boolean supported = games().contains(version);
            game.setText("Phigros " + version);
            state.setText(supported ? "Supported" + (release == null ? "" : " by Phigros++ " + release.version) : "Not supported by this Phigros++ yet");
            state.setTextColor(supported ? OK : WARN);
            String ours = installedVersion();
            // lua updates arrive in game, so only a new game or native version calls for patching again
            installed = version.equals(ours) && target() == installedNative();
            install.setText(installed ? "Open Phigros++" : ours == null ? "Install Phigros++" : "Update Phigros++");
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

    // a lib's built-in bundle starts with "phigros++ <native> <version> <games>": its native version, -1 without one
    private static int nativeOf(byte[] lib) {
        Matcher line = Pattern.compile("phigros\\+\\+ (\\d+) \\d+\\.\\d+\\.\\d+\\S* [\\d. ]+\n")
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
            status.setText(step + " · " + permille / 10 + "%");
        });
    }

    private void done(String text) {
        finish(text, OK, true);
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
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            refresh();
        });
    }
}
