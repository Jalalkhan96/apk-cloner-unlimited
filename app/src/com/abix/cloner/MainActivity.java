package com.abix.cloner;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.abix.cloner.engine.CloneEngine;
import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final String KS_PASS = "AbixClone#2026";

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    private AppAdapter adapter;
    private CheckBox systemCheck;
    private EditText searchBox;

    private static final class AppEntry {
        String label;
        String pkg;
        Drawable icon;
        ApplicationInfo info;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("ABIX Cloner");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, 4);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Kisi bhi app ki copy banao — copy home screen par alag app ban kar lagegi. No ads, no tracking.");
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, 12);
        root.addView(sub);

        searchBox = new EditText(this);
        searchBox.setHint("App search karo...");
        searchBox.setSingleLine(true);
        root.addView(searchBox);

        systemCheck = new CheckBox(this);
        systemCheck.setText("System apps bhi dikhao");
        root.addView(systemCheck);

        ListView list = new ListView(this);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
        systemCheck.setOnCheckedChangeListener((v, checked) -> applyFilter());
        list.setOnItemClickListener((parent, view, position, id) -> confirmClone(shownApps.get(position)));

        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            List<AppEntry> loaded = new ArrayList<>();
            for (ApplicationInfo ai : installed) {
                if (ai.packageName.equals(getPackageName())) continue;
                AppEntry e = new AppEntry();
                e.pkg = ai.packageName;
                e.info = ai;
                CharSequence label = ai.loadLabel(pm);
                e.label = label != null ? label.toString() : ai.packageName;
                try { e.icon = ai.loadIcon(pm); } catch (RuntimeException ignored) { }
                loaded.add(e);
            }
            Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(loaded);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        String q = searchBox.getText().toString().toLowerCase().trim();
        boolean showSystem = systemCheck.isChecked();
        shownApps.clear();
        for (AppEntry e : allApps) {
            boolean isSystem = (e.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem && !showSystem) continue;
            if (!q.isEmpty() && !e.label.toLowerCase().contains(q) && !e.pkg.toLowerCase().contains(q)) continue;
            shownApps.add(e);
        }
        adapter.notifyDataSetChanged();
    }

    private String nextClonePackage(String pkg) {
        PackageManager pm = getPackageManager();
        String candidate = pkg + ".clone";
        int n = 2;
        while (true) {
            try {
                pm.getPackageInfo(candidate, 0);
                candidate = pkg + ".clone" + n;
                n++;
            } catch (PackageManager.NameNotFoundException e) {
                return candidate;
            }
        }
    }

    private void confirmClone(AppEntry entry) {
        String newPkg = nextClonePackage(entry.pkg);
        boolean hasSplits = entry.info.splitSourceDirs != null && entry.info.splitSourceDirs.length > 0;
        String msg = "App: " + entry.label + "\n"
                + "Copy ka naam: " + entry.label + " Clone\n"
                + "Copy ka package: " + newPkg + "\n\n"
                + "Copy alag app ban kar install hogi — home screen par is ka apna icon hoga aur apna data hoga."
                + (hasSplits ? "\n\nNote: ye app split APK hai — copy adhoori ho sakti hai." : "");
        new AlertDialog.Builder(this)
                .setTitle("Clone banayein?")
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clone banao", (d, w) -> doClone(entry, newPkg))
                .show();
    }

    private void doClone(AppEntry entry, String newPkg) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Clone ban raha hai: " + entry.label + " ...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                File clonesDir = new File(getCacheDir(), "clones");
                if (!clonesDir.exists()) clonesDir.mkdirs();
                File unsigned = new File(clonesDir, "unsigned.apk");
                File signed = new File(clonesDir, newPkg + ".apk");

                CloneEngine.Report report = new CloneEngine().cloneApk(
                        new File(entry.info.publicSourceDir), unsigned, newPkg, " Clone");
                signApk(unsigned, signed, entry.info.minSdkVersion);
                unsigned.delete();

                runOnUiThread(() -> {
                    progress.dismiss();
                    installClone(signed, report);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Clone nahi bana")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }).start();
    }

    private void signApk(File unsigned, File signed, int minSdk) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getAssets().open("cloner.p12")) {
            ks.load(in, KS_PASS.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("cloner", KS_PASS.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("cloner");
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "ABIX Cloner", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(Math.max(23, minSdk))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    private void installClone(File apk, CloneEngine.Report report) {
        Uri uri = Uri.parse("content://com.abix.cloner.fileprovider/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            Toast.makeText(this, "Clone tayyar: " + report.newPackage, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Install nahi khula")
                    .setMessage("Clone file ban gayi hai lekin installer nahi khula: " + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView icon;
            LinearLayout texts;
            TextView name;
            TextView pkg;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(8, 12, 8, 12);
                icon = new ImageView(MainActivity.this);
                icon.setLayoutParams(new LinearLayout.LayoutParams(96, 96));
                row.addView(icon);
                texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(20, 0, 0, 0);
                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                pkg = new TextView(MainActivity.this);
                pkg.setTextSize(12);
                texts.addView(name);
                texts.addView(pkg);
                row.addView(texts);
                row.setTag(new Object[]{icon, name, pkg});
            } else {
                row = (LinearLayout) convertView;
                Object[] holders = (Object[]) row.getTag();
                icon = (ImageView) holders[0];
                name = (TextView) holders[1];
                pkg = (TextView) holders[2];
            }
            AppEntry e = shownApps.get(position);
            icon.setImageDrawable(e.icon);
            name.setText(e.label);
            pkg.setText(e.pkg);
            return row;
        }
    }
}
