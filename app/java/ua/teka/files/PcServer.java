package ua.teka.files;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * «Доступ з комп’ютера»: маленький HTTP-сервер у локальній мережі.
 * Перегляд папок, завантаження файлів (з Range для відео), папка цілком у ZIP,
 * вивантаження файлів з ПК, нова папка. Вхід за PIN-кодом, сесія в cookie.
 * Чистий Java (ServerSocket) — покритий тестами на JVM.
 */
public final class PcServer {
    public interface Listener { void onEvent(String text); }

    final File[] roots;
    final String[] rootNames;
    final String pin;
    final Map<String, Long> sessions = new ConcurrentHashMap<>();
    final Map<String, int[]> fails = new ConcurrentHashMap<>();
    volatile boolean running;
    ServerSocket ss;
    ExecutorService pool;
    Listener listener;

    public PcServer(File[] roots, String[] rootNames, String pin) {
        this.roots = roots;
        this.rootNames = rootNames;
        this.pin = pin;
    }

    public static String newPin() {
        SecureRandom r = new SecureRandom();
        return String.format(Locale.ROOT, "%06d", r.nextInt(1_000_000));
    }

    public int start(int port) throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port), 16);
        running = true;
        pool = Executors.newFixedThreadPool(6);
        Thread t = new Thread(this::acceptLoop, "teka-http");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    void acceptLoop() {
        while (running) {
            try {
                Socket s = ss.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            } catch (Exception e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------ запит

    static final class Req {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        InputStream in;
        String ip;

        String header(String k) { return headers.get(k.toLowerCase(Locale.ROOT)); }

        String cookie(String name) {
            String c = header("cookie");
            if (c == null) return null;
            for (String part : c.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
            return null;
        }

        String param(String name) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                String k = eq < 0 ? kv : kv.substring(0, eq);
                if (k.equals(name)) return eq < 0 ? "" : dec(kv.substring(eq + 1));
            }
            return null;
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static String dec(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    static String encPath(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String p : rel.split("/")) {
            if (p.isEmpty()) continue;
            sb.append('/').append(enc(p));
        }
        return sb.toString();
    }

    void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 1 << 16);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 1 << 16);
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Req r = new Req();
            r.method = parts[0];
            r.in = in;
            r.ip = sock.getInetAddress().getHostAddress();
            String target = parts[1];
            int q = target.indexOf('?');
            if (q >= 0) { r.query = target.substring(q + 1); target = target.substring(0, q); }
            r.path = target;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            route(r, out);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ маршрути

    void route(Req r, OutputStream out) throws IOException {
        if (r.path.equals("/favicon.ico")) { send(out, 204, "No Content", null, null, new byte[0]); return; }
        if (r.path.equals("/login")) { login(r, out); return; }
        String sid = r.cookie("teka");
        if (sid == null || !sessions.containsKey(sid)) {
            send(out, 200, "OK", "text/html; charset=utf-8", null, page("Вхід", loginForm(null)).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (r.path.equals("/") || r.path.isEmpty()) { roots(out); return; }
        String[] seg = r.path.split("/", 4); // "", "b", "0", "rel/path"
        if (seg.length < 3) { notFound(out); return; }
        int idx;
        try { idx = Integer.parseInt(seg[2]); } catch (NumberFormatException e) { notFound(out); return; }
        if (idx < 0 || idx >= roots.length) { notFound(out); return; }
        String rel = seg.length > 3 ? dec(seg[3]) : "";
        File f = resolve(idx, rel);
        if (f == null) { send(out, 403, "Forbidden", "text/plain; charset=utf-8", null, "Заборонено".getBytes(StandardCharsets.UTF_8)); return; }
        switch (seg[1]) {
            case "b":
                if (!f.exists()) { notFound(out); return; }
                if (f.isDirectory()) listing(idx, rel, f, out, r.param("ok"));
                else file(r, f, out, "1".equals(r.param("dl")));
                return;
            case "z":
                if (!f.isDirectory()) { notFound(out); return; }
                zipDir(f, out);
                return;
            case "u":
                if (!"POST".equals(r.method) || !f.isDirectory()) { notFound(out); return; }
                int n = upload(r, f);
                event("Отримано з комп’ютера: " + n + " файл(ів) у " + f.getName());
                redirect(out, "/b/" + idx + encPath(rel) + "?ok=" + n);
                return;
            case "m":
                if (!"POST".equals(r.method) || !f.isDirectory()) { notFound(out); return; }
                String name = form(r).get("name");
                if (name != null && Names.invalid(name) == null) new File(f, name).mkdirs();
                redirect(out, "/b/" + idx + encPath(rel));
                return;
            default:
                notFound(out);
        }
    }

    File resolve(int idx, String rel) {
        File root = roots[idx];
        File f = rel.isEmpty() ? root : new File(root, rel);
        return Names.inside(f, root) ? f : null;
    }

    void login(Req r, OutputStream out) throws IOException {
        if (!"POST".equals(r.method)) {
            send(out, 200, "OK", "text/html; charset=utf-8", null, page("Вхід", loginForm(null)).getBytes(StandardCharsets.UTF_8));
            return;
        }
        int[] f = fails.get(r.ip);
        long now = System.currentTimeMillis() / 1000;
        if (f != null && f[0] >= 5 && now - f[1] < 60) {
            send(out, 429, "Too Many Requests", "text/html; charset=utf-8", null,
                page("Вхід", loginForm("Забагато спроб. Зачекайте хвилину.")).getBytes(StandardCharsets.UTF_8));
            return;
        }
        String got = form(r).get("pin");
        if (pin != null && pin.equals(got)) {
            fails.remove(r.ip);
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            StringBuilder sid = new StringBuilder();
            for (byte x : b) sid.append(String.format(Locale.ROOT, "%02x", x));
            sessions.put(sid.toString(), System.currentTimeMillis());
            Map<String, String> h = new HashMap<>();
            h.put("Set-Cookie", "teka=" + sid + "; Path=/; HttpOnly; SameSite=Strict");
            h.put("Location", "/");
            send(out, 303, "See Other", null, h, new byte[0]);
            event("Комп’ютер " + r.ip + " увійшов");
            return;
        }
        if (f == null || now - f[1] >= 60) f = new int[]{0, (int) now};
        f[0]++;
        f[1] = (int) now;
        fails.put(r.ip, f);
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        send(out, 401, "Unauthorized", "text/html; charset=utf-8", null, page("Вхід", loginForm("Неправильний код")).getBytes(StandardCharsets.UTF_8));
    }

    /** Тіло форми application/x-www-form-urlencoded (до 64 КБ). */
    static Map<String, String> form(Req r) throws IOException {
        Map<String, String> m = new HashMap<>();
        int len = 0;
        try { len = Integer.parseInt(String.valueOf(r.header("content-length"))); } catch (NumberFormatException ignored) {}
        len = Math.min(len, 65536);
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) { int n = r.in.read(b, off, len - off); if (n < 0) break; off += n; }
        for (String kv : new String(b, 0, off, StandardCharsets.UTF_8).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                try { m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8")); }
                catch (Exception ignored) {}
            }
        }
        return m;
    }

    void event(String t) {
        Listener l = listener;
        if (l != null) try { l.onEvent(t); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ сторінки

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static final String CSS = "*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;background:#f5f6f8;color:#1d2230}"
        + "header{background:#16181d;color:#fff;padding:14px 22px;display:flex;align-items:center;gap:12px}header b{font-size:18px}header .logo{width:30px;height:30px;border-radius:8px;background:#f4b740;display:inline-block}"
        + "main{max-width:1000px;margin:0 auto;padding:18px}a{color:#2453d6;text-decoration:none}a:hover{text-decoration:underline}"
        + ".crumbs{margin:4px 0 14px;font-size:15px}.crumbs a{margin-right:4px}.card{background:#fff;border-radius:14px;box-shadow:0 1px 3px rgba(0,0,0,.08);overflow:hidden;margin-bottom:16px}"
        + "table{width:100%;border-collapse:collapse}td,th{padding:9px 14px;border-bottom:1px solid #eceef2;text-align:left}th{font-size:13px;color:#667;font-weight:600}td.s,th.s{text-align:right;white-space:nowrap;color:#556}td.d{white-space:nowrap;color:#778;font-size:13px}"
        + "tr:hover td{background:#fafbfd}.ic{display:inline-block;width:22px}.bar{display:flex;flex-wrap:wrap;gap:10px;padding:14px;align-items:center}"
        + "button,.btn{background:#f4b740;color:#1d1400;border:0;border-radius:10px;padding:9px 16px;font:600 14px system-ui;cursor:pointer}input[type=text],input[type=password]{border:1px solid #ccd;border-radius:10px;padding:9px 12px;font-size:15px}"
        + ".ok{background:#e7f8ee;color:#1d6b3d;padding:10px 14px;border-radius:10px;margin-bottom:12px}.err{color:#c0262d;margin:8px 0}.muted{color:#778;font-size:13px}"
        + ".login{max-width:360px;margin:60px auto;text-align:center}.login input{width:100%;font-size:26px;letter-spacing:.3em;text-align:center;margin:14px 0}";

    static String page(String title, String body) {
        return "<!doctype html><html lang=\"uk\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
            + esc(title) + " — Тека</title><style>" + CSS + "</style></head><body><header><span class=\"logo\"></span><b>Тека</b><span class=\"muted\" style=\"color:#aab\">доступ до телефона з комп’ютера</span></header><main>"
            + body + "</main></body></html>";
    }

    static String loginForm(String err) {
        return "<div class=\"card login\" style=\"padding:26px\"><h2 style=\"margin:0\">Введіть код</h2><p class=\"muted\">Шестизначний код показано в застосунку «Тека» на телефоні.</p>"
            + (err != null ? "<div class=\"err\">" + esc(err) + "</div>" : "")
            + "<form method=\"post\" action=\"/login\"><input name=\"pin\" type=\"password\" inputmode=\"numeric\" autofocus autocomplete=\"off\" maxlength=\"6\"><button style=\"width:100%\">Увійти</button></form></div>";
    }

    void roots(OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder("<div class=\"card\"><table><tr><th>Сховище</th><th class=\"s\">Вільно</th></tr>");
        for (int i = 0; i < roots.length; i++) {
            sb.append("<tr><td><span class=\"ic\">💾</span><a href=\"/b/").append(i).append("/\">").append(esc(rootNames[i])).append("</a></td><td class=\"s\">")
                .append(Names.human(roots[i].getUsableSpace())).append(" з ").append(Names.human(roots[i].getTotalSpace())).append("</td></tr>");
        }
        sb.append("</table></div>");
        send(out, 200, "OK", "text/html; charset=utf-8", null, page("Сховища", sb.toString()).getBytes(StandardCharsets.UTF_8));
    }

    static String icon(File f) {
        if (f.isDirectory()) return "📁";
        String e = Names.ext(f.getName());
        if (e.matches("jpe?g|png|gif|webp|heic|heif|bmp|avif")) return "🖼";
        if (e.matches("mp4|mkv|webm|avi|mov|3gp")) return "🎞";
        if (e.matches("mp3|m4a|aac|ogg|opus|flac|wav|amr")) return "🎵";
        if (e.matches("zip|rar|7z|tar|gz|tgz")) return "🗜";
        if (e.equals("apk")) return "📦";
        if (e.equals("pdf")) return "📕";
        return "📄";
    }

    void listing(int idx, String rel, File dir, OutputStream out, String ok) throws IOException {
        File[] list = dir.listFiles();
        if (list == null) list = new File[0];
        Arrays.sort(list, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        if (ok != null) sb.append("<div class=\"ok\">Вивантажено файлів: ").append(esc(ok)).append("</div>");
        sb.append("<div class=\"crumbs\"><a href=\"/\">Сховища</a> › <a href=\"/b/").append(idx).append("/\">").append(esc(rootNames[idx])).append("</a>");
        StringBuilder acc = new StringBuilder();
        for (String p : rel.split("/")) {
            if (p.isEmpty()) continue;
            acc.append('/').append(p);
            sb.append(" › <a href=\"/b/").append(idx).append(encPath(acc.toString())).append("\">").append(esc(p)).append("</a>");
        }
        sb.append("</div>");
        String base = "/b/" + idx + encPath(rel);
        sb.append("<div class=\"card\"><div class=\"bar\"><form method=\"post\" enctype=\"multipart/form-data\" action=\"/u/").append(idx).append(encPath(rel))
            .append("\"><input type=\"file\" name=\"f\" multiple> <button>Вивантажити сюди</button></form>")
            .append("<form method=\"post\" action=\"/m/").append(idx).append(encPath(rel)).append("\"><input type=\"text\" name=\"name\" placeholder=\"Нова папка\"> <button>Створити</button></form>")
            .append("<a class=\"btn\" href=\"/z/").append(idx).append(encPath(rel)).append("\">Завантажити папку ZIP</a></div></div>");
        sb.append("<div class=\"card\"><table><tr><th>Назва</th><th class=\"s\">Розмір</th><th>Змінено</th></tr>");
        if (!rel.isEmpty()) {
            String up = rel.contains("/") ? rel.substring(0, rel.replaceAll("/+$", "").lastIndexOf('/') + 1) : "";
            sb.append("<tr><td colspan=\"3\"><span class=\"ic\">↩</span><a href=\"/b/").append(idx).append(encPath(up)).append("/\">..</a></td></tr>");
        }
        int shown = 0;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            shown++;
            String href = base + "/" + enc(f.getName()) + (f.isDirectory() ? "/" : "");
            sb.append("<tr><td><span class=\"ic\">").append(icon(f)).append("</span><a href=\"").append(href).append("\">").append(esc(f.getName())).append("</a>");
            if (!f.isDirectory()) sb.append(" <a class=\"muted\" href=\"").append(href).append("?dl=1\" title=\"Завантажити\">⬇</a>");
            sb.append("</td><td class=\"s\">").append(f.isDirectory() ? "" : Names.human(f.length())).append("</td><td class=\"d\">")
                .append(df.format(new Date(f.lastModified()))).append("</td></tr>");
        }
        if (shown == 0) sb.append("<tr><td colspan=\"3\" class=\"muted\">Папка порожня</td></tr>");
        sb.append("</table></div>");
        send(out, 200, "OK", "text/html; charset=utf-8", null, page(dir.getName(), sb.toString()).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ файли

    void file(Req r, File f, OutputStream out, boolean download) throws IOException {
        long len = f.length();
        long start = 0, end = len - 1;
        int code = 200;
        String range = r.header("range");
        if (range != null && range.startsWith("bytes=") && len > 0) {
            try {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                String a = spec.substring(0, dash), b = spec.substring(dash + 1);
                if (a.isEmpty()) { start = Math.max(0, len - Long.parseLong(b)); }
                else { start = Long.parseLong(a); if (!b.isEmpty()) end = Math.min(len - 1, Long.parseLong(b)); }
                if (start > end || start >= len) {
                    Map<String, String> h = new HashMap<>();
                    h.put("Content-Range", "bytes */" + len);
                    send(out, 416, "Range Not Satisfiable", null, h, new byte[0]);
                    return;
                }
                code = 206;
            } catch (Exception e) {
                start = 0; end = len - 1; code = 200;
            }
        }
        Map<String, String> h = new HashMap<>();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Disposition", (download ? "attachment" : "inline") + "; filename*=UTF-8''" + enc(f.getName()));
        if (code == 206) h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        long count = len == 0 ? 0 : end - start + 1;
        head(out, code, code == 206 ? "Partial Content" : "OK", mime(f.getName()), h, count);
        if ("HEAD".equals(r.method)) return;
        try (InputStream in = new FileInputStream(f)) {
            long skipped = 0;
            while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
            byte[] buf = new byte[1 << 16];
            long left = count;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /** Папка у ZIP «на льоту» (без стиснення — швидко і без тимчасових файлів). */
    void zipDir(File dir, OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename*=UTF-8''"
            + enc(dir.getName() + ".zip") + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8);
        z.setLevel(1);
        addZip(z, dir, dir.getName());
        z.finish();
        z.flush();
    }

    static void addZip(ZipOutputStream z, File f, String name) throws IOException {
        if (f.isDirectory()) {
            z.putNextEntry(new ZipEntry(name + "/"));
            z.closeEntry();
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) addZip(z, c, name + "/" + c.getName());
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        }
        z.closeEntry();
    }

    /** multipart/form-data: кожен файл пишеться потоком, без утримання в пам’яті. */
    int upload(Req r, File dir) throws IOException {
        String ct = r.header("content-type");
        if (ct == null || !ct.contains("boundary=")) return 0;
        String boundary = ct.substring(ct.indexOf("boundary=") + 9).replace("\"", "").trim();
        int semi = boundary.indexOf(';');
        if (semi >= 0) boundary = boundary.substring(0, semi);
        byte[] delim = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        InputStream in = r.in;
        // перший роздільник
        String first = readLine(in);
        if (first == null || !first.startsWith("--" + boundary)) return 0;
        int saved = 0;
        while (true) {
            String filename = null;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                String low = h.toLowerCase(Locale.ROOT);
                if (low.startsWith("content-disposition")) {
                    int fi = low.indexOf("filename=\"");
                    if (fi >= 0) {
                        int endq = h.indexOf('"', fi + 10);
                        filename = h.substring(fi + 10, endq < 0 ? h.length() : endq);
                    }
                }
            }
            if (h == null) break;
            OutputStream os = null;
            File target = null;
            if (filename != null && !filename.isEmpty()) {
                String clean = filename.replace('\\', '/');
                clean = clean.substring(clean.lastIndexOf('/') + 1);
                if (Names.invalid(clean) == null) {
                    target = new File(dir, Names.unique(dir, clean));
                    os = new BufferedOutputStream(new FileOutputStream(target), 1 << 16);
                }
            }
            boolean last = copyUntil(in, os, delim);
            if (os != null) { os.close(); saved++; }
            if (last) break;
        }
        return saved;
    }

    /**
     * Копіює дані частини до роздільника. Після роздільника читає «--» (кінець) або CRLF (наступна частина).
     * Повертає true, якщо це була остання частина.
     */
    static boolean copyUntil(InputStream in, OutputStream os, byte[] delim) throws IOException {
        int dl = delim.length;
        byte last = delim[dl - 1];
        byte[] ring = new byte[dl];     // останні dl байтів (кільце)
        int head = 0, filled = 0;
        byte[] buf = new byte[1 << 16];
        int bl = 0;
        int c;
        while ((c = in.read()) >= 0) {
            if (filled == dl) {
                // найстаріший байт кільця точно не належить роздільнику — віддаємо його
                if (os != null) {
                    buf[bl++] = ring[head];
                    if (bl == buf.length) { os.write(buf, 0, bl); bl = 0; }
                }
                ring[head] = (byte) c;
                head = (head + 1) % dl;
            } else {
                ring[(head + filled) % dl] = (byte) c;
                filled++;
            }
            if (filled == dl && (byte) c == last) {
                boolean match = true;
                for (int i = 0; i < dl; i++) if (ring[(head + i) % dl] != delim[i]) { match = false; break; }
                if (match) {
                    if (os != null && bl > 0) os.write(buf, 0, bl);
                    int a = in.read(), b = in.read();
                    if (a == '-' && b == '-') { readLine(in); return true; }
                    return false;
                }
            }
        }
        if (os != null) {
            if (bl > 0) os.write(buf, 0, bl);
            for (int i = 0; i < filled; i++) os.write(ring[(head + i) % dl]);
        }
        return true;
    }

    // ------------------------------------------------------------ відповіді

    static String mime(String name) {
        String e = Names.ext(name);
        switch (e) {
            case "html": case "htm": return "text/html; charset=utf-8";
            case "txt": case "md": case "log": case "csv": case "json": case "xml": case "js": case "css": case "java": case "py": case "kt": case "sh": case "ini":
                return "text/plain; charset=utf-8";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "ogg": case "opus": return "audio/ogg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    static void head(OutputStream out, int code, String reason, String type, Map<String, String> headers, long length) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void send(OutputStream out, int code, String reason, String type, Map<String, String> headers, byte[] body) throws IOException {
        head(out, code, reason, type, headers, body.length);
        out.write(body);
    }

    static void redirect(OutputStream out, String to) throws IOException {
        Map<String, String> h = new HashMap<>();
        h.put("Location", to);
        send(out, 303, "See Other", null, h, new byte[0]);
    }

    static void notFound(OutputStream out) throws IOException {
        send(out, 404, "Not Found", "text/html; charset=utf-8", null, page("Не знайдено", "<p>Не знайдено. <a href=\"/\">До сховищ</a></p>").getBytes(StandardCharsets.UTF_8));
    }
}
