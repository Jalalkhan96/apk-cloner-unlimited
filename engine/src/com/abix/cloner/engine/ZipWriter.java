package com.abix.cloner.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Deterministic ZIP writer with zipalign-style padding:
 * STORED entries get their data aligned (4096 for .so, 4 for everything else)
 * via a padding extra field in the local header.
 */
public final class ZipWriter {
    public static final class Entry {
        public final String name;
        public final byte[] data;
        public final boolean stored;
        public Entry(String name, byte[] data, boolean stored) {
            this.name = name; this.data = data; this.stored = stored;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(Entry e) { entries.add(e); }

    public byte[] build() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> central = new ArrayList<>();
        for (Entry e : entries) {
            byte[] nameBytes = e.name.getBytes("UTF-8");
            CRC32 crc = new CRC32();
            crc.update(e.data);
            long crcVal = crc.getValue();
            byte[] payload;
            int method;
            if (e.stored) {
                method = 0;
                payload = e.data;
            } else {
                method = 8;
                payload = deflate(e.data);
            }
            int localOff = out.size();
            int align = e.stored ? (e.name.endsWith(".so") ? 4096 : 4) : 1;
            byte[] extra = new byte[0];
            if (align > 1) {
                int base = localOff + 30 + nameBytes.length;
                int total = 4; // extra field header (id + size)
                int pad = (align - ((base + total) % align)) % align;
                extra = new byte[total + pad];
                extra[0] = (byte) 0xFF; extra[1] = (byte) 0xFF; // 0xFFFF padding id
                extra[2] = (byte) (pad & 0xFF); extra[3] = (byte) ((pad >> 8) & 0xFF);
            }
            // local file header
            write32(out, 0x04034b50);
            write16(out, 20);                 // version needed
            write16(out, 0x800);              // UTF-8 names
            write16(out, method);
            write16(out, 0);                  // time
            write16(out, 0x21);               // date (1980-01-01)
            write32(out, (int) crcVal);
            write32(out, payload.length);
            write32(out, e.data.length);
            write16(out, nameBytes.length);
            write16(out, extra.length);
            out.write(nameBytes);
            out.write(extra);
            out.write(payload);
            // central directory record (kept for the end)
            ByteArrayOutputStream c = new ByteArrayOutputStream();
            write32(c, 0x02014b50);
            write16(c, 20);                   // version made by
            write16(c, 20);                   // version needed
            write16(c, 0x800);
            write16(c, method);
            write16(c, 0);
            write16(c, 0x21);
            write32(c, (int) crcVal);
            write32(c, payload.length);
            write32(c, e.data.length);
            write16(c, nameBytes.length);
            write16(c, 0);                    // extra
            write16(c, 0);                    // comment
            write16(c, 0);                    // disk
            write16(c, 0);                    // internal attrs
            write32(c, 0);                    // external attrs
            write32(c, localOff);
            c.write(nameBytes);
            central.add(c.toByteArray());
        }
        int cdOff = out.size();
        int cdSize = 0;
        for (byte[] c : central) { out.write(c); cdSize += c.length; }
        write32(out, 0x06054b50);
        write16(out, 0);
        write16(out, 0);
        write16(out, entries.size());
        write16(out, entries.size());
        write32(out, cdSize);
        write32(out, cdOff);
        write16(out, 0);
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[Math.max(64, data.length)];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (!d.finished()) {
            int n = d.deflate(buf);
            o.write(buf, 0, n);
        }
        d.end();
        return o.toByteArray();
    }

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }
    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }
}
