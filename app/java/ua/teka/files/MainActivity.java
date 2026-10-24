package ua.teka.files;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Єдиний екран: WebView з інтерфейсом «Теки» на віртуальному https-хості і JS-міст
 * {@code window.Android}. Усі слухачі — статичні вкладені класи або лямбди.
 */
public class MainActivity extends Activity {
    static final int REQ_PERM = 41, REQ_NOTIF = 42;
    static final String MAIN_URL = Server.BASE + "/www/index.html";

    WebView web;
    FrameLayout root;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    boolean pageReady;
    String pendingIntent;
    Ops ops;
    final ExecutorService bg = Executors.newFixedThreadPool(3);

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        boolean night = isNight();
        int bg0 = night ? 0xFF141519 : 0xFFF7F6F2;
        w.setStatusBarColor(bg0);
        w.setNavigationBarColor(bg0);
        setLightBars(!night);

        ops = new Ops(this, (id, json) -> js("window.__teka&&__teka.onTask(" + JSONObject.quote(id) + "," + json + ")"));
        ServerService.listener = json -> js("window.__teka&&__teka.onPc(" + json + ")");
        Apps.listener = json -> js("window.__teka&&__teka.onInstall(" + json + ")");

        root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(bg0);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        web.setWebViewClient(new Client(this));
        web.setWebChromeClient(new Chrome(this));
        web.addJavascriptInterface(new Bridge(this), "Android");
        WebView.setWebContentsDebuggingEnabled(getSharedPreferences("teka", MODE_PRIVATE).getBoolean("debug", false));

        bg.execute(() -> {
            Thumbs.trim(this);
            deleteTree(new File(getCacheDir(), "arc"));
            deleteTree(new File(getCacheDir(), "incoming"));
        });
        handleIntent(getIntent());
        web.loadUrl(MAIN_URL);
    }

    static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File x : ch) deleteTree(x);
        f.delete();
    }

    boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    void setLightBars(boolean light) {
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        f = light ? f | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            f = light ? f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    void js(String code) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    // ------------------------------------------------------------ дозвіл на файли

    String perm() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager() ? "full" : "none";
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? "full" : "none";
    }

    void requestPerm() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Exception ignored) {}
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_PERM) js("window.__teka&&__teka.onPerm(" + JSONObject.quote(perm()) + ")");
        if (req == REQ_NOTIF) js("window.__teka&&__teka.onNotif()");
    }

    // ------------------------------------------------------------ відкриття файлів з інших застосунків

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    void handleIntent(Intent in) {
        if (in == null) return;
        String action = in.getAction();
        if ("ua.teka.files.PC".equals(action)) {
            pendingIntent = "{\"type\":\"pc\"}";
            if (pageReady) js("window.__teka&&__teka.onIntent()");
            setIntent(new Intent());
            return;
        }
        if (!Intent.ACTION_VIEW.equals(action) || in.getData() == null) return;
        Uri u = in.getData();
        setIntent(new Intent());
        bg.execute(() -> {
            try {
                File f = resolve(u);
                if (f == null) return;
                pendingIntent = new JSONObject().put("type", "open").put("path", f.getAbsolutePath()).toString();
                if (pageReady) js("window.__teka&&__teka.onIntent()");
            } catch (Exception ignored) {}
        });
    }

    /** Шлях до файлу за адресою від іншого застосунку; якщо шлях невідомий — копія в кеші. */
    File resolve(Uri u) throws Exception {
        if ("file".equals(u.getScheme()) && u.getPath() != null) return new File(u.getPath());
        if (FilesProvider.AUTHORITY.equals(u.getAuthority()) && u.getPath() != null && u.getPath().startsWith("/f/")) return new File(u.getPath().substring(2));
        String name = "файл";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int di = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (di >= 0) {
                    String p = c.getString(di);
                    if (p != null && new File(p).canRead()) return new File(p);
                }
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
            }
        } catch (Exception ignored) {}
        File dir = new File(getCacheDir(), "incoming");
        dir.mkdirs();
        File f = new File(dir, name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
            if (in == null) return null;
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return f;
    }

    // ------------------------------------------------------------ життєвий цикл

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) js("window.__teka&&__teka.resume(" + JSONObject.quote(perm()) + ")");
    }

    @Override
    protected void onPause() {
        super.onPause();
        js("window.__teka&&__teka.pause()");
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        js("window.__teka&&__teka.config(" + isNight() + ")");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            if (customCb != null) customCb.onCustomViewHidden();
            return;
        }
        if (!pageReady) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.__teka?__teka.back():'exit'", v -> {
            if ("\"exit\"".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        ops.shutdown();
        bg.shutdownNow();
        Thumbs.closePdf();
        ServerService.listener = null;
        Apps.listener = null;
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ WebView

    static class Client extends WebViewClient {
        final MainActivity a;
        Client(MainActivity a) { this.a = a; }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return Server.handle(a, r);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (Server.HOST.equals(u.getHost())) return false;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            Bridge.handedOut = false;
            a.pageReady = false;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            a.pageReady = true;
            if (a.pendingIntent != null) a.js("window.__teka&&__teka.onIntent()");
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            a.web = null;
            v.destroy();
            a.recreate();
            return true;
        }
    }

    static class Chrome extends WebChromeClient {
        final MainActivity a;
        Chrome(MainActivity a) { this.a = a; }

        @Override
        @SuppressWarnings("deprecation")
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (a.customView != null) { cb.onCustomViewHidden(); return; }
            a.customView = view;
            a.customCb = cb;
            a.root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            a.web.setVisibility(View.INVISIBLE);
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onHideCustomView() {
            if (a.customView == null) return;
            a.root.removeView(a.customView);
            a.customView = null;
            a.customCb = null;
            a.web.setVisibility(View.VISIBLE);
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().getDecorView().setSystemUiVisibility(0);
            a.setLightBars(!a.isNight());
        }
    }

    // ------------------------------------------------------------ JS-міст

    /**
     * Міст для сторінки. Кожен виклик перевіряє маркер, який отримує лише головна сторінка
     * під час завантаження (див. {@link #hello()}).
     */
    public static class Bridge {
        final MainActivity a;
        static volatile boolean handedOut;
        static final String token = newToken();

        Bridge(MainActivity a) { this.a = a; }

        static String newToken() {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }

        boolean ok(String t) { return token.equals(t); }

        static String fail(String msg) {
            try {
                return new JSONObject().put("ok", false).put("err", msg == null ? "помилка" : msg).toString();
            } catch (Exception e) {
                return "{\"ok\":false}";
            }
        }

        @JavascriptInterface
        public synchronized String hello() {
            if (handedOut) return "";
            handedOut = true;
            return token;
        }

        @JavascriptInterface
        public String info(String t) {
            if (!ok(t)) return "{}";
            try {
                PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
                File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                return new JSONObject()
                    .put("version", pi.versionName)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("night", a.isNight())
                    .put("perm", a.perm())
                    .put("home", Environment.getExternalStorageDirectory().getAbsolutePath())
                    .put("downloads", dl.getAbsolutePath())
                    .put("dcim", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath())
                    .put("pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath())
                    .put("music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath())
                    .put("movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).getAbsolutePath())
                    .put("documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath())
                    .put("debug", a.getSharedPreferences("teka", MODE_PRIVATE).getBoolean("debug", false))
                    .put("encodings", encodings())
                    .toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        static JSONArray encodings() {
            JSONArray arr = new JSONArray();
            for (String[] e : Encodings.SUPPORTED) arr.put(new JSONArray().put(e[0]).put(e[2]));
            return arr;
        }

        @JavascriptInterface
        public String perm(String t) {
            return ok(t) ? a.perm() : "none";
        }

        @JavascriptInterface
        public void requestPerm(String t) {
            if (ok(t)) a.runOnUiThread(a::requestPerm);
        }

        @JavascriptInterface
        public boolean needNotif(String t) {
            return ok(t) && Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestNotif(String t) {
            if (!ok(t)) return;
            if (Build.VERSION.SDK_INT >= 33) a.runOnUiThread(() -> a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
            else a.js("window.__teka&&__teka.onNotif()");
        }

        // ---------- швидкі операції (синхронно)

        @JavascriptInterface
        public String volumes(String t) {
            if (!ok(t)) return "[]";
            try { return Fs.volumes(a).toString(); } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String list(String t, String path, boolean hidden) {
            if (!ok(t)) return "{}";
            try { return Fs.list(path, hidden).toString(); } catch (Exception e) { return fail(e.getMessage()); }
        }

        @JavascriptInterface
        public String stat(String t, String path) {
            if (!ok(t)) return "{}";
            try { return Fs.stat(path).toString(); } catch (Exception e) { return fail(e.getMessage()); }
        }

        @JavascriptInterface
        public String mkdir(String t, String parent, String name) {
            if (!ok(t)) return "{}";
            try {
                String r = Fs.mkdir(parent, name).toString();
                return r;
            } catch (Exception e) { return fail(e.getMessage()); }
        }

        @JavascriptInterface
        public String mkfile(String t, String parent, String name) {
            if (!ok(t)) return "{}";
            try { return Fs.mkfile(parent, name).toString(); } catch (Exception e) { return fail(e.getMessage()); }
        }

        @JavascriptInterface
        public String rename(String t, String path, String name) {
            if (!ok(t)) return "{}";
            try {
                JSONObject r = Fs.rename(path, name);
                if (r.optBoolean("ok")) a.ops.scan(java.util.Arrays.asList(path, r.optString("path")));
                return r.toString();
            } catch (Exception e) { return fail(e.getMessage()); }
        }

        @JavascriptInterface
        public String conflicts(String t, String srcs, String dest) {
            if (!ok(t)) return "[]";
            try { return Fs.conflicts(new JSONArray(srcs), dest).toString(); } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String exists(String t, String path) {
            if (!ok(t)) return "false";
            return String.valueOf(new File(path).exists());
        }

        // ---------- довгі завдання з прогресом

        @JavascriptInterface
        public void task(String t, String id, String kind, String json) {
            if (!ok(t)) return;
            try {
                a.ops.start(id, kind, new JSONObject(json));
            } catch (Exception e) {
                a.js("window.__teka&&__teka.onTask(" + JSONObject.quote(id) + "," + "{\"state\":\"error\",\"err\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "})");
            }
        }

        @JavascriptInterface
        public void cancel(String t, String id) {
            if (ok(t)) a.ops.cancel(id);
        }

        // ---------- запити у фоні: відповідь приходить у __teka.onResult(id, json)

        @JavascriptInterface
        public void call(String t, String id, String name, String json) {
            if (!ok(t)) return;
            a.bg.execute(() -> {
                String out;
                try {
                    out = dispatch(name, new JSONObject(json));
                } catch (OutOfMemoryError e) {
                    out = fail("Забракло пам’яті");
                } catch (Throwable e) {
                    out = fail(e instanceof SecurityException ? "Немає доступу" : e.getMessage());
                }
                a.js("window.__teka&&__teka.onResult(" + JSONObject.quote(id) + "," + out + ")");
            });
        }

        String dispatch(String name, JSONObject p) throws Exception {
            switch (name) {
                case "category":
                    return Fs.category(a, p.getString("kind"), p.optInt("offset", 0), p.optInt("limit", 200)).toString();
                case "archiveList": {
                    File f = new File(p.getString("path"));
                    String cs = p.optString("charset", "");
                    if (cs.isEmpty()) cs = "zip".equals(Archive.kind(f.getName())) ? Archive.zipCharset(f) : null;
                    JSONArray arr = new JSONArray();
                    for (Archive.Entry e : Archive.list(f, cs)) {
                        arr.put(new JSONObject().put("name", e.name).put("size", e.size).put("csize", e.csize).put("mtime", e.mtime).put("dir", e.dir));
                    }
                    return new JSONObject().put("ok", true).put("entries", arr).put("charset", cs == null ? "" : cs).toString();
                }
                case "archiveOpen": {
                    File f = new File(p.getString("path"));
                    String entry = p.getString("entry");
                    File dir = new File(new File(a.getCacheDir(), "arc"), Thumbs.sha1(f.getAbsolutePath() + "|" + f.lastModified() + "|" + entry));
                    dir.mkdirs();
                    File out = new File(dir, new File(entry).getName());
                    if (!out.exists()) {
                        Archive.extract(f, dir, new HashSet<>(Collections.singletonList(entry)), p.optString("charset", "").isEmpty() ? null : p.optString("charset"), "replace", null);
                    }
                    if (!out.exists()) return fail("Не вдалося видобути файл");
                    return new JSONObject().put("ok", true).put("path", out.getAbsolutePath()).toString();
                }
                case "apkInfo":
                    return Apps.apkInfo(a, new File(p.getString("path"))).toString();
                case "mediaInfo":
                    return Convert.mediaInfo(new File(p.getString("path"))).put("ok", true).toString();
                case "imageInfo":
                    return Convert.imageInfo(new File(p.getString("path"))).put("ok", true).toString();
                case "pdfInfo":
                    return Convert.pdfInfo(new File(p.getString("path"))).toString();
                case "readText": {
                    File f = new File(p.getString("path"));
                    long max = p.optLong("max", 4L << 20);
                    long len = f.length();
                    int n = (int) Math.min(len, max);
                    byte[] b = new byte[n];
                    try (InputStream in = new FileInputStream(f)) {
                        int off = 0, r;
                        while (off < n && (r = in.read(b, off, n - off)) > 0) off += r;
                        n = off;
                    }
                    Encodings.Text tx = Encodings.read(b, n, p.optString("enc", "auto"));
                    return new JSONObject().put("ok", true).put("text", tx.binary && !p.optBoolean("force") ? "" : tx.text)
                        .put("enc", tx.enc).put("eol", tx.eol).put("bom", tx.bom).put("binary", tx.binary)
                        .put("size", len).put("truncated", len > max).toString();
                }
                case "writeText": {
                    File f = new File(p.getString("path"));
                    byte[] b = Encodings.write(p.getString("text"), p.optString("enc", "utf-8"), p.optBoolean("bom"), p.optString("eol", "LF"));
                    File tmp = new File(f.getParentFile(), ".teka_save_" + System.nanoTime());
                    try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                    if (!tmp.renameTo(f)) {
                        tmp.delete();
                        try (OutputStream o = new FileOutputStream(f)) { o.write(b); }
                    }
                    a.ops.scan(Collections.singletonList(f.getAbsolutePath()));
                    return new JSONObject().put("ok", true).put("size", b.length).put("mtime", f.lastModified()).toString();
                }
                case "unmappable":
                    return new JSONObject().put("ok", true).put("count", Encodings.unmappable(p.getString("text"), p.getString("enc"))).toString();
                case "folderSize": {
                    long[] acc = new long[2];
                    sizeOf(new File(p.getString("path")), acc, System.currentTimeMillis() + 4000);
                    return new JSONObject().put("ok", true).put("size", acc[0]).put("files", acc[1]).toString();
                }
                default:
                    return fail("Невідомий запит " + name);
            }
        }

        static void sizeOf(File f, long[] acc, long until) {
            if (System.currentTimeMillis() > until) return;
            File[] ch = f.listFiles();
            if (ch == null) return;
            for (File x : ch) {
                if (x.isDirectory()) sizeOf(x, acc, until);
                else { acc[0] += x.length(); acc[1]++; }
            }
        }

        // ---------- інші застосунки

        @JavascriptInterface
        public void open(String t, String path, String mime, boolean chooser) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                File f = new File(path);
                String m = mime == null || mime.isEmpty() ? Fs.mime(f.getName()) : mime;
                if ("apk".equals(Names.ext(f.getName()))) { install(t, path); return; }
                Intent i = new Intent(Intent.ACTION_VIEW);
                Uri u = FilesProvider.uriFor(f);
                i.setDataAndType(u, m);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    a.startActivity(chooser ? Intent.createChooser(i, "Відкрити в…") : i);
                } catch (Exception e) {
                    try {
                        i.setDataAndType(u, "*/*");
                        a.startActivity(Intent.createChooser(i, "Відкрити в…"));
                    } catch (Exception e2) {
                        a.js("window.__teka&&__teka.toast('Немає застосунку, щоб відкрити цей файл')");
                    }
                }
            });
        }

        @JavascriptInterface
        public void share(String t, String pathsJson) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                try {
                    JSONArray arr = new JSONArray(pathsJson);
                    ArrayList<Uri> uris = new ArrayList<>();
                    String type = null;
                    for (int k = 0; k < arr.length(); k++) {
                        File f = new File(arr.getString(k));
                        if (!f.isFile()) continue;
                        uris.add(FilesProvider.uriFor(f));
                        String m = Fs.mime(f.getName());
                        if (type == null) type = m;
                        else if (!type.equals(m)) {
                            String a1 = type.split("/")[0], b1 = m.split("/")[0];
                            type = a1.equals(b1) ? a1 + "/*" : "*/*";
                        }
                    }
                    if (uris.isEmpty()) return;
                    Intent i;
                    if (uris.size() == 1) {
                        i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    } else {
                        i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    }
                    i.setType(type);
                    ClipData cd = ClipData.newRawUri("", uris.get(0));
                    for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                    i.setClipData(cd);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(i, "Поділитися"));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void install(String t, String path) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                    a.js("window.__teka&&__teka.toast('Дозвольте «Теці» встановлювати застосунки й повторіть')");
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
                    } catch (Exception ignored) {}
                    return;
                }
                File f = new File(path);
                if (!"apk".equals(Names.ext(f.getName()))) {
                    try {
                        a.ops.start("install-" + System.nanoTime(), "installBundle", new JSONObject().put("path", path));
                    } catch (Exception ignored) {}
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(FilesProvider.uriFor(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { a.startActivity(i); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void appAction(String t, String pkg, String action) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                try {
                    switch (action) {
                        case "launch": {
                            Intent i = a.getPackageManager().getLaunchIntentForPackage(pkg);
                            if (i != null) a.startActivity(i);
                            break;
                        }
                        case "uninstall":
                            a.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
                            break;
                        case "details":
                            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                            break;
                        case "market":
                            try {
                                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
                            } catch (Exception e) {
                                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                            }
                            break;
                        default:
                    }
                } catch (Exception e) {
                    a.js("window.__teka&&__teka.toast('Не вдалося виконати дію')");
                }
            });
        }

        // ---------- доступ з ПК

        @JavascriptInterface
        public void pcStart(String t, String pin, int port) {
            if (!ok(t)) return;
            Intent i = new Intent(a, ServerService.class).putExtra("pin", pin).putExtra("port", port);
            try {
                if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
                else a.startService(i);
            } catch (Exception e) {
                a.js("window.__teka&&__teka.toast('Не вдалося запустити доступ з ПК')");
            }
        }

        @JavascriptInterface
        public void pcStop(String t) {
            if (!ok(t)) return;
            a.stopService(new Intent(a, ServerService.class));
        }

        @JavascriptInterface
        public String pcStatus(String t) {
            return ok(t) ? ServerService.status() : "{}";
        }

        // ---------- різне

        @JavascriptInterface
        public boolean kvPut(String t, String key, String value) {
            return ok(t) && Store.put(a, "kv", key, value);
        }

        @JavascriptInterface
        public String kvGet(String t, String key) {
            if (!ok(t)) return null;
            return Store.get(a, "kv", key);
        }

        @JavascriptInterface
        public void clip(String t, String text) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Тека", text));
            });
        }

        @JavascriptInterface
        public String intent(String t) {
            if (!ok(t)) return "";
            String s = a.pendingIntent;
            a.pendingIntent = null;
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public void bars(String t, String color, boolean light) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(color);
                    a.getWindow().setStatusBarColor(c);
                    a.getWindow().setNavigationBarColor(c);
                    if (a.web != null) a.web.setBackgroundColor(c);
                    a.setLightBars(light);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void keepScreen(String t, boolean on) {
            if (!ok(t)) return;
            a.runOnUiThread(() -> {
                if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void debug(String t, boolean on) {
            if (!ok(t)) return;
            a.getSharedPreferences("teka", MODE_PRIVATE).edit().putBoolean("debug", on).apply();
            a.runOnUiThread(() -> WebView.setWebContentsDebuggingEnabled(on));
        }

        @JavascriptInterface
        public String hashText(String t, String text) {
            if (!ok(t)) return "";
            try {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte x : d) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void exit(String t) {
            if (ok(t)) a.runOnUiThread(a::finish);
        }

        static List<String> strings(JSONArray arr) throws Exception {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) l.add(arr.getString(i));
            return l;
        }
    }
}
