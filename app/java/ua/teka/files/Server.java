package ua.teka.files;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Віртуальний сайт усередині WebView (у мережу нічого не йде):
 *   /www/…            — інтерфейс із assets/www;
 *   /f?p=шлях         — файл (з підтримкою Range для перемотування відео й музики);
 *   /t?p=шлях&s=розмір — мініатюра;
 *   /pdf?p=шлях&n=сторінка&w=ширина — сторінка PDF зображенням;
 *   /app?pkg=пакет&s=розмір — значок застосунку.
 */
final class Server {
    private Server() {}

    static final String HOST = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOST;

    static WebResourceResponse handle(Context c, WebResourceRequest req) {
        Uri url = req.getUrl();
        if (url == null || !HOST.equals(url.getHost())) return null;
        String path = url.getPath();
        if (path == null || path.equals("/")) path = "/www/index.html";
        try {
            if (path.startsWith("/www/")) return asset(c.getAssets(), path.substring(1));
            switch (path) {
                case "/f": return file(new File(url.getQueryParameter("p")), req.getRequestHeaders(), "1".equals(url.getQueryParameter("dl")));
                case "/t": {
                    byte[] b = Thumbs.get(c, new File(url.getQueryParameter("p")), num(url.getQueryParameter("s"), 256));
                    return b == null ? text(404, "Not found", "") : image(b);
                }
                case "/pdf": {
                    byte[] b = Thumbs.pdfPage(new File(url.getQueryParameter("p")), num(url.getQueryParameter("n"), 0), num(url.getQueryParameter("w"), 1080));
                    return b == null ? text(404, "Not found", "") : image(b);
                }
                case "/app": {
                    byte[] b = Thumbs.appIcon(c, url.getQueryParameter("pkg"), num(url.getQueryParameter("s"), 96));
                    return b == null ? text(404, "Not found", "") : image(b);
                }
                default: return text(404, "Not found", "404: " + path);
            }
        } catch (SecurityException e) {
            return text(403, "Forbidden", "Немає доступу");
        } catch (Throwable e) {
            return text(404, "Not found", String.valueOf(e.getMessage()));
        }
    }

    static int num(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        return Fs.mime(name);
    }

    static WebResourceResponse asset(AssetManager am, String path) throws Exception {
        InputStream in;
        try {
            in = am.open(path, AssetManager.ACCESS_STREAMING);
        } catch (Exception e) {
            return text(404, "Not found", "404: " + path);
        }
        String m = mime(path);
        WebResourceResponse r = new WebResourceResponse(m, m.startsWith("text/") || m.contains("json") || m.contains("svg") ? "utf-8" : null, in);
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        r.setResponseHeaders(h);
        return r;
    }

    static WebResourceResponse image(byte[] b) {
        boolean png = b.length > 0 && (b[0] & 0xFF) == 0x89;
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=86400");
        return new WebResourceResponse(png ? "image/png" : "image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(b));
    }

    static final class Limited extends FilterInputStream {
        long left;
        Limited(InputStream in, long left) { super(in); this.left = left; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int c = super.read();
            if (c >= 0) left--;
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
    }

    static WebResourceResponse file(File f, Map<String, String> headers, boolean download) throws Exception {
        if (!f.isFile()) return text(404, "Not found", "Файл не знайдено");
        long len = f.length();
        String mime = Fs.mime(f.getName());
        String charset = null; // текстові файли читає переглядач через міст, з визначенням кодування
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-store");
        if (download) h.put("Content-Disposition", "attachment; filename*=UTF-8''" + Uri.encode(f.getName()));
        String range = null;
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
        InputStream in = new FileInputStream(f);
        if (range != null && range.startsWith("bytes=") && len > 0) {
            String spec = range.substring(6).split(",")[0].trim();
            long start, end;
            try {
                int dash = spec.indexOf('-');
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty()) { start = Math.max(0, len - Long.parseLong(b)); end = len - 1; }
                else { start = Long.parseLong(a); end = b.isEmpty() ? len - 1 : Math.min(len - 1, Long.parseLong(b)); }
            } catch (Exception e) {
                start = 0;
                end = len - 1;
            }
            if (start >= len || start > end) {
                in.close();
                h.put("Content-Range", "bytes */" + len);
                return new WebResourceResponse(mime, charset, 416, "Range Not Satisfiable", h, new ByteArrayInputStream(new byte[0]));
            }
            long skip = start;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            h.put("Content-Length", String.valueOf(end - start + 1));
            return new WebResourceResponse(mime, charset, 206, "Partial Content", h, new Limited(in, end - start + 1));
        }
        h.put("Content-Length", String.valueOf(len));
        return new WebResourceResponse(mime, charset, 200, "OK", h, in);
    }

    static WebResourceResponse text(int code, String reason, String body) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "utf-8", code, reason, h,
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
