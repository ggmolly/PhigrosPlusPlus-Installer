package sh.molly.phigrosplusplus.installer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** reads the entries of a zip and writes them the way mod/scripts/patch_apk.py does, without zip64 */
final class Zip {
    static final int STORED = 0, DEFLATED = 8;

    static final class Entry {
        String name;
        int made, needed, flags, method, time, date;
        long crc, compressedSize, size, external, offset;
    }

    static List<Entry> entries(FileChannel in) throws IOException {
        int tail = (int) Math.min(in.size(), 22 + 0xFFFF);
        ByteBuffer end = read(in, in.size() - tail, tail);
        int at = tail - 22;
        while (at >= 0 && end.getInt(at) != 0x06054B50) at--;
        if (at < 0) throw new IOException("not a zip file");
        int count = end.getShort(at + 10) & 0xFFFF;
        ByteBuffer cd = read(in, end.getInt(at + 16) & 0xFFFFFFFFL, end.getInt(at + 12));
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0, p = 0; i < count; i++) {
            Entry e = new Entry();
            e.made = cd.getShort(p + 4) & 0xFFFF;
            e.needed = cd.getShort(p + 6) & 0xFFFF;
            e.flags = cd.getShort(p + 8) & 0xFFFF;
            e.method = cd.getShort(p + 10) & 0xFFFF;
            e.time = cd.getShort(p + 12) & 0xFFFF;
            e.date = cd.getShort(p + 14) & 0xFFFF;
            e.crc = cd.getInt(p + 16) & 0xFFFFFFFFL;
            e.compressedSize = cd.getInt(p + 20) & 0xFFFFFFFFL;
            e.size = cd.getInt(p + 24) & 0xFFFFFFFFL;
            int name = cd.getShort(p + 28) & 0xFFFF;
            e.external = cd.getInt(p + 38) & 0xFFFFFFFFL;
            e.offset = cd.getInt(p + 42) & 0xFFFFFFFFL;
            e.name = new String(cd.array(), p + 46, name, StandardCharsets.UTF_8);
            p += 46 + name + (cd.getShort(p + 30) & 0xFFFF) + (cd.getShort(p + 32) & 0xFFFF);
            entries.add(e);
        }
        return entries;
    }

    /** the uncompressed content of an entry */
    static byte[] read(FileChannel in, Entry e) throws IOException {
        byte[] raw = read(in, data(in, e), (int) e.compressedSize).array();
        if (e.method == STORED) return raw;
        Inflater inflater = new Inflater(true);
        inflater.setInput(raw);
        byte[] out = new byte[(int) e.size];
        try {
            for (int n = 0; n < out.length; ) {
                int got = inflater.inflate(out, n, out.length - n);
                if (got == 0 && inflater.needsInput()) throw new EOFException(e.name + " is truncated");
                n += got;
            }
        } catch (DataFormatException x) {
            throw new IOException(e.name, x);
        } finally {
            inflater.end();
        }
        return out;
    }

    private static long data(FileChannel in, Entry e) throws IOException {
        ByteBuffer local = read(in, e.offset + 26, 4);
        return e.offset + 30 + (local.getShort(0) & 0xFFFF) + (local.getShort(2) & 0xFFFF);
    }

    private static ByteBuffer read(FileChannel in, long at, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) if (in.read(b, at + b.position()) < 0) throw new EOFException();
        return b;
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static final class Writer {
        private final OutputStream out;
        private final ByteArrayOutputStream central = new ByteArrayOutputStream();
        private final byte[] buffer = new byte[1 << 20];
        private long position;
        private int count;

        Writer(OutputStream out) {
            this.out = out;
        }

        /** copies an entry of in as it is, still compressed */
        void copy(FileChannel in, Entry e) throws IOException {
            header(e);
            ByteBuffer b = ByteBuffer.wrap(buffer);
            for (long at = data(in, e), left = e.compressedSize; left > 0; ) {
                b.clear();
                b.limit((int) Math.min(left, buffer.length));
                int n = in.read(b, at);
                if (n < 0) throw new EOFException(e.name + " is truncated");
                write(buffer, n);
                at += n;
                left -= n;
            }
        }

        void add(String name, byte[] data, boolean deflate) throws IOException {
            byte[] body = data;
            if (deflate) {
                Deflater deflater = new Deflater(9, true);
                deflater.setInput(data);
                deflater.finish();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                while (!deflater.finished()) b.write(buffer, 0, deflater.deflate(buffer));
                deflater.end();
                body = b.toByteArray();
            }
            CRC32 crc = new CRC32();
            crc.update(data);
            Entry e = new Entry();
            e.name = name;
            e.made = e.needed = 20;
            e.method = deflate ? DEFLATED : STORED;
            e.date = (2020 - 1980) << 9 | 1 << 5 | 1;
            e.crc = crc.getValue();
            e.compressedSize = body.length;
            e.size = data.length;
            header(e);
            write(body, body.length);
        }

        // an uncompressed entry's data starts on its alignment, as android requires for resources.arsc, padded with
        // the extra field zipalign uses (0xd935)
        private void header(Entry e) throws IOException {
            byte[] name = e.name.getBytes(StandardCharsets.UTF_8);
            int align = e.name.endsWith(".so") ? 16384 : 4, pad = 0;
            if (e.method == STORED) {
                pad = (int) Math.floorMod(-(position + 30 + name.length), (long) align);
                while (pad != 0 && pad < 6) pad += align;
            }
            ByteBuffer local = le(30 + name.length + pad).putInt(0x04034B50);
            fields(local, e).putShort((short) name.length).putShort((short) pad).put(name);
            if (pad != 0) local.putShort((short) 0xD935).putShort((short) (pad - 4)).putShort((short) align);
            ByteBuffer c = le(46 + name.length).putInt(0x02014B50).putShort((short) e.made);
            fields(c, e).putShort((short) name.length).putLong(0).putInt((int) e.external).putInt((int) position);
            central.write(c.put(name).array(), 0, c.capacity());
            count++;
            write(local.array(), local.capacity());
        }

        private static ByteBuffer fields(ByteBuffer b, Entry e) {
            return b.putShort((short) e.needed).putShort((short) (e.flags & ~8)).putShort((short) e.method)
                .putShort((short) e.time).putShort((short) e.date).putInt((int) e.crc)
                .putInt((int) e.compressedSize).putInt((int) e.size);
        }

        private void write(byte[] b, int n) throws IOException {
            out.write(b, 0, n);
            position += n;
        }

        byte[] central() {
            return central.toByteArray();
        }

        /** the end of central directory record, for a central directory written right after the entries */
        byte[] end() {
            return le(22).putInt(0x06054B50).putInt(0).putShort((short) count).putShort((short) count)
                .putInt(central.size()).putInt((int) position).putShort((short) 0).array();
        }
    }
}
