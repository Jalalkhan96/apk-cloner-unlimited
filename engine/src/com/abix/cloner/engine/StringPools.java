package com.abix.cloner.engine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal reader/writer for Android ResStringPool chunks (AXML + ARSC share the format). */
public final class StringPools {
    public static final int TYPE_STRING_POOL = 0x0001;
    public static final int FLAG_UTF8 = 0x100;

    public final List<String> strings = new ArrayList<>();
    public boolean utf8;
    public int styleCount;
    public byte[] rawStylesBlob;   // styles data kept verbatim (offsets + entries), may be null
    public int[] styleOffsets;

    public static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    public static int u32(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24); }
    public static void put16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    public static void put32(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }

    /** Parse a string-pool chunk starting at chunkOff in data. */
    public static StringPools parse(byte[] data, int chunkOff) {
        StringPools p = new StringPools();
        int stringCount = u32(data, chunkOff + 8);
        p.styleCount = u32(data, chunkOff + 12);
        int flags = u32(data, chunkOff + 16);
        p.utf8 = (flags & FLAG_UTF8) != 0;
        int stringsStart = u32(data, chunkOff + 20);
        int stylesStart = u32(data, chunkOff + 24);
        int chunkSize = u32(data, chunkOff + 4);
        int[] offs = new int[stringCount];
        for (int i = 0; i < stringCount; i++) offs[i] = u32(data, chunkOff + 28 + i * 4);
        for (int i = 0; i < stringCount; i++) {
            int so = chunkOff + stringsStart + offs[i];
            p.strings.add(p.utf8 ? decodeUtf8(data, so) : decodeUtf16(data, so));
        }
        if (p.styleCount > 0 && stylesStart > 0) {
            p.styleOffsets = new int[p.styleCount];
            int sOffBase = chunkOff + 28 + stringCount * 4;
            for (int i = 0; i < p.styleCount; i++) p.styleOffsets[i] = u32(data, sOffBase + i * 4);
            int blobLen = chunkOff + chunkSize - (chunkOff + stylesStart);
            p.rawStylesBlob = new byte[blobLen];
            System.arraycopy(data, chunkOff + stylesStart, p.rawStylesBlob, 0, blobLen);
        }
        return p;
    }

    private static String decodeUtf16(byte[] b, int o) {
        int len = u16(b, o);
        o += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7FFF) << 16) | u16(b, o);
            o += 2;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) { chars[i] = (char) u16(b, o); o += 2; }
        return new String(chars);
    }

    private static String decodeUtf8(byte[] b, int o) {
        int[] r = readUtf8Len(b, o);
        o = r[1];
        r = readUtf8Len(b, o);
        int byteLen = r[0];
        o = r[1];
        return new String(b, o, byteLen, StandardCharsets.UTF_8);
    }

    private static int[] readUtf8Len(byte[] b, int o) {
        int v = b[o] & 0xFF;
        o++;
        if ((v & 0x80) != 0) { v = ((v & 0x7F) << 8) | (b[o] & 0xFF); o++; }
        return new int[]{v, o};
    }

    /** Find index of s, or -1. */
    public int indexOf(String s) { return strings.indexOf(s); }

    /** Find or append; returns index. */
    public int add(String s) {
        int i = strings.indexOf(s);
        if (i >= 0) return i;
        strings.add(s);
        return strings.size() - 1;
    }

    /** Serialize the pool as a chunk, encoded in the given encoding. */
    public byte[] buildChunk(boolean asUtf8) {
        ByteArrayOutputStream strData = new ByteArrayOutputStream();
        int[] offsets = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offsets[i] = strData.size();
            byte[] enc = asUtf8 ? encodeUtf8(strings.get(i)) : encodeUtf16(strings.get(i));
            strData.write(enc, 0, enc.length);
        }
        while ((strData.size() & 3) != 0) strData.write(0);
        int stringsStart = 28 + strings.size() * 4 + styleCount * 4;
        int stylesStart = styleCount > 0 ? stringsStart + strData.size() : 0;
        int total = stringsStart + strData.size() + (styleCount > 0 ? rawStylesBlob.length : 0);
        byte[] out = new byte[total];
        put16(out, 0, TYPE_STRING_POOL);
        put16(out, 2, 28);
        put32(out, 4, total);
        put32(out, 8, strings.size());
        put32(out, 12, styleCount);
        put32(out, 16, asUtf8 ? FLAG_UTF8 : 0);
        put32(out, 20, stringsStart);
        put32(out, 24, stylesStart);
        for (int i = 0; i < offsets.length; i++) put32(out, 28 + i * 4, offsets[i]);
        if (styleCount > 0) {
            for (int i = 0; i < styleCount; i++) put32(out, 28 + strings.size() * 4 + i * 4, styleOffsets[i]);
            System.arraycopy(rawStylesBlob, 0, out, stylesStart, rawStylesBlob.length);
        }
        System.arraycopy(strData.toByteArray(), 0, out, stringsStart, strData.size());
        return out;
    }

    private static byte[] encodeUtf16(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int len = s.length();
        if (len > 0x7FFF) {
            int hi = 0x8000 | (len >> 16);
            o.write(hi & 0xFF); o.write((hi >> 8) & 0xFF);
            o.write(len & 0xFF); o.write((len >> 8) & 0xFF);
        } else {
            o.write(len & 0xFF); o.write((len >> 8) & 0xFF);
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            o.write(c & 0xFF); o.write((c >> 8) & 0xFF);
        }
        o.write(0); o.write(0);
        return o.toByteArray();
    }

    private static byte[] encodeUtf8(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeUtf8Len(o, s.length());
        writeUtf8Len(o, bytes.length);
        o.write(bytes, 0, bytes.length);
        o.write(0);
        return o.toByteArray();
    }

    private static void writeUtf8Len(ByteArrayOutputStream o, int v) {
        if (v > 0x7F) {
            o.write(0x80 | (v >> 8));
            o.write(v & 0xFF);
        } else {
            o.write(v);
        }
    }
}
