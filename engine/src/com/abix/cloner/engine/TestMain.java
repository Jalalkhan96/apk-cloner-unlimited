package com.abix.cloner.engine;

import java.io.File;

public final class TestMain {
    public static void main(String[] args) throws Exception {
        // v1.2 mode: --named <base> <out> <newPkg> <labelOverride|-> <iconPngPath|->
        if (args.length > 0 && args[0].equals("--named")) {
            File base = new File(args[1]);
            File out = new File(args[2]);
            String newPkg = args[3];
            CloneEngine.Options opts = new CloneEngine.Options();
            if (!"-".equals(args[4])) opts.labelOverride = args[4];
            if (!"-".equals(args[5])) opts.iconPng = java.nio.file.Files.readAllBytes(new File(args[5]).toPath());
            CloneEngine.Report r = new CloneEngine().cloneApk(base, out, newPkg, " Clone", opts);
            print(r);
            return;
        }
        // merged mode: --merged <base> <split1,split2> <out> <newPkg>
        if (args.length > 0 && args[0].equals("--merged")) {
            File base = new File(args[1]);
            String[] parts = args[2].split(",");
            File[] splits = new File[parts.length];
            for (int i = 0; i < parts.length; i++) splits[i] = new File(parts[i]);
            File out = new File(args[3]);
            String newPkg = args.length > 4 ? args[4] : null;
            CloneEngine.Report r = new CloneEngine().cloneMerged(base, splits, out, newPkg, " Clone");
            print(r);
            return;
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        String newPkg = args.length > 2 ? args[2] : null;
        CloneEngine.Report r = new CloneEngine().cloneApk(in, out, newPkg, " Clone");
        print(r);
    }

    private static void print(CloneEngine.Report r) {
        System.out.println("oldPackage=" + r.oldPackage);
        System.out.println("newPackage=" + r.newPackage);
        System.out.println("labelStringsPatched=" + r.labelStringsPatched);
        System.out.println("entriesWritten=" + r.entriesWritten);
        System.out.println("outSize=" + r.outSize);
        System.out.println("splitsMerged=" + r.splitsMerged);
        System.out.println("splitEntriesAdded=" + r.splitEntriesAdded);
        System.out.println("dexFilesTotal=" + r.dexFilesTotal);
        System.out.println("iconEntriesReplaced=" + r.iconEntriesReplaced);
    }
}
