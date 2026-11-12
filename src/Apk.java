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
    private static final int LABEL_ATTRIBUTE = 0x01010001; // android:label
    // apksigner drops the source stamp, dropping it here lets a release be compared without its signature
    private static final String[] DROPPED = {"META-INF/BNDLTOOL.", "META-INF/MANIFEST.MF", "stamp-cert-sha256"};

    /**
     * writes src under another package, named Phigros++ in the launcher with the ++ tab on its icon, its libmain.so
     * loading lib, signed by signer. the other entries are copied byte for byte. returns whether src had the libmain.so
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
        for (int at = 0; at < rest.capacity(); at += rest.getInt(at + 4)) {
            int kind = rest.getShort(at) & 0xFFFF;
            if (kind == 0x0180) { // resource ids of the first strings, the attribute names
                ids = new int[(rest.getInt(at + 4) - 8) / 4];
                for (int i = 0; i < ids.length; i++) ids[i] = rest.getInt(at + 8 + 4 * i);
            } else if (kind == 0x0102 && strings.get(rest.getInt(at + 20)).equals("application")) {
                int first = rest.getShort(at + 24), step = rest.getShort(at + 26), attributes = rest.getShort(at + 28);
                for (int i = 0; i < attributes; i++) {
                    int attribute = at + 16 + first + i * step, name = rest.getInt(attribute + 4);
                    if (name >= ids.length || ids[name] != LABEL_ATTRIBUTE) continue;
                    strings.add(LABEL);
                    int index = strings.size() - 1;
                    rest.putInt(attribute + 8, index).putShort(attribute + 12, (short) 8).put(attribute + 14, (byte) 0)
                        .put(attribute + 15, (byte) 0x03).putInt(attribute + 16, index);
                }
            }
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
