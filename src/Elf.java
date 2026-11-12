package sh.molly.phigrosplusplus.installer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** makes a 64-bit little-endian shared library load another one, like mod/scripts/patch_libmain.py */
final class Elf {
    private static final int PT_LOAD = 1, PT_DYNAMIC = 2, PT_PHDR = 6, PF_R = 4, SHT_DYNAMIC = 6;
    private static final int PHDR = 56, SHDR = 64, DYN = 16;
    private static final long DT_NULL = 0, DT_NEEDED = 1, DT_STRTAB = 5, DT_STRSZ = 10;

    // the program headers, the dynamic section and the string table are copied, grown, into a new segment at the end
    // of the file, so nothing else moves and no address in the code changes
    static byte[] addNeeded(byte[] elf, String library) {
        ByteBuffer in = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt(0) != 0x464C457F || elf[4] != 2 || elf[5] != 1)
            throw new IllegalArgumentException("not a 64-bit little-endian ELF");
        int phoff = (int) in.getLong(0x20), phnum = in.getShort(0x38) & 0xFFFF;
        int dynamic = 0, lastLoad = 0;
        long align = 0, end = 0;
        for (int i = 0; i < phnum; i++) {
            int p = phoff + i * PHDR;
            if (in.getInt(p) == PT_DYNAMIC) dynamic = (int) in.getLong(p + 8);
            if (in.getInt(p) == PT_LOAD) {
                lastLoad = i;
                align = Math.max(align, in.getLong(p + 48));
                end = Math.max(end, in.getLong(p + 16) + in.getLong(p + 40));
            }
        }

        int entries = 0, needed = 0;
        long strtab = 0, strsz = 0;
        for (long tag; (tag = in.getLong(dynamic + entries * DYN)) != DT_NULL; entries++) {
            long value = in.getLong(dynamic + entries * DYN + 8);
            if (tag == DT_NEEDED) needed = entries + 1;
            if (tag == DT_STRTAB) strtab = value;
            if (tag == DT_STRSZ) strsz = value;
        }
        int strings = offset(in, phoff, phnum, strtab);
        for (int i = 0; i < entries; i++)
            if (in.getLong(dynamic + i * DYN) == DT_NEEDED && library.equals(string(elf, strings + (int) in.getLong(dynamic + i * DYN + 8))))
                return elf;

        byte[] name = (library + "\0").getBytes(StandardCharsets.UTF_8);
        int start = (elf.length + 7) & ~7;
        int phsize = (phnum + 1) * PHDR, dynsize = (entries + 2) * DYN, strsize = (int) strsz + name.length;
        int dyn = start + phsize, str = dyn + dynsize, size = phsize + dynsize + strsize;
        // a segment's address and file offset must be equal modulo its alignment
        long vaddr = (end + align - 1) / align * align + start % align;
        ByteBuffer out = ByteBuffer.wrap(Arrays.copyOf(elf, start + size)).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0, o = start; i < phnum; i++, o += PHDR) {
            System.arraycopy(elf, phoff + i * PHDR, out.array(), o, PHDR);
            if (out.getInt(o) == PT_PHDR) segment(out, o, start, vaddr, phsize);
            if (out.getInt(o) == PT_DYNAMIC) segment(out, o, dyn, vaddr + phsize, dynsize);
            if (i == lastLoad) {
                o += PHDR;
                segment(out.putInt(o, PT_LOAD).putInt(o + 4, PF_R).putLong(o + 48, align), o, start, vaddr, size);
            }
        }
        for (int i = 0, o = dyn; i <= entries; i++, o += DYN) {
            if (i == needed) {
                out.putLong(o, DT_NEEDED).putLong(o + 8, strsz);
                o += DYN;
            }
            long tag = in.getLong(dynamic + i * DYN), value = in.getLong(dynamic + i * DYN + 8);
            if (tag == DT_STRTAB) value = vaddr + str - start;
            if (tag == DT_STRSZ) value = strsize;
            out.putLong(o, tag).putLong(o + 8, value);
        }
        System.arraycopy(elf, strings, out.array(), str, (int) strsz);
        System.arraycopy(name, 0, out.array(), str + (int) strsz, name.length);
        out.putLong(0x20, start).putShort(0x38, (short) (phnum + 1));

        // android's linker reads the needed libraries through the section headers, before loading
        int shoff = (int) in.getLong(0x28), shnum = in.getShort(0x3C) & 0xFFFF;
        for (int i = 0; i < shnum; i++) {
            int s = shoff + i * SHDR;
            if (in.getInt(s + 4) != SHT_DYNAMIC) continue;
            section(out, s, dyn, vaddr + phsize, dynsize);
            section(out, shoff + in.getInt(s + 40) * SHDR, str, vaddr + str - start, strsize);
        }
        return out.array();
    }

    private static void segment(ByteBuffer b, int p, long offset, long vaddr, long size) {
        b.putLong(p + 8, offset).putLong(p + 16, vaddr).putLong(p + 24, vaddr).putLong(p + 32, size).putLong(p + 40, size);
    }

    private static void section(ByteBuffer b, int s, long offset, long vaddr, long size) {
        b.putLong(s + 16, vaddr).putLong(s + 24, offset).putLong(s + 32, size);
    }

    private static int offset(ByteBuffer in, int phoff, int phnum, long vaddr) {
        for (int i = 0; i < phnum; i++) {
            int p = phoff + i * PHDR;
            long start = in.getLong(p + 16);
            if (in.getInt(p) == PT_LOAD && vaddr >= start && vaddr < start + in.getLong(p + 32))
                return (int) (in.getLong(p + 8) + vaddr - start);
        }
        throw new IllegalArgumentException("address " + vaddr + " isn't in the file");
    }

    private static String string(byte[] b, int at) {
        int end = at;
        while (b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.UTF_8);
    }
}
