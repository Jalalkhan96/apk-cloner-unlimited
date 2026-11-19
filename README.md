# ABIX Cloner — Unlimited App Cloner for Android

**Clone any app on your phone, as many times as you want.**
Every copy is a real, separate app — its own home-screen icon, its own data.
**No ads. No tracking. No internet permission.**

📥 **Download the ready APK:** see the [Releases](https://github.com/Jalalkhan96/apk-cloner-unlimited/releases) section (or grab `ABIX-Cloner.apk` from this repo).

⭐ **Please star the repo** if you find it useful — it helps more people discover it!

---

## ⭐ The only limit is your storage

There is **no clone limit** in this app:

- 1st copy of an app → `app.clone`
- 2nd copy → `app.clone2`
- 3rd copy → `app.clone3` … and so on, forever.

**You can make copies as many as you want.** The only limit is the **storage of your device** — every copy takes about the same space as the original app.

## How to use

1. Install `ABIX-Cloner.apk` (allow "install unknown apps" once).
2. Open **ABIX Cloner** — you get a list of your installed apps.
3. Tap any app → confirm → the copy is built and the installer opens.
4. Install it — the copy appears on your home screen like any normal app.

## How it works

ABIX Cloner uses the classic repackaging technique (no virtual container, no root):

1. Reads the original app's APK.
2. Rewrites its identity: new package name (`….clone`, `….clone2`, …), renamed provider authorities and custom permissions, and a new display name — **"App Name Clone"**.
3. Strips the old signature and re-signs the copy with the cloner's own key.
4. Hands the finished APK to the Android installer.

Because the copy is a genuinely separate package, Android treats it as a different app: separate data, separate accounts, separate icon.

## Honest limits

These are limits of the repackaging technique itself (paid cloners have them too):

- Apps that verify their own signature, use Play licensing / Play Integrity, or bind accounts to the app signature (banking apps, Google apps, WhatsApp, …) may refuse to run as clones.
- Split-APK apps: only the base APK is cloned in v1 (the app warns you when this is the case).
- Clones don't get Play Store updates — just re-clone the updated original.
- Very large apps (100 MB+) are processed in memory; low-RAM phones may struggle.

## Build from source

No Gradle, no Android Studio needed — just a JDK/JRE 17+ and the Android SDK build-tools (35) + platform (android-35):

```bash
export JAVA_HOME=/path/to/jdk-or-jre-21
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

The script downloads the two build libraries it needs (Eclipse ECJ compiler + Google's apksig), compiles the app and the clone engine, dexes, aligns and signs → `ABIX-Cloner.apk`.

If you build it yourself, `build.sh` generates **your own** signing key on first run. Release APKs in this repo are signed by **ABIX**.

## 🤝 Open source — let's collab!

This project is **open source (MIT)**. Want to update the APK, fix a bug, or add a feature?

- Fork it, open a pull request, or start an issue/discussion.
- Ideas welcome: split-APK support, icon changer for clones, batch cloning, and more.

**Let's collab — contributions are welcome.**

## Project layout

```
app/      the Android app (app list → clone → install), no dependencies
engine/   the clone engine in pure Java (also runs on a desktop JVM for tests)
build.sh  full CLI build: aapt2 + ecj + d8 + zipalign + apksigner
```

## License

MIT — see [LICENSE](LICENSE).

---

**Signed by ABIX** ✍️
Built by Jalal Khan (ABIX Lab) — if you ship something with this code, a credit/link back is appreciated.
