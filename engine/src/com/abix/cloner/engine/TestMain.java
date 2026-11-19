package com.abix.cloner.engine;

import java.io.File;

public final class TestMain {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        String newPkg = args.length > 2 ? args[2] : null;
        CloneEngine.Report r = new CloneEngine().cloneApk(in, out, newPkg, " Clone");
        System.out.println("oldPackage=" + r.oldPackage);
        System.out.println("newPackage=" + r.newPackage);
        System.out.println("labelStringsPatched=" + r.labelStringsPatched);
        System.out.println("entriesWritten=" + r.entriesWritten);
        System.out.println("outSize=" + r.outSize);
    }
}
