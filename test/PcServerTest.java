import ua.teka.files.PcServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Тести HTTP-доступу з ПК: вхід за кодом, перегляд, Range, вивантаження, ZIP, вихід за межі. */
public class PcServerTest {
    static int failed = 0, passed = 0;

    static void eq(String name, Object got, Object want) {
        if (want == null ? got == null : want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + name + ": отримано «" + got + "», очікувалось «" + want + "»");
    }

    static final class Resp {
        int code;
        byte[] body;
        HttpURLConnection c;
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static Resp req(String method, String url, String cookie, String ctype, byte[] body, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (range != null) c.setRequestProperty("Range", range);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        Resp r = new Resp();
        r.c = c;
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
        r.body = b.toByteArray();
        return r;
    }

    public static void main(String[] a) throws Exception {
        File root = Files.createTempDirectory("tekasrv").toFile();
        new File(root, "Фото").mkdirs();
        byte[] big = new byte[500_000];
        new Random(7).nextBytes(big);
        try (FileOutputStream o = new FileOutputStream(new File(root, "Фото/відео.mp4"))) { o.write(big); }
        try (FileOutputStream o = new FileOutputStream(new File(root, "нотатка.txt"))) { o.write("Привіт".getBytes(StandardCharsets.UTF_8)); }

        PcServer s = new PcServer(new File[]{root}, new String[]{"Внутрішня пам’ять"}, "123456");
        int port = s.start(0);
        String base = "http://127.0.0.1:" + port;

        // без входу — форма коду
        Resp r = req("GET", base + "/b/0/", null, null, null, null);
        eq("login form", r.text().contains("Введіть код"), true);
        // неправильний код
        r = req("POST", base + "/login", null, "application/x-www-form-urlencoded", "pin=000000".getBytes(), null);
        eq("bad pin", r.code, 401);
        // правильний код
        r = req("POST", base + "/login", null, "application/x-www-form-urlencoded", "pin=123456".getBytes(), null);
        eq("good pin", r.code, 303);
        String cookie = r.c.getHeaderField("Set-Cookie").split(";")[0];
        eq("cookie", cookie.startsWith("teka="), true);

        // сховища і папка
        r = req("GET", base + "/", cookie, null, null, null);
        eq("roots", r.text().contains("Внутрішня пам’ять"), true);
        r = req("GET", base + "/b/0/", cookie, null, null, null);
        eq("listing", r.text().contains("нотатка.txt") && r.text().contains("Фото"), true);

        // файл цілком і частина (Range)
        String vid = base + "/b/0/" + URLEncoder.encode("Фото", "UTF-8") + "/" + URLEncoder.encode("відео.mp4", "UTF-8");
        r = req("GET", vid, cookie, null, null, null);
        eq("full", r.code + " " + r.body.length, "200 " + big.length);
        eq("full data", Arrays.equals(r.body, big), true);
        r = req("GET", vid, cookie, null, null, "bytes=1000-1999");
        eq("range code", r.code, 206);
        eq("range data", Arrays.equals(r.body, Arrays.copyOfRange(big, 1000, 2000)), true);
        eq("range header", r.c.getHeaderField("Content-Range"), "bytes 1000-1999/" + big.length);
        r = req("GET", vid, cookie, null, null, "bytes=-100");
        eq("suffix range", Arrays.equals(r.body, Arrays.copyOfRange(big, big.length - 100, big.length)), true);

        // вивантаження двох файлів (один бінарний з CRLF усередині)
        String boundary = "----TekaBoundary7MA4YWxk";
        byte[] up1 = "перший файл\r\n--не роздільник\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] up2 = new byte[200_000];
        new Random(3).nextBytes(up2);
        ByteArrayOutputStream mp = new ByteArrayOutputStream();
        mp.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"звіт.txt\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        mp.write(up1);
        mp.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"дані.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        mp.write(up2);
        mp.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        r = req("POST", base + "/u/0/" + URLEncoder.encode("Фото", "UTF-8"), cookie, "multipart/form-data; boundary=" + boundary, mp.toByteArray(), null);
        eq("upload redirect", r.code, 303);
        eq("upload 1", Arrays.equals(Files.readAllBytes(new File(root, "Фото/звіт.txt").toPath()), up1), true);
        eq("upload 2", Arrays.equals(Files.readAllBytes(new File(root, "Фото/дані.bin").toPath()), up2), true);

        // нова папка
        r = req("POST", base + "/m/0/", cookie, "application/x-www-form-urlencoded", ("name=" + URLEncoder.encode("Нова папка", "UTF-8")).getBytes(), null);
        eq("mkdir", new File(root, "Нова папка").isDirectory(), true);

        // папка в ZIP
        r = req("GET", base + "/z/0/" + URLEncoder.encode("Фото", "UTF-8"), cookie, null, null, null);
        int entries = 0;
        boolean videoOk = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.body))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                entries++;
                if (e.getName().equals("Фото/відео.mp4")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = z.read(buf)) > 0) b.write(buf, 0, n);
                    videoOk = Arrays.equals(b.toByteArray(), big);
                }
            }
        }
        eq("zip entries", entries, 4);
        eq("zip video", videoOk, true);

        // вихід за межі кореня
        r = req("GET", base + "/b/0/..%2F..%2Fetc%2Fpasswd", cookie, null, null, null);
        eq("traversal blocked", r.code == 403 || r.code == 404, true);
        r = req("GET", base + "/b/5/", cookie, null, null, null);
        eq("bad root", r.code, 404);

        s.stop();
        System.out.println((failed == 0 ? "OK" : "ПОМИЛКИ") + ": пройдено " + passed + ", не пройдено " + failed);
        if (failed > 0) System.exit(1);
    }
}
