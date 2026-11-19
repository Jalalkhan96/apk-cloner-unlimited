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
    }

    public Report cloneApk(File inApk, File outApk, String newPackage, String labelSuffix) throws IOException {
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

        byte[] newManifest = axml.rewrite(manifest, newPackage, labelSuffix);
        byte[] newArsc = null;
        int labelPatched = 0;
        if (arsc != null) {
            ArscEditor arscEditor = new ArscEditor();
            newArsc = arscEditor.rewrite(arsc, oldPackage, newPackage, axml.labelResIds, labelSuffix);
            labelPatched = arscEditor.labelStringsPatched;
        }

        // second pass: copy everything else, minus old signature files
        ZipWriter zw = new ZipWriter();
        int count = 0;
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
                } else {
                    data = readAll(zf.getInputStream(e));
                }
                zw.add(new ZipWriter.Entry(name, data, stored));
                count++;
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
        return r;
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
