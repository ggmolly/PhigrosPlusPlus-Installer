package sh.molly.phigrosplusplus.installer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/** the port of mod/scripts/patch_apk.py and the apk part of mod/scripts/build-apk.sh */
final class Apk {
    private static final String ORIGINAL = "com.PigeonGames.Phigros", LABEL = "Phigros++";
    private static final String LIBMAIN = "lib/arm64-v8a/libmain.so", LIB = "lib/arm64-v8a/libphigrospp.so";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String LAUNCHER = "android.intent.category.LAUNCHER";
    private static final String SCHEME = "phigrospp"; // of the links that open the app (mod/phigrospp/lua/catalog.lua reads them)
    // android:label, android:name and android:scheme
    private static final int LABEL_ATTRIBUTE = 0x01010001, NAME_ATTRIBUTE = 0x01010003, SCHEME_ATTRIBUTE = 0x01010027;
    // apksigner drops the source stamp, dropping it here lets a release be compared without its signature
    private static final String[] DROPPED = {"META-INF/BNDLTOOL.", "META-INF/MANIFEST.MF", "stamp-cert-sha256"};

    /**
     * writes src under another package, named Phigros++ in the launcher with the ++ tab on its icon, opened by
     * phigrospp:// links, its libmain.so loading lib, signed by signer. the other entries are copied byte for byte. returns whether src had the libmain.so
     */
    static boolean patch(File src, OutputStream dst, String pkg, byte[] lib, Signer signer, LongConsumer read)
            throws IOException, GeneralSecurityException {
        try (FileChannel in = new FileInputStream(src).getChannel()) {
            Signer.Output out = signer.output(dst);
            Zip.Writer zip = new Zip.Writer(out);
            byte[] libmain = null;
            for (Zip.Entry e : Zip.entries(in)) {
                if (dropped(e.name) || e.name.equals(LIB)) continue;
                if (e.name.equals("AndroidManifest.xml")) zip.add(e.name, manifest(Zip.read(in, e), pkg), true);
                else if (e.name.equals("resources.arsc")) zip.add(e.name, resources(Zip.read(in, e), pkg), false);
                else if (e.name.equals(LIBMAIN)) libmain = Zip.read(in, e);
                else if (Badge.icon(e.name)) zip.add(e.name, Badge.png(Zip.read(in, e)), false);
                else zip.copy(in, e);
                read.accept(e.offset + e.compressedSize);
            }
            if (libmain != null) {
                zip.add(LIBMAIN, Elf.addNeeded(libmain, "libphigrospp.so"), true);
                zip.add(LIB, lib, true);
            }
            out.finish(zip.central(), zip.end());
            return libmain != null;
        }
    }

    private static boolean dropped(String name) {
        for (String prefix : DROPPED) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static boolean renamed(String s) {
        return s.equals(ORIGINAL) || s.startsWith(ORIGINAL + ".") && s.endsWith(".provider");
    }

    // a binary manifest is a string pool then the elements, which point into the pool by index
    static byte[] manifest(byte[] data, String pkg) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pool = 8, header = in.getShort(pool + 2), size = in.getInt(pool + 4), count = in.getInt(pool + 8);
        int flags = in.getInt(pool + 16), start = in.getInt(pool + 20);
        if (in.getInt(pool + 12) != 0) throw new IOException("AndroidManifest.xml: styled strings aren't handled");
        boolean utf8 = (flags & 0x100) != 0;
        List<String> strings = new ArrayList<>(count + 1);
        for (int i = 0; i < count; i++) {
            int at = pool + start + in.getInt(pool + header + 4 * i);
            String s;
            // utf-8 strings start with their length in characters then in bytes, utf-16 ones with it in units
            if (utf8) {
                at += (data[at] & 0x80) != 0 ? 2 : 1;
                int n = data[at] & 0xFF;
                if ((n & 0x80) != 0) n = (n & 0x7F) << 8 | data[++at] & 0xFF;
                s = new String(data, at + 1, n, StandardCharsets.UTF_8);
            } else {
                int n = in.getShort(at) & 0xFFFF;
                if ((n & 0x8000) != 0) n = (n & 0x7FFF) << 16 | in.getShort(at += 2) & 0xFFFF;
                s = new String(data, at + 2, 2 * n, StandardCharsets.UTF_16LE);
            }
            strings.add(renamed(s) ? pkg + s.substring(ORIGINAL.length()) : s);
        }

        ByteBuffer rest = ByteBuffer.wrap(Arrays.copyOfRange(data, pool + size, data.length)).order(ByteOrder.LITTLE_ENDIAN);
        int[] ids = {};
        int idmap = 0, activity = 0, launcher = -1; // where the ids are, and inside which activity the launcher's filter is
        for (int at = 0; at < rest.capacity(); at += rest.getInt(at + 4)) {
            int kind = rest.getShort(at) & 0xFFFF;
            String element = kind == 0x0102 ? strings.get(rest.getInt(at + 20)) : "";
            if (kind == 0x0180) { // resource ids of the first strings, the attribute names
                idmap = at;
                ids = new int[(rest.getInt(at + 4) - 8) / 4];
                for (int i = 0; i < ids.length; i++) ids[i] = rest.getInt(at + 8 + 4 * i);
            } else if (element.equals("activity")) {
                activity = at + rest.getInt(at + 4);
            } else if (element.equals("application") || element.equals("category")) {
                int first = rest.getShort(at + 24), step = rest.getShort(at + 26), attributes = rest.getShort(at + 28);
                for (int i = 0; i < attributes; i++) {
                    int attribute = at + 16 + first + i * step, name = rest.getInt(attribute + 4), value = rest.getInt(attribute + 8);
                    if (element.equals("category")) {
                        if (value >= 0 && value < strings.size() && strings.get(value).equals(LAUNCHER)) launcher = activity;
                    } else if (name < ids.length && ids[name] == LABEL_ATTRIBUTE) {
                        strings.add(LABEL);
                        int index = strings.size() - 1;
                        rest.putInt(attribute + 8, index).putShort(attribute + 12, (short) 8).put(attribute + 14, (byte) 0)
                            .put(attribute + 15, (byte) 0x03).putInt(attribute + 16, index);
                    }
                }
            }
        }
        // the launcher's activity also opens phigrospp:// links, from a browser too. only the base apk has activities
        if (launcher >= 0) {
            int name = 0;
            while (ids[name] != NAME_ATTRIBUTE) name++;
            // an attribute's name is its string's resource id: the map grows to reach the new one, other strings map to 0
            strings.add("scheme");
            int scheme = strings.size() - 1, end = idmap + rest.getInt(idmap + 4);
            ids = Arrays.copyOf(ids, scheme + 1);
            ids[scheme] = SCHEME_ATTRIBUTE;
            byte[] link = element(strings, "intent-filter", 0, null,
                element(strings, "action", name, "android.intent.action.VIEW"),
                element(strings, "category", name, "android.intent.category.DEFAULT"),
                element(strings, "category", name, "android.intent.category.BROWSABLE"),
                element(strings, "data", scheme, SCHEME));
            ByteBuffer grown = Zip.le(rest.capacity() - (end - idmap) + 8 + 4 * ids.length + link.length);
            grown.put(rest.array(), 0, idmap).putShort((short) 0x0180).putShort((short) 8).putInt(8 + 4 * ids.length);
            for (int id : ids) grown.putInt(id);
            grown.put(rest.array(), end, launcher - end).put(link).put(rest.array(), launcher, rest.capacity() - launcher);
            rest = grown;
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ByteBuffer offsets = Zip.le(4 * strings.size());
        for (String s : strings) {
            offsets.putInt(body.size());
            encode(body, s, utf8);
        }
        while (body.size() % 4 != 0) body.write(0);
        header = 28 + 4 * strings.size();
        int total = 8 + header + body.size() + rest.capacity();
        ByteBuffer out = Zip.le(total).putShort((short) 3).putShort((short) 8).putInt(total);
        out.putShort((short) 1).putShort((short) 28).putInt(header + body.size()).putInt(strings.size()).putInt(0)
            .putInt(flags).putInt(header).putInt(0);
        return out.put(offsets.array()).put(body.toByteArray()).put(rest.array()).array();
    }

    private static int string(List<String> strings, String s) {
        if (!strings.contains(s)) strings.add(s);
        return strings.indexOf(s);
    }

    // an element around its children, with a string attribute of the android namespace when it's given a value
    private static byte[] element(List<String> strings, String tag, int name, String value, byte[]... children) {
        int attributes = value == null ? 0 : 1, inside = 0;
        for (byte[] child : children) inside += child.length;
        int text = value == null ? 0 : string(strings, value), index = string(strings, tag);
        ByteBuffer out = Zip.le(36 + 20 * attributes + inside + 24);
        out.putShort((short) 0x0102).putShort((short) 16).putInt(36 + 20 * attributes).putInt(0).putInt(-1).putInt(-1)
            .putInt(index).putShort((short) 20).putShort((short) 20).putShort((short) attributes).putShort((short) 0)
            .putShort((short) 0).putShort((short) 0);
        if (value != null)
            out.putInt(strings.indexOf(ANDROID)).putInt(name).putInt(text).putShort((short) 8).put((byte) 0).put((byte) 0x03)
                .putInt(text);
        for (byte[] child : children) out.put(child);
        return out.putShort((short) 0x0103).putShort((short) 16).putInt(24).putInt(0).putInt(-1).putInt(-1).putInt(index).array();
    }

    private static void encode(ByteArrayOutputStream out, String s, boolean utf8) throws IOException {
        if (utf8) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            length(out, s.length(), false);
            length(out, b.length, false);
            out.write(b);
            out.write(0);
        } else {
            length(out, s.length(), true);
            out.write(s.getBytes(StandardCharsets.UTF_16LE));
            out.write(new byte[2]);
        }
    }

    private static void length(ByteArrayOutputStream out, int n, boolean wide) {
        if (wide && n >= 0x8000) out.write(Zip.le(4).putShort((short) (0x8000 | n >> 16)).putShort((short) n).array(), 0, 4);
        else if (wide) out.write(Zip.le(2).putShort((short) n).array(), 0, 2);
        else if (n >= 0x80) out.write(new byte[] {(byte) (0x80 | n >> 8), (byte) n}, 0, 2);
        else out.write(n);
    }

    // unity finds its strings with getIdentifier(name, type, getPackageName()), which only searches the resource
    // table's package of that name
    static byte[] resources(byte[] data, String pkg) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] original = Arrays.copyOf(ORIGINAL.getBytes(StandardCharsets.UTF_16LE), 256);
        byte[] renamed = Arrays.copyOf(pkg.getBytes(StandardCharsets.UTF_16LE), 256);
        for (int at = b.getShort(2); at < data.length; at += b.getInt(at + 4)) {
            if (b.getShort(at) == 0x0200 && Arrays.equals(Arrays.copyOfRange(data, at + 12, at + 268), original))
                System.arraycopy(renamed, 0, data, at + 12, 256);
        }
        return data;
    }
}
