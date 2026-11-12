package sh.molly.phigrosplusplus.installer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * the latest Phigros++ release, from the manifest mod/scripts/release.sh signs with the release key: its version, the
 * game versions it runs on and the libphigrospp.so to patch them with
 */
final class Release {
    private static final String DOWNLOAD = "https://github.com/ggmolly/PhigrosPlusPlus/releases/latest/download/";
    final String version;
    final int nativeVersion; // mod/phigrospp/NATIVE: a release with the same one only changes lua, updated in game
    final List<String> games;
    final String lib;
    private final long size;

    private Release(Map<String, String> fields) {
        version = fields.get("version");
        nativeVersion = Integer.parseInt(fields.get("native"));
        games = Arrays.asList(fields.get("games").split(" "));
        String[] lib = fields.get("lib").split(" ");
        this.lib = lib[0];
        size = Long.parseLong(lib[1]);
    }

    static Release fetch(byte[] key) throws IOException {
        byte[] signed = get(DOWNLOAD + "phigrospp.installer", 1 << 16);
        byte[] message = Arrays.copyOfRange(signed, 64, signed.length);
        if (!Ed25519.verify(key, message, Arrays.copyOf(signed, 64))) throw new IOException("the release isn't signed by the release key");
        String[] lines = new String(message, StandardCharsets.UTF_8).split("\n");
        if (!lines[0].equals("phigros++ installer 1")) throw new IOException("the release is for a newer installer");
        Map<String, String> fields = new HashMap<>();
        for (String line : lines) fields.put(line.substring(0, Math.max(line.indexOf(' '), 0)), line.substring(line.indexOf(' ') + 1));
        return new Release(fields);
    }

    byte[] lib() throws IOException {
        byte[] data = get(DOWNLOAD + "libphigrospp.so", size);
        if (data.length != size || !sha256(data).equals(lib)) throw new IOException("the downloaded libphigrospp.so isn't the release's");
        return data;
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // github answers with a redirect to its file host, which HttpURLConnection follows as both are https
    private static byte[] get(String url, long max) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException(url + ": HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    if (out.size() > max) throw new IOException(url + ": bigger than expected");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
