package com.abix.cloner.engine;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.abix.cloner.engine.StringPools.*;

/**
 * Patches resources.arsc for a clone:
 *  - renames the package in the ResTable_package header (must match the manifest)
 *  - appends the label suffix to the string resources used as app/activity labels
 */
public final class ArscEditor {
    private static final int TYPE_TABLE = 0x0002;
    private static final int TYPE_PACKAGE = 0x0200;
    private static final int TYPE_TYPE = 0x0201;
    private static final int DATA_TYPE_STRING = 0x03;

    public int labelStringsPatched;

    public byte[] rewrite(byte[] arsc, String oldPackage, String newPackage,
                          List<Integer> labelResIds, String labelSuffix) {
        return rewrite(arsc, oldPackage, newPackage, labelResIds, labelSuffix, null);
    }

    /**
     * When labelOverride is non-null, the label string resources are set to
     * that exact text (custom clone name) instead of original + suffix.
     */
    public byte[] rewrite(byte[] arsc, String oldPackage, String newPackage,
                          List<Integer> labelResIds, String labelSuffix, String labelOverride) {
        if (u16(arsc, 0) != TYPE_TABLE) throw new IllegalArgumentException("not a resources.arsc");
        int pos = u16(arsc, 2); // header size
        // first chunk should be the global string pool
        if (u16(arsc, pos) != TYPE_STRING_POOL) throw new IllegalArgumentException("arsc: string pool not found");
        int poolOff = pos;
        int poolSize = u32(arsc, poolOff + 4);
        StringPools pool = StringPools.parse(arsc, poolOff);

        Set<Integer> labelPoolIdx = new LinkedHashSet<>();

        // walk package chunks
        pos = poolOff + poolSize;
        int fileEnd = arsc.length;
        int pkgNameChunkOff = -1;
        while (pos + 8 <= fileEnd) {
            int type = u16(arsc, pos);
            int size = u32(arsc, pos + 4);
            if (size <= 0) break;
            if (type == TYPE_PACKAGE) {
                String pkgName = readPkgName(arsc, pos);
                if (oldPackage.equals(pkgName)) {
                    pkgNameChunkOff = pos;
                    collectLabelStrings(arsc, pos, size, labelResIds, labelPoolIdx);
                }
            }
            pos += size;
        }
        if (pkgNameChunkOff < 0) throw new IllegalArgumentException("arsc: package chunk for " + oldPackage + " not found");

        // patch the label strings in the pool: exact override when a custom
        // clone name was given, otherwise append the suffix
        for (int idx : labelPoolIdx) {
            if (idx >= 0 && idx < pool.strings.size()) {
                String v = pool.strings.get(idx);
                if (labelOverride != null) {
                    if (!v.equals(labelOverride)) {
                        pool.strings.set(idx, labelOverride);
                        labelStringsPatched++;
                    }
                } else if (!v.endsWith(labelSuffix)) {
                    pool.strings.set(idx, v + labelSuffix);
                    labelStringsPatched++;
                }
            }
        }
        byte[] newPool = pool.buildChunk(pool.utf8);

        // reassemble: header + new pool + rest (patching the package name in place)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int headerSize = u16(arsc, 2);
        out.write(arsc, 0, headerSize);
        out.write(newPool, 0, newPool.length);
        pos = poolOff + poolSize;
        while (pos + 8 <= fileEnd) {
            int size = u32(arsc, pos + 4);
            if (size <= 0) break;
            byte[] chunk = new byte[size];
            System.arraycopy(arsc, pos, chunk, 0, size);
            if (pos == pkgNameChunkOff) writePkgName(chunk, newPackage);
            out.write(chunk, 0, size);
            pos += size;
        }
        byte[] result = out.toByteArray();
        put32(result, 4, result.length);
        return result;
    }

    private static String readPkgName(byte[] b, int chunkOff) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            int c = u16(b, chunkOff + 12 + i * 2);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static void writePkgName(byte[] chunk, String name) {
        if (name.length() > 127) throw new IllegalArgumentException("package name too long for arsc");
        for (int i = 0; i < 128; i++) {
            put16(chunk, 12 + i * 2, i < name.length() ? name.charAt(i) : 0);
        }
    }

    /** For every label resId, find its string values across configs and collect global pool indexes. */
    private static void collectLabelStrings(byte[] arsc, int pkgOff, int pkgSize,
                                            List<Integer> labelResIds, Set<Integer> outIdx) {
        if (labelResIds == null || labelResIds.isEmpty()) return;
        int pkgId = u32(arsc, pkgOff + 8);
        int typeStringsOff = u32(arsc, pkgOff + 268);
        StringPools typePool = null;
        if (typeStringsOff > 0) {
            try { typePool = StringPools.parse(arsc, pkgOff + typeStringsOff); } catch (RuntimeException ignored) { }
        }
        int headerSize = u16(arsc, pkgOff + 2);
        int pos = pkgOff + headerSize;
        int end = pkgOff + pkgSize;
        while (pos + 8 <= end) {
            int type = u16(arsc, pos);
            int size = u32(arsc, pos + 4);
            if (size <= 0) break;
            if (type == TYPE_TYPE) {
                int typeId = arsc[pos + 8] & 0xFF;
                int entryCount = u32(arsc, pos + 12);
                int entriesStart = u32(arsc, pos + 16);
                int typeHeaderSize = u16(arsc, pos + 2);
                String typeName = null;
                if (typePool != null && typeId - 1 < typePool.strings.size() && typeId >= 1) {
                    typeName = typePool.strings.get(typeId - 1);
                }
                if ("string".equals(typeName)) {
                    for (int resId : labelResIds) {
                        if (((resId >> 24) & 0xFF) != pkgId) continue;
                        if (((resId >> 16) & 0xFF) != typeId) continue;
                        int entryIdx = resId & 0xFFFF;
                        if (entryIdx >= entryCount) continue;
                        int eOff = u32(arsc, pos + typeHeaderSize + entryIdx * 4);
                        if (eOff == -1) continue;
                        int entryPos = pos + entriesStart + eOff;
                        int eFlags = u16(arsc, entryPos + 2);
                        if ((eFlags & 0x0001) != 0) continue; // complex entry (bag)
                        int dataType = arsc[entryPos + 8 + 3] & 0xFF;
                        int data = u32(arsc, entryPos + 8 + 4);
                        if (dataType == DATA_TYPE_STRING) outIdx.add(data);
                    }
                }
            }
            pos += size;
        }
    }

    private static final int DATA_TYPE_REFERENCE = 0x01;

    /**
     * Resolve resource ids (e.g. the app icon) to the file paths their
     * entries point at, across all configurations. File resources are
     * stored as string values holding the path ("res/mipmap-.../x.png").
     * One level of reference indirection (aliases) is followed.
     */
    public static Set<String> collectResourcePaths(byte[] arsc, String oldPackage, List<Integer> resIds) {
        Set<String> out = new LinkedHashSet<>();
        if (resIds == null || resIds.isEmpty()) return out;
        if (u16(arsc, 0) != TYPE_TABLE) return out;
        int pos = u16(arsc, 2);
        if (u16(arsc, pos) != TYPE_STRING_POOL) return out;
        int poolOff = pos;
        int poolSize = u32(arsc, poolOff + 4);
        StringPools pool = StringPools.parse(arsc, poolOff);

        Set<Integer> refs = new LinkedHashSet<>();
        collectEntryStrings(arsc, poolOff, poolSize, oldPackage, resIds, pool, out, refs);
        if (!refs.isEmpty()) {
            collectEntryStrings(arsc, poolOff, poolSize, oldPackage,
                    new java.util.ArrayList<>(refs), pool, out, new LinkedHashSet<>());
        }
        return out;
    }

    /** Like collectLabelStrings, but for any resource type: collects the string values (paths) of the given resIds. */
    private static void collectEntryStrings(byte[] arsc, int poolOff, int poolSize, String oldPackage,
                                            List<Integer> resIds, StringPools pool,
                                            Set<String> outPaths, Set<Integer> outRefs) {
        int pos = poolOff + poolSize;
        int fileEnd = arsc.length;
        while (pos + 8 <= fileEnd) {
            int type = u16(arsc, pos);
            int size = u32(arsc, pos + 4);
            if (size <= 0) break;
            if (type == TYPE_PACKAGE && oldPackage.equals(readPkgName(arsc, pos))) {
                int pkgId = u32(arsc, pos + 8);
                int headerSize = u16(arsc, pos + 2);
                int tPos = pos + headerSize;
                int end = pos + size;
                while (tPos + 8 <= end) {
                    int cType = u16(arsc, tPos);
                    int cSize = u32(arsc, tPos + 4);
                    if (cSize <= 0) break;
                    if (cType == TYPE_TYPE) {
                        int typeId = arsc[tPos + 8] & 0xFF;
                        int entryCount = u32(arsc, tPos + 12);
                        int entriesStart = u32(arsc, tPos + 16);
                        int typeHeaderSize = u16(arsc, tPos + 2);
                        for (int resId : resIds) {
                            if (((resId >> 24) & 0xFF) != pkgId) continue;
                            if (((resId >> 16) & 0xFF) != typeId) continue;
                            int entryIdx = resId & 0xFFFF;
                            if (entryIdx >= entryCount) continue;
                            int eOff = u32(arsc, tPos + typeHeaderSize + entryIdx * 4);
                            if (eOff == -1) continue;
                            int entryPos = tPos + entriesStart + eOff;
                            int eFlags = u16(arsc, entryPos + 2);
                            if ((eFlags & 0x0001) != 0) continue; // complex entry (bag)
                            int dataType = arsc[entryPos + 8 + 3] & 0xFF;
                            int data = u32(arsc, entryPos + 8 + 4);
                            if (dataType == DATA_TYPE_STRING) {
                                if (data >= 0 && data < pool.strings.size()) outPaths.add(pool.strings.get(data));
                            } else if (dataType == DATA_TYPE_REFERENCE) {
                                outRefs.add(data);
                            }
                        }
                    }
                    tPos += cSize;
                }
            }
            pos += size;
        }
    }
}
