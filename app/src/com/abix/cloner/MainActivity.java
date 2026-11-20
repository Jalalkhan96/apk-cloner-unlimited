package com.abix.cloner;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.abix.cloner.engine.CloneEngine;
import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
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
    private static final int REQ_ICON = 42;

    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    private AppAdapter adapter;
    private CheckBox systemCheck;
    private EditText searchBox;

    // Clone-dialog state, kept across the icon-picker round trip.
    private AppEntry pendingEntry;
    private String pendingPkg;
    private String pendingName;
    private byte[] pendingIconPng;
    private Bitmap pendingIconBitmap;
    private ImageView pendingIconPreview;

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
        sub.setText("Clone any app — the copy installs as a separate app with its own data.\nKisi bhi app ki copy banao — alag app, alag data. No ads, no tracking.");
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, 12);
        root.addView(sub);

        searchBox = new EditText(this);
        searchBox.setHint("Search apps... (app dhoondo)");
        searchBox.setSingleLine(true);
        root.addView(searchBox);

        systemCheck = new CheckBox(this);
        systemCheck.setText("Show system apps (system apps bhi dikhao)");
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
        list.setOnItemClickListener((parent, view, position, id) -> {
            clearPending();
            confirmClone(shownApps.get(position));
        });

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

    /** Clone number for a clone package: pkg.clone -> 1, pkg.clone2 -> 2, ... */
    private static int cloneNumber(String pkg, String newPkg) {
        String suffix = newPkg.substring(pkg.length());
        if (suffix.equals(".clone")) return 1;
        try {
            return Integer.parseInt(suffix.substring(".clone".length()));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** Apps that protect themselves with signature / integrity checks. */
    private static boolean isProtectedApp(String pkg) {
        return pkg.equals("com.whatsapp")
                || pkg.equals("com.whatsapp.w4b")
                || pkg.equals("com.zhiliaoapp.musically")
                || pkg.equals("com.ss.android.ugc.trill");
    }

    private void clearPending() {
        pendingEntry = null;
        pendingPkg = null;
        pendingName = null;
        pendingIconPng = null;
        pendingIconBitmap = null;
        pendingIconPreview = null;
    }

    private void confirmClone(AppEntry entry) {
        final String newPkg = (pendingEntry == entry && pendingPkg != null)
                ? pendingPkg : nextClonePackage(entry.pkg);
        final int number = cloneNumber(entry.pkg, newPkg);
        final String defaultName = entry.label + " Clone " + number;
        boolean hasSplits = entry.info.splitSourceDirs != null && entry.info.splitSourceDirs.length > 0;

        String info = "App: " + entry.label + "\n"
                + "Clone package: " + newPkg + "\n\n"
                + "The clone installs as its own app — its own home-screen icon and its own data.\n"
                + "Copy alag app ban kar install hogi — apna icon, apna data."
                + (hasSplits ? "\n\nThis app is installed in split parts — all parts will be merged into the clone automatically.\nYe app split parts mein hai — saaray parts copy mein shamil kar diye jayenge." : "")
                + (isProtectedApp(entry.pkg)
                    ? "\n\n⚠ Protected app: this app checks its official signature, so the clone is created in full, but the app itself may refuse to run or sign in. That block is the app's own protection — no cloner can remove it.\nYe app apni hifazat khud karti hai — copy poori banegi, lekin app khud chalne ya sign-in se inkaar kar sakti hai."
                    : "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 8);

        TextView infoView = new TextView(this);
        infoView.setText(info);
        box.addView(infoView);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("\nClone name (clone ka naam):");
        box.addView(nameLabel);

        final EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setText(pendingEntry == entry && pendingName != null ? pendingName : defaultName);
        nameField.setSelection(nameField.getText().length());
        box.addView(nameField);

        TextView iconLabel = new TextView(this);
        iconLabel.setText("\nClone icon (clone ka icon):");
        box.addView(iconLabel);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        final ImageView preview = new ImageView(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(120, 120));
        if (pendingEntry == entry && pendingIconBitmap != null) preview.setImageBitmap(pendingIconBitmap);
        else preview.setImageDrawable(entry.icon);
        iconRow.addView(preview);
        Button pick = new Button(this);
        pick.setText("Choose custom icon\n(apna icon chuno)");
        iconRow.addView(pick);
        box.addView(iconRow);
        pendingIconPreview = preview;

        pick.setOnClickListener(v -> {
            pendingEntry = entry;
            pendingPkg = newPkg;
            pendingName = nameField.getText().toString();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, REQ_ICON);
            } catch (RuntimeException e) {
                Toast.makeText(this, "No image picker found (image picker nahi mila)", Toast.LENGTH_LONG).show();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Create clone?")
                .setView(box)
                .setNegativeButton("Cancel", (d, w) -> clearPending())
                .setPositiveButton("Create Clone", (d, w) -> {
                    String name = nameField.getText().toString().trim();
                    if (name.isEmpty()) name = defaultName;
                    CloneEngine.Options opts = new CloneEngine.Options();
                    opts.labelOverride = name;
                    opts.iconPng = (pendingEntry == entry) ? pendingIconPng : null;
                    clearPending();
                    doClone(entry, newPkg, opts, name);
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ICON || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            Bitmap decoded;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                decoded = BitmapFactory.decodeStream(in);
            }
            if (decoded == null) throw new IllegalStateException("image could not be read");
            Bitmap scaled = Bitmap.createScaledBitmap(decoded, 512, 512, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            pendingIconPng = out.toByteArray();
            pendingIconBitmap = scaled;
            if (pendingIconPreview != null) pendingIconPreview.setImageBitmap(scaled);
            Toast.makeText(this, "Custom icon set (icon lag gaya) — now tap Create Clone", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Icon failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void doClone(AppEntry entry, String newPkg, CloneEngine.Options opts, String cloneName) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Creating clone: " + cloneName + " ...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                File clonesDir = new File(getCacheDir(), "clones");
                if (!clonesDir.exists()) clonesDir.mkdirs();
                File unsigned = new File(clonesDir, "unsigned.apk");
                File signed = new File(clonesDir, newPkg + ".apk");

                CloneEngine.Report report;
                String[] splitDirs = entry.info.splitSourceDirs;
                if (splitDirs != null && splitDirs.length > 0) {
                    File[] splits = new File[splitDirs.length];
                    for (int i = 0; i < splitDirs.length; i++) splits[i] = new File(splitDirs[i]);
                    report = new CloneEngine().cloneMerged(
                            new File(entry.info.publicSourceDir), splits, unsigned, newPkg, " Clone", opts);
                } else {
                    report = new CloneEngine().cloneApk(
                            new File(entry.info.publicSourceDir), unsigned, newPkg, " Clone", opts);
                }
                signApk(unsigned, signed, entry.info.minSdkVersion);
                unsigned.delete();

                runOnUiThread(() -> {
                    progress.dismiss();
                    installClone(signed, report, cloneName);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Clone failed (clone nahi bana)")
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

    private void installClone(File apk, CloneEngine.Report report, String cloneName) {
        Uri uri = Uri.parse("content://com.abix.cloner.fileprovider/" + apk.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            String toast = "Clone ready: " + cloneName
                    + (report.splitsMerged > 0
                        ? " (" + report.splitsMerged + " split parts merged)"
                        : "")
                    + (report.iconEntriesReplaced > 0
                        ? " (custom icon applied)"
                        : "");
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Installer did not open")
                    .setMessage("The clone file was created but the installer did not open: " + e.getMessage())
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
