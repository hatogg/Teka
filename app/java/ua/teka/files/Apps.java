package ua.teka.files;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Застосунки: список, відомості про APK, резервні копії APK, встановлення APK і наборів APKS/XAPK. */
final class Apps {
    private Apps() {}

    interface Listener { void onInstall(String json); }
    static volatile Listener listener;

    static long code(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    static long apkSize(ApplicationInfo ai) {
        long n = new File(ai.sourceDir).length();
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) n += new File(s).length();
        return n;
    }

    @SuppressWarnings("deprecation")
    static JSONArray list(Context c, Ops.Job j) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> ps = pm.getInstalledPackages(0);
        j.filesTotal = ps.size();
        JSONArray out = new JSONArray();
        for (PackageInfo pi : ps) {
            j.check();
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean launch = pm.getLaunchIntentForPackage(pi.packageName) != null;
            if (system && !updated && !launch) continue; // службові частини системи без значка
            out.put(new JSONObject()
                .put("pkg", pi.packageName)
                .put("label", String.valueOf(ai.loadLabel(pm)))
                .put("version", pi.versionName == null ? "" : pi.versionName)
                .put("code", code(pi))
                .put("system", system)
                .put("updated", updated)
                .put("launch", launch)
                .put("size", apkSize(ai))
                .put("splits", ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length)
                .put("installed", pi.firstInstallTime)
                .put("modified", pi.lastUpdateTime)
                .put("target", ai.targetSdkVersion)
                .put("apk", ai.sourceDir));
            j.files++;
            j.progress(false);
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    static JSONObject apkInfo(Context c, File f) throws Exception {
        PackageManager pm = c.getPackageManager();
        JSONObject o = new JSONObject();
        String ext = Names.ext(f.getName());
        if (!"apk".equals(ext)) {
            // набір APK: показуємо відомості з base.apk
            try (ZipFile z = new ZipFile(f)) {
                int n = 0;
                ZipEntry base = null;
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String nm = e.getName().toLowerCase(Locale.ROOT);
                    if (!nm.endsWith(".apk")) continue;
                    n++;
                    if (base == null || nm.endsWith("base.apk") || (!base.getName().toLowerCase(Locale.ROOT).endsWith("base.apk") && !nm.contains("config.") && !nm.startsWith("split"))) base = e;
                }
                o.put("parts", n);
                if (base == null) return o.put("ok", false).put("err", "У наборі немає APK");
                File tmp = new File(c.getCacheDir(), "apkinfo.apk");
                try (InputStream in = z.getInputStream(base); OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                JSONObject inner = apkInfo(c, tmp);
                tmp.delete();
                inner.put("parts", n).put("bundle", true);
                return inner;
            }
        }
        PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_PERMISSIONS);
        if (pi == null || pi.applicationInfo == null) return o.put("ok", false).put("err", "Пошкоджений або непідтримуваний APK");
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = f.getAbsolutePath();
        ai.publicSourceDir = f.getAbsolutePath();
        o.put("ok", true)
            .put("pkg", pi.packageName)
            .put("label", String.valueOf(ai.loadLabel(pm)))
            .put("version", pi.versionName == null ? "" : pi.versionName)
            .put("code", code(pi))
            .put("target", ai.targetSdkVersion);
        if (Build.VERSION.SDK_INT >= 24) o.put("min", ai.minSdkVersion);
        JSONArray perms = new JSONArray();
        if (pi.requestedPermissions != null) for (String p : pi.requestedPermissions) perms.put(p);
        o.put("perms", perms);
        try {
            PackageInfo cur = pm.getPackageInfo(pi.packageName, 0);
            long a = code(pi), b = code(cur);
            o.put("installedVersion", cur.versionName == null ? "" : cur.versionName)
                .put("installedCode", b)
                .put("status", a > b ? "newer" : a < b ? "older" : "same");
        } catch (PackageManager.NameNotFoundException e) {
            o.put("status", "new");
        }
        return o;
    }

    // ------------------------------------------------------------ резервні копії

    static File backupDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Тека/APK");
        d.mkdirs();
        return d;
    }

    static String safe(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    }

    static void backup(Context c, Ops.Job j, JSONObject p, JSONObject res) throws Exception {
        PackageManager pm = c.getPackageManager();
        JSONArray pkgs = p.getJSONArray("pkgs");
        File dir = p.has("dest") ? new File(p.getString("dest")) : backupDir();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Немає доступу до папки " + dir.getName());
        List<ApplicationInfo> ais = new ArrayList<>();
        List<PackageInfo> pis = new ArrayList<>();
        for (int i = 0; i < pkgs.length(); i++) {
            PackageInfo pi = pm.getPackageInfo(pkgs.getString(i), 0);
            pis.add(pi);
            ais.add(pi.applicationInfo);
            j.total += apkSize(pi.applicationInfo);
        }
        j.filesTotal = pis.size();
        j.progress(true);
        JSONArray out = new JSONArray();
        for (int i = 0; i < pis.size(); i++) {
            j.check();
            PackageInfo pi = pis.get(i);
            ApplicationInfo ai = ais.get(i);
            String base = safe(String.valueOf(ai.loadLabel(pm))) + "_" + safe(pi.versionName == null ? String.valueOf(code(pi)) : pi.versionName);
            j.cur = base;
            File t;
            if (ai.splitSourceDirs == null || ai.splitSourceDirs.length == 0) {
                t = new File(dir, Names.unique(dir, base + ".apk"));
                j.copyFile(new File(ai.sourceDir), t);
            } else {
                // застосунок із частинами: збираємо набір .apks (ZIP з усіма APK)
                List<File> parts = new ArrayList<>();
                parts.add(new File(ai.sourceDir));
                for (String s : ai.splitSourceDirs) parts.add(new File(s));
                t = new File(dir, Names.unique(dir, base + ".apks"));
                j.base = j.done;
                Archive.zip(parts, t, 0, j);
                j.base = 0;
                j.files++;
                j.touched.add(t.getAbsolutePath());
            }
            out.put(t.getAbsolutePath());
        }
        res.put("paths", out).put("dir", dir.getAbsolutePath());
    }

    // ------------------------------------------------------------ встановлення наборів APK

    static final String ACTION_INSTALL = "ua.teka.files.INSTALL_STATUS";

    /** Встановлює .apks / .xapk / .apkm (ZIP із кількома APK) через PackageInstaller. */
    static void installBundle(Context c, Ops.Job j, JSONObject p, JSONObject res) throws Exception {
        File f = new File(p.getString("path"));
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sid = pi.createSession(sp);
        boolean ok = false;
        try (ZipFile z = new ZipFile(f); PackageInstaller.Session s = pi.openSession(sid)) {
            List<ZipEntry> apks = new ArrayList<>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    apks.add(e);
                    j.total += Math.max(0, e.getSize());
                }
            }
            if (apks.isEmpty()) throw new IOException("У наборі немає APK");
            j.filesTotal = apks.size();
            byte[] buf = new byte[1 << 16];
            int k = 0;
            for (ZipEntry e : apks) {
                j.check();
                j.cur = new File(e.getName()).getName();
                try (InputStream in = z.getInputStream(e); OutputStream out = s.openWrite("part" + (k++) + ".apk", 0, e.getSize() > 0 ? e.getSize() : -1)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        j.done += n;
                        j.progress(false);
                    }
                    s.fsync(out);
                }
                j.files++;
            }
            Intent cb = new Intent(ACTION_INSTALL).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, sid, cb, flags);
            s.commit(pend.getIntentSender());
            ok = true;
        } finally {
            if (!ok) try { pi.abandonSession(sid); } catch (Exception ignored) {}
        }
        res.put("session", sid);
    }

    /** Відповіді системи про встановлення. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception ignored) {}
                }
                return;
            }
            String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String text;
            switch (status) {
                case PackageInstaller.STATUS_SUCCESS: text = "Застосунок встановлено"; break;
                case PackageInstaller.STATUS_FAILURE_ABORTED: text = "Встановлення скасовано"; break;
                case PackageInstaller.STATUS_FAILURE_BLOCKED: text = "Встановлення заблоковано системою"; break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT: text = "Конфлікт з уже встановленою версією (інший підпис?)"; break;
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: text = "Застосунок несумісний із цим пристроєм"; break;
                case PackageInstaller.STATUS_FAILURE_INVALID: text = "Пошкоджений набір APK"; break;
                case PackageInstaller.STATUS_FAILURE_STORAGE: text = "Недостатньо місця для встановлення"; break;
                default: text = "Не вдалося встановити" + (msg != null ? ": " + msg : "");
            }
            Listener l = listener;
            if (l != null) {
                try {
                    l.onInstall(new JSONObject().put("ok", status == PackageInstaller.STATUS_SUCCESS).put("text", text)
                        .put("pkg", i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)).toString());
                } catch (Exception ignored) {}
            } else {
                Toast.makeText(c, text, Toast.LENGTH_LONG).show();
            }
        }
    }
}
