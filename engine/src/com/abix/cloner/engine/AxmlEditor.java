package com.abix.cloner.engine;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.abix.cloner.engine.StringPools.*;

/**
 * Rewrites a binary AndroidManifest.xml for cloning:
 *  - manifest package -> new package
 *  - provider authorities prefixed with the old package -> new package prefix
 *  - app-defined permission names (old package prefix) -> new prefix
 *  - instrumentation targetPackage -> new package
 *  - literal app/activity labels get the suffix appended; reference labels are
 *    reported back so the ARSC editor can rename the string resource.
 * Class names (activity/service/receiver/provider android:name) are NOT touched:
 * the code package inside the dex stays valid.
 */
public final class AxmlEditor {
    private static final int TYPE_RES_XML = 0x0003;
    private static final int TYPE_START_ELEMENT = 0x0102;
    private static final int DATA_TYPE_STRING = 0x03;
    private static final int DATA_TYPE_REFERENCE = 0x01;

    public String oldPackage;
    public final List<Integer> labelResIds = new ArrayList<>();
    /** Resource id of the application android:icon reference, or -1. */
    public int iconResId = -1;
    /** Resource id of the application android:roundIcon reference, or -1. */
    public int roundIconResId = -1;
    private String labelOverride;

    private static final class AttrPatch {
        int chunkOff;      // offset of the start-element chunk in the ORIGINAL bytes
        int attrOff;       // offset of the attribute within the chunk
        int newStrIndex;   // index into the NEW string pool
    }

    public byte[] rewrite(byte[] axml, String newPackage, String labelSuffix) {
        return rewrite(axml, newPackage, labelSuffix, null);
    }

    /**
     * When labelOverride is non-null, app/activity labels are set to that
     * exact text (custom clone name) instead of original + suffix.
     */
    public byte[] rewrite(byte[] axml, String newPackage, String labelSuffix, String labelOverride) {
        this.labelOverride = labelOverride;
        if (u32(axml, 0) != 0x00080003) throw new IllegalArgumentException("not a binary XML file");
        // locate the string pool chunk (first chunk)
        int poolOff = 8;
        if (u16(axml, poolOff) != TYPE_STRING_POOL) throw new IllegalArgumentException("string pool not first chunk");
        int poolSize = u32(axml, poolOff + 4);
        StringPools pool = StringPools.parse(axml, poolOff);
        List<String> orig = new ArrayList<>(pool.strings);

        List<AttrPatch> patches = new ArrayList<>();

        // walk chunks after the pool
        int pos = poolOff + poolSize;
        while (pos + 8 <= axml.length) {
            int type = u16(axml, pos);
            int size = u32(axml, pos + 4);
            if (size <= 0) break;
            if (type == TYPE_START_ELEMENT) {
                int nameIdx = u32(axml, pos + 20);
                String el = str(orig, nameIdx);
                int attrStart = u16(axml, pos + 24);
                int attrSize = u16(axml, pos + 26);
                int attrCount = u16(axml, pos + 28);
                int attrsBase = pos + 16 + attrStart;
                for (int i = 0; i < attrCount; i++) {
                    int aOff = attrsBase + i * attrSize;
                    int aNameIdx = u32(axml, aOff + 4);
                    int rawIdx = u32(axml, aOff + 8);
                    int dataType = axml[aOff + 15] & 0xFF;
                    int data = u32(axml, aOff + 16);
                    String aName = str(orig, aNameIdx);
                    String value = null;
                    if (rawIdx != -1) value = str(orig, rawIdx);
                    else if (dataType == DATA_TYPE_STRING) value = str(orig, data);

                    String newValue = transform(el, aName, value, newPackage, labelSuffix);
                    if (dataType == DATA_TYPE_REFERENCE && "label".equals(aName)
                            && ("application".equals(el) || "activity".equals(el) || "activity-alias".equals(el))) {
                        labelResIds.add(data);
                    }
                    if (dataType == DATA_TYPE_REFERENCE && "application".equals(el)) {
                        if ("icon".equals(aName)) iconResId = data;
                        else if ("roundIcon".equals(aName)) roundIconResId = data;
                    }
                    if (newValue != null && !newValue.equals(value)) {
                        AttrPatch p = new AttrPatch();
                        p.chunkOff = pos;
                        p.attrOff = aOff - pos;
                        p.newStrIndex = pool.add(newValue);
                        patches.add(p);
                    }
                }
            }
            pos += size;
        }
        if (oldPackage == null) throw new IllegalArgumentException("manifest package attribute not found");

        // rebuild: header + new pool + remaining chunks (with attribute patches applied)
        byte[] newPool = pool.buildChunk(false); // UTF-16 pool
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[8];
        System.arraycopy(axml, 0, header, 0, 8);
        out.write(header, 0, 8);
        out.write(newPool, 0, newPool.length);
        pos = poolOff + poolSize;
        while (pos + 8 <= axml.length) {
            int size = u32(axml, pos + 4);
            if (size <= 0) break;
            byte[] chunk = new byte[size];
            System.arraycopy(axml, pos, chunk, 0, size);
            for (AttrPatch p : patches) {
                if (p.chunkOff == pos) {
                    put32(chunk, p.attrOff + 8, p.newStrIndex);   // rawValue
                    int dataType = chunk[p.attrOff + 15] & 0xFF;
                    if (dataType == DATA_TYPE_STRING) put32(chunk, p.attrOff + 16, p.newStrIndex); // typed data
                }
            }
            out.write(chunk, 0, size);
            pos += size;
        }
        byte[] result = out.toByteArray();
        put32(result, 4, result.length);
        return result;
    }

    private String transform(String el, String attr, String value, String newPkg, String suffix) {
        if (value == null || attr == null) return null;
        if ("manifest".equals(el) && "package".equals(attr)) {
            oldPackage = value;
            return newPkg;
        }
        if (oldPackage == null) return null; // package attr comes first in practice
        if ("authorities".equals(attr)) {
            String[] toks = value.split(";");
            boolean changed = false;
            for (int i = 0; i < toks.length; i++) {
                if (toks[i].startsWith(oldPackage)) {
                    toks[i] = newPkg + toks[i].substring(oldPackage.length());
                    changed = true;
                }
            }
            return changed ? String.join(";", toks) : null;
        }
        if (("permission".equals(el) || "uses-permission".equals(el) || "permission-group".equals(el) || "permission-tree".equals(el))
                && "name".equals(attr) && value.startsWith(oldPackage + ".")) {
            return newPkg + value.substring(oldPackage.length());
        }
        if (("original-package".equals(el) || "adopt-permissions".equals(el)) && "name".equals(attr) && value.equals(oldPackage)) {
            return newPkg;
        }
        if ("instrumentation".equals(el) && "targetPackage".equals(attr) && value.equals(oldPackage)) {
            return newPkg;
        }
        if ("label".equals(attr) && ("application".equals(el) || "activity".equals(el) || "activity-alias".equals(el))) {
            if (labelOverride != null) {
                return value.equals(labelOverride) ? null : labelOverride;
            }
            if (value.endsWith(suffix)) return null;
            return value + suffix;
        }
        return null;
    }

    private static String str(List<String> pool, int idx) {
        if (idx < 0 || idx >= pool.size()) return null;
        return pool.get(idx);
    }
}
