package com.abix.cloner.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The clone engine: takes an APK, rewrites its identity (package + label),
 * strips the old signature files and produces an unsigned, aligned APK
 * ready to be signed with the cloner's own key.
 * Pure Java — runs both on a desktop JVM (tests) and inside the Android app.
 */
public final class CloneEngine {
    public static final class Report {
        public String oldPackage;
        public String newPackage;
        public int labelStringsPatched;
        public int entriesWritten;
        public long outSize;
        public int splitsMerged;
        public int splitEntriesAdded;
        public int dexFilesTotal;
        public int iconEntriesReplaced;
    }

    /** Optional clone customisation (v1.2). */
    public static final class Options {
        /** Exact label for the clone (e.g. "WhatsApp Clone 1" or a custom name); null = original + suffix. */
        public String labelOverride;
        /** PNG bytes to write over the app's launcher icon files; null = keep the original icon. */
        public byte[] iconPng;
    }

    /** Clone a split-installed app: base APK identity rewrite + split parts merged in. */
    public Report cloneMerged(File baseApk, File[] splitApks, File outApk, String newPackage, String labelSuffix) throws IOException {
        return cloneInternal(baseApk, splitApks, outApk, newPackage, labelSuffix, null);
    }

    public Report cloneMerged(File baseApk, File[] splitApks, File outApk, String newPackage, String labelSuffix, Options opts) throws IOException {
        return cloneInternal(baseApk, splitApks, outApk, newPackage, labelSuffix, opts);
    }

    public Report cloneApk(File inApk, File outApk, String newPackage, String labelSuffix) throws IOException {
        return cloneInternal(inApk, null, outApk, newPackage, labelSuffix, null);
    }

    public Report cloneApk(File inApk, File outApk, String newPackage, String labelSuffix, Options opts) throws IOException {
        return cloneInternal(inApk, null, outApk, newPackage, labelSuffix, opts);
    }

    private Report cloneInternal(File inApk, File[] splitApks, File outApk, String newPackage, String labelSuffix, Options opts) throws IOException {
        if (labelSuffix == null) labelSuffix = " Clone";
        byte[] manifest = null;
        byte[] arsc = null;
        // first pass: read manifest + arsc
        try (ZipFile zf = new ZipFile(inApk)) {
            ZipEntry m = zf.getEntry("AndroidManifest.xml");
            if (m == null) throw new IOException("AndroidManifest.xml missing");
            manifest = readAll(zf.getInputStream(m));
            ZipEntry r = zf.getEntry("resources.arsc");
            if (r != null) arsc = readAll(zf.getInputStream(r));
        }

        AxmlEditor axml = new AxmlEditor();
        // oldPackage is only known after a first rewrite pass; do a probe pass:
        // rewrite() captures oldPackage from the manifest itself. We need the new
        // package BEFORE rewriting, so probe with a throwaway rewrite first.
        AxmlEditor probe = new AxmlEditor();
        try {
            probe.rewrite(manifest, "probe.invalid", "");
        } catch (RuntimeException ignored) { }
        String oldPackage = probe.oldPackage;
        if (oldPackage == null) throw new IOException("could not read original package name");
        if (newPackage == null || newPackage.isEmpty()) newPackage = oldPackage + ".clone";

        String labelOverride = opts != null ? opts.labelOverride : null;
        byte[] newManifest = axml.rewrite(manifest, newPackage, labelSuffix, labelOverride);
        byte[] newArsc = null;
        int labelPatched = 0;
        // Resolve the launcher icon's file paths so a custom icon can be
        // written over exactly those entries during the copy pass.
        java.util.Set<String> iconPaths = new java.util.HashSet<>();
        boolean adaptiveIcon = false;
        if (arsc != null) {
            ArscEditor arscEditor = new ArscEditor();
            newArsc = arscEditor.rewrite(arsc, oldPackage, newPackage, axml.labelResIds, labelSuffix, labelOverride);
            labelPatched = arscEditor.labelStringsPatched;
            if (opts != null && opts.iconPng != null) {
                java.util.List<Integer> iconIds = new java.util.ArrayList<>();
                if (axml.iconResId != -1) iconIds.add(axml.iconResId);
                if (axml.roundIconResId != -1) iconIds.add(axml.roundIconResId);
                if (!iconIds.isEmpty()) {
                    iconPaths = ArscEditor.collectResourcePaths(arsc, oldPackage, iconIds);
                    for (String p : iconPaths) {
                        if (p.endsWith(".xml")) { adaptiveIcon = true; break; }
                    }
                }
            }
        }
        final boolean adaptiveIconFinal = adaptiveIcon;
        final java.util.Set<String> iconReplaced = new java.util.HashSet<>();

        // second pass: copy everything else, minus old signature files
        ZipWriter zw = new ZipWriter();
        int count = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<Integer> dexUsed = new java.util.HashSet<>();
        try (ZipFile zf = new ZipFile(inApk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (isSignatureFile(name)) continue;
                byte[] data;
                boolean stored = e.getMethod() == ZipEntry.STORED;
                if (name.equals("AndroidManifest.xml")) {
                    data = newManifest;
                } else if (name.equals("resources.arsc") && newArsc != null) {
                    data = newArsc;
                } else if (opts != null && opts.iconPng != null
                        && (iconPaths.contains(name)
                            || (adaptiveIconFinal && isLauncherIconBitmap(name)))) {
                    // custom icon written over the original icon file
                    data = opts.iconPng;
                    iconReplaced.add(name);
                } else {
                    data = readAll(zf.getInputStream(e));
                }
                zw.add(new ZipWriter.Entry(name, data, stored));
                names.add(name);
                int di = dexIndex(name);
                if (di > 0) dexUsed.add(di);
                count++;
            }
        }

        // split merge: fold every split APK's unique parts into the copy.
        // Split manifests and resource tables are skipped (the base APK's
        // rewritten ones rule); split code is renumbered in as extra
        // classesN.dex, native libs and assets are added when missing.
        int splitsMerged = 0;
        int splitEntriesAdded = 0;
        if (splitApks != null) {
            for (File split : splitApks) {
                if (split == null || !split.exists()) continue;
                splitsMerged++;
                try (ZipFile zf = new ZipFile(split)) {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String name = e.getName();
                        if (e.isDirectory()) continue;
                        if (isSignatureFile(name)) continue;
                        if (name.equals("AndroidManifest.xml")) continue;
                        if (name.equals("resources.arsc")) continue;
                        int di = dexIndex(name);
                        if (di > 0) {
                            if (dexUsed.contains(di)) {
                                int free = 2;
                                while (dexUsed.contains(free)) free++;
                                di = free;
                            }
                            dexUsed.add(di);
                            String newName = di == 1 ? "classes.dex" : "classes" + di + ".dex";
                            byte[] data = readAll(zf.getInputStream(e));
                            zw.add(new ZipWriter.Entry(newName, data, false));
                            names.add(newName);
                            splitEntriesAdded++;
                            count++;
                            continue;
                        }
                        if (names.contains(name)) continue;
                        byte[] data = readAll(zf.getInputStream(e));
                        boolean stored = e.getMethod() == ZipEntry.STORED || name.endsWith(".so");
                        zw.add(new ZipWriter.Entry(name, data, stored));
                        names.add(name);
                        splitEntriesAdded++;
                        count++;
                    }
                }
            }
        }
        byte[] out = zw.build();
        try (FileOutputStream fos = new FileOutputStream(outApk)) {
            fos.write(out);
        }
        Report r = new Report();
        r.oldPackage = oldPackage;
        r.newPackage = newPackage;
        r.labelStringsPatched = labelPatched;
        r.entriesWritten = count;
        r.outSize = out.length;
        r.splitsMerged = splitsMerged;
        r.splitEntriesAdded = splitEntriesAdded;
        r.dexFilesTotal = dexUsed.size();
        r.iconEntriesReplaced = iconReplaced.size();
        return r;
    }

    /** Launcher icon bitmap files (used for adaptive-icon APKs, whose icon resource is an XML wrapper). */
    private static boolean isLauncherIconBitmap(String name) {
        if (!name.startsWith("res/")) return false;
        String file = name.substring(name.lastIndexOf('/') + 1);
        if (!file.startsWith("ic_launcher")) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /** classes.dex -> 1, classes2.dex -> 2, anything else -> -1. */
    private static int dexIndex(String name) {
        if (name.equals("classes.dex")) return 1;
        if (name.startsWith("classes") && name.endsWith(".dex")) {
            String mid = name.substring(7, name.length() - 4);
            try {
                int n = Integer.parseInt(mid);
                return n >= 2 ? n : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String rest = name.substring("META-INF/".length());
        if (rest.contains("/")) return false;
        String up = rest.toUpperCase();
        return up.equals("MANIFEST.MF") || up.endsWith(".SF") || up.endsWith(".RSA")
                || up.endsWith(".DSA") || up.endsWith(".EC");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
