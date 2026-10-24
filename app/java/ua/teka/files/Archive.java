package ua.teka.files;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Вбудований архіватор: ZIP (і все, що є ZIP усередині — APK, JAR, DOCX, EPUB…), TAR, TAR.GZ, GZ.
 * Створення, перегляд вмісту без розпакування, розпакування всього або вибраного.
 * Чистий Java без Android — покритий тестами на JVM.
 */
public final class Archive {
    private Archive() {}

    /** Прогрес: повертає false, щоб скасувати. */
    public interface Progress {
        boolean tick(long bytesDone, String current);
    }

    public static final class Entry {
        public String name;
        public long size, csize = -1, mtime;
        public boolean dir;
    }

    public static final class Cancelled extends IOException {
        public Cancelled() { super("cancelled"); }
    }

    public static final class Encrypted extends IOException {
        public Encrypted() { super("encrypted"); }
    }

    static final String[] ZIP_EXT = {"zip", "jar", "apk", "apks", "xapk", "apkm", "aar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "cbz", "xpi", "ipa", "war", "kmz", "3mf"};

    /** zip | tar | tgz | gz | null */
    public static String kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return "tgz";
        if (n.endsWith(".tar")) return "tar";
        if (n.endsWith(".gz")) return "gz";
        String e = Names.ext(n);
        for (String z : ZIP_EXT) if (z.equals(e)) return "zip";
        return null;
    }

    // ================================================================ кодування назв у ZIP

    /**
     * Старі архіви з Windows зберігають кириличні назви в CP866 без позначки UTF-8.
     * Спершу пробуємо UTF-8; якщо Java відкидає назви — CP866.
     */
    public static String zipCharset(File f) {
        try (ZipFile z = new ZipFile(f, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) en.nextElement();
            return "UTF-8";
        } catch (Exception e) {
            return "IBM866";
        }
    }

    static ZipFile openZip(File f, String charset) throws IOException {
        Charset cs;
        try { cs = Charset.forName(charset == null || charset.isEmpty() ? zipCharset(f) : charset); }
        catch (Exception e) { cs = StandardCharsets.UTF_8; }
        return new ZipFile(f, cs);
    }

    // ================================================================ перегляд

    public static List<Entry> list(File f, String charset) throws IOException {
        String k = kind(f.getName());
        List<Entry> out = new ArrayList<>();
        if ("zip".equals(k)) {
            if (zipEncrypted(f)) throw new Encrypted();
            try (ZipFile z = openZip(f, charset)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    Entry e = new Entry();
                    e.name = clean(ze.getName());
                    if (e.name.isEmpty()) continue;
                    e.dir = ze.isDirectory();
                    e.size = Math.max(0, ze.getSize());
                    e.csize = ze.getCompressedSize();
                    e.mtime = ze.getTime();
                    out.add(e);
                }
            }
        } else if ("tar".equals(k) || "tgz".equals(k)) {
            try (InputStream in = tarStream(f, k)) {
                TarIn t = new TarIn(in);
                Entry e;
                while ((e = t.next()) != null) {
                    out.add(e);
                    t.skipData();
                }
            }
        } else if ("gz".equals(k)) {
            Entry e = new Entry();
            e.name = f.getName().substring(0, f.getName().length() - 3);
            e.size = gzipSize(f);
            e.csize = f.length();
            e.mtime = f.lastModified();
            out.add(e);
        } else {
            throw new IOException("unsupported");
        }
        return out;
    }

    /** Розпакований розмір GZ з останніх 4 байтів (ISIZE, за модулем 4 ГБ). */
    static long gzipSize(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 18) return -1;
            r.seek(r.length() - 4);
            int b0 = r.read(), b1 = r.read(), b2 = r.read(), b3 = r.read();
            return (b0 | (b1 << 8) | (b2 << 16) | ((long) b3 << 24)) & 0xffffffffL;
        } catch (IOException e) {
            return -1;
        }
    }

    static String clean(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("./")) n = n.substring(2);
        return n;
    }

    static InputStream tarStream(File f, String k) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16);
        return "tgz".equals(k) ? new GZIPInputStream(in, 1 << 16) : in;
    }

    // ================================================================ розпакування

    /**
     * Розпаковує архів у папку dest. only — набір шляхів (файли чи папки з «/» в кінці), або null — усе.
     * conflict: rename | replace | skip. Повертає кількість створених файлів.
     */
    public static int extract(File archive, File dest, Set<String> only, String charset, String conflict, Progress p) throws IOException {
        String k = kind(archive.getName());
        if (!dest.exists() && !dest.mkdirs()) throw new IOException("Не вдалося створити папку " + dest);
        long[] done = {0};
        int count = 0;
        if ("zip".equals(k)) {
            if (zipEncrypted(archive)) throw new Encrypted();
            try (ZipFile z = openZip(archive, charset)) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = clean(ze.getName());
                    if (name.isEmpty() || !wanted(name, only)) continue;
                    File out = target(dest, name, only);
                    if (out == null) continue;
                    if (ze.isDirectory()) { out.mkdirs(); continue; }
                    out = resolveConflict(out, conflict);
                    if (out == null) continue;
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = z.getInputStream(ze)) {
                        copy(in, out, done, p, name);
                    }
                    if (ze.getTime() > 0) out.setLastModified(ze.getTime());
                    count++;
                }
            }
        } else if ("tar".equals(k) || "tgz".equals(k)) {
            try (InputStream in = tarStream(archive, k)) {
                TarIn t = new TarIn(in);
                Entry e;
                while ((e = t.next()) != null) {
                    if (!wanted(e.name, only)) { t.skipData(); continue; }
                    File out = target(dest, e.name, only);
                    if (out == null) { t.skipData(); continue; }
                    if (e.dir) { out.mkdirs(); t.skipData(); continue; }
                    if (t.isLink()) { t.skipData(); continue; }
                    out = resolveConflict(out, conflict);
                    if (out == null) { t.skipData(); continue; }
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    copy(t.data(), out, done, p, e.name);
                    t.skipData();
                    if (e.mtime > 0) out.setLastModified(e.mtime);
                    count++;
                }
            }
        } else if ("gz".equals(k)) {
            String name = archive.getName().substring(0, archive.getName().length() - 3);
            File out = resolveConflict(new File(dest, name), conflict);
            if (out != null) {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive), 1 << 16))) {
                    copy(in, out, done, p, name);
                }
                count++;
            }
        } else {
            throw new IOException("unsupported");
        }
        return count;
    }

    /**
     * Чи зашифровано перший запис ZIP (біт 0 загального прапорця локального заголовка).
     * java.util.zip не розшифровує ні ZipCrypto, ні AES, тож такі архіви не відкриваємо.
     */
    public static boolean zipEncrypted(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[8];
            int n = 0;
            while (n < 8) { int r = in.read(h, n, 8 - n); if (r < 0) return false; n += r; }
            if ((h[0] & 0xff) != 0x50 || (h[1] & 0xff) != 0x4b || h[2] != 3 || h[3] != 4) return false;
            return (h[6] & 1) != 0;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean wanted(String name, Set<String> only) {
        if (only == null || only.isEmpty()) return true;
        for (String o : only) {
            if (name.equals(o)) return true;
            if (o.endsWith("/") && name.startsWith(o)) return true;
            if (name.equals(o + "/")) return true;
        }
        return false;
    }

    /**
     * Шлях у папці призначення. Якщо розпаковуємо вибрані елементи з підпапки архіву,
     * кладемо їх у dest без батьківських папок архіву. Захист від «zip slip»: шлях не може вийти за dest.
     */
    static File target(File dest, String name, Set<String> only) throws IOException {
        String rel = name;
        if (only != null && !only.isEmpty()) {
            String best = null;
            for (String o : only) {
                String base = o.endsWith("/") ? o.substring(0, o.length() - 1) : o;
                int slash = base.lastIndexOf('/');
                String parent = slash >= 0 ? base.substring(0, slash + 1) : "";
                if ((name.equals(o) || name.startsWith(base + "/") || name.equals(base)) && (best == null || parent.length() > best.length())) best = parent;
            }
            if (best != null && rel.startsWith(best)) rel = rel.substring(best.length());
        }
        if (rel.isEmpty()) return null;
        File out = new File(dest, rel);
        String d = dest.getCanonicalPath();
        String o = out.getCanonicalPath();
        if (!o.startsWith(d + File.separator) && !o.equals(d)) return null;
        return out;
    }

    static File resolveConflict(File out, String conflict) {
        if (!out.exists()) return out;
        if ("skip".equals(conflict)) return null;
        if ("replace".equals(conflict)) { out.delete(); return out; }
        return new File(out.getParentFile(), Names.unique(out.getParentFile(), out.getName()));
    }

    static void copy(InputStream in, File out, long[] done, Progress p, String name) throws IOException {
        byte[] buf = new byte[1 << 16];
        boolean ok = false;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done[0] += n;
                if (p != null && !p.tick(done[0], name)) throw new Cancelled();
            }
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
    }

    // ================================================================ створення

    /** Сумарний розмір файлів (для прогресу). */
    public static long total(List<File> files) {
        long t = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) {
                    List<File> l = new ArrayList<>();
                    for (File c : ch) l.add(c);
                    t += total(l);
                }
            } else t += f.length();
        }
        return t;
    }

    /** ZIP із файлів і папок; назви всередині — відносно батьківської папки кожного джерела. level 0–9. */
    public static void zip(List<File> sources, File out, int level, Progress p) throws IOException {
        long[] done = {0};
        boolean ok = false;
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16), StandardCharsets.UTF_8)) {
            z.setLevel(Math.max(0, Math.min(9, level)));
            for (File s : sources) addZip(z, s, s.getName(), out, done, p);
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
    }

    static void addZip(ZipOutputStream z, File f, String name, File out, long[] done, Progress p) throws IOException {
        if (f.getCanonicalPath().equals(out.getCanonicalPath())) return; // не пакуємо сам архів
        if (f.isDirectory()) {
            ZipEntry e = new ZipEntry(name + "/");
            e.setTime(f.lastModified());
            z.putNextEntry(e);
            z.closeEntry();
            File[] ch = f.listFiles();
            if (ch == null) return;
            java.util.Arrays.sort(ch);
            for (File c : ch) addZip(z, c, name + "/" + c.getName(), out, done, p);
            return;
        }
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        z.putNextEntry(e);
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                z.write(buf, 0, n);
                done[0] += n;
                if (p != null && !p.tick(done[0], name)) throw new Cancelled();
            }
        }
        z.closeEntry();
    }

    /** TAR або TAR.GZ (POSIX ustar, довгі назви — записом GNU LongLink). */
    public static void tar(List<File> sources, File out, boolean gzip, Progress p) throws IOException {
        long[] done = {0};
        boolean ok = false;
        OutputStream raw = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
        try (OutputStream os = gzip ? new GZIPOutputStream(raw, 1 << 16) : raw) {
            TarOut t = new TarOut(os);
            for (File s : sources) addTar(t, s, s.getName(), out, done, p);
            t.finish();
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
    }

    static void addTar(TarOut t, File f, String name, File out, long[] done, Progress p) throws IOException {
        if (f.getCanonicalPath().equals(out.getCanonicalPath())) return;
        if (f.isDirectory()) {
            t.header(name + "/", 0, f.lastModified(), true);
            File[] ch = f.listFiles();
            if (ch == null) return;
            java.util.Arrays.sort(ch);
            for (File c : ch) addTar(t, c, name + "/" + c.getName(), out, done, p);
            return;
        }
        long len = f.length();
        t.header(name, len, f.lastModified(), false);
        byte[] buf = new byte[1 << 16];
        long left = len;
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                t.write(buf, n);
                left -= n;
                done[0] += n;
                if (p != null && !p.tick(done[0], name)) throw new Cancelled();
            }
        }
        // файл змінився під час пакування — доповнюємо нулями, щоб архів лишився цілим
        byte[] zero = new byte[8192];
        while (left > 0) { int n = (int) Math.min(zero.length, left); t.write(zero, n); left -= n; }
        t.pad();
    }

    /** GZ одного файлу. */
    public static void gzip(File src, File out, Progress p) throws IOException {
        long[] done = {0};
        boolean ok = false;
        try (InputStream in = new FileInputStream(src);
             OutputStream os = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16), 1 << 16)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done[0] += n;
                if (p != null && !p.tick(done[0], src.getName())) throw new Cancelled();
            }
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
    }

    // ================================================================ TAR: запис

    static final class TarOut {
        final OutputStream os;
        long written;

        TarOut(OutputStream os) { this.os = os; }

        void header(String name, long size, long mtime, boolean dir) throws IOException {
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            if (nb.length > 100) {
                // GNU: окремий запис ././@LongLink з повною назвою
                byte[] h = block("././@LongLink", nb.length + 1, 0, 'L');
                os.write(h);
                written += 512;
                write(nb, nb.length);
                write(new byte[]{0}, 1);
                pad();
            }
            os.write(block(name, size, mtime, dir ? '5' : '0'));
            written += 512;
        }

        static byte[] block(String name, long size, long mtime, char type) {
            byte[] h = new byte[512];
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(nb, 0, h, 0, Math.min(100, nb.length));
            octal(h, 100, 8, type == '5' ? 0755 : 0644);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            if (size > 077777777777L) {
                // розмір понад 8 ГБ — у двійковому вигляді (base-256)
                h[124] = (byte) 0x80;
                for (int i = 0; i < 8; i++) h[135 - i] = (byte) (size >>> (8 * i));
            } else octal(h, 124, 12, size);
            octal(h, 136, 12, Math.max(0, mtime / 1000));
            for (int i = 148; i < 156; i++) h[i] = ' ';
            h[156] = (byte) type;
            byte[] magic = "ustar\u000000".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(magic, 0, h, 257, magic.length);
            byte[] user = "teka".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(user, 0, h, 265, user.length);
            System.arraycopy(user, 0, h, 297, user.length);
            long sum = 0;
            for (byte b : h) sum += b & 0xff;
            String cs = String.format(Locale.ROOT, "%06o", sum);
            for (int i = 0; i < 6; i++) h[148 + i] = (byte) cs.charAt(i);
            h[154] = 0;
            h[155] = ' ';
            return h;
        }

        static void octal(byte[] h, int off, int len, long v) {
            String s = Long.toOctalString(v);
            int digits = len - 1;
            while (s.length() < digits) s = "0" + s;
            if (s.length() > digits) s = s.substring(s.length() - digits);
            for (int i = 0; i < digits; i++) h[off + i] = (byte) s.charAt(i);
            h[off + digits] = 0;
        }

        void write(byte[] b, int n) throws IOException {
            os.write(b, 0, n);
            written += n;
        }

        void pad() throws IOException {
            int r = (int) (written % 512);
            if (r != 0) {
                os.write(new byte[512 - r]);
                written += 512 - r;
            }
        }

        void finish() throws IOException {
            os.write(new byte[1024]);
            written += 1024;
            int r = (int) (written % 10240);
            if (r != 0) os.write(new byte[10240 - r]);
            os.flush();
        }
    }

    // ================================================================ TAR: читання

    static final class TarIn {
        final InputStream in;
        long remaining, padding;
        char type;
        String longName, paxPath;

        TarIn(InputStream in) { this.in = in; }

        Entry next() throws IOException {
            while (true) {
                byte[] h = new byte[512];
                if (!readFully(h)) return null;
                boolean empty = true;
                for (byte b : h) if (b != 0) { empty = false; break; }
                if (empty) return null;
                long size = num(h, 124, 12);
                type = (char) h[156];
                remaining = size;
                padding = (512 - size % 512) % 512;
                if (type == 'L' || type == 'x' || type == 'g') {
                    byte[] data = new byte[(int) Math.min(size, 1 << 20)];
                    readExact(data, data.length);
                    remaining -= data.length;
                    skipData();
                    if (type == 'L') longName = cstr(data, 0, data.length);
                    else if (type == 'x') paxPath = pax(data, "path");
                    continue;
                }
                Entry e = new Entry();
                String name = cstr(h, 0, 100);
                String magic = new String(h, 257, 5, StandardCharsets.US_ASCII);
                if (magic.equals("ustar")) {
                    String prefix = cstr(h, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                if (longName != null) { name = longName; longName = null; }
                if (paxPath != null) { name = paxPath; paxPath = null; }
                e.name = clean(name);
                e.size = size;
                e.mtime = num(h, 136, 12) * 1000;
                e.dir = type == '5' || e.name.endsWith("/");
                if (e.name.isEmpty()) { skipData(); continue; }
                return e;
            }
        }

        boolean isLink() { return type == '1' || type == '2'; }

        /** Потік даних поточного запису (не більше його розміру). */
        InputStream data() {
            final TarIn self = this;
            return new LimitedIn(self);
        }

        void skipData() throws IOException {
            long n = remaining + padding;
            remaining = 0;
            padding = 0;
            byte[] buf = new byte[8192];
            while (n > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r < 0) throw new EOFException();
                n -= r;
            }
        }

        boolean readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) { if (off == 0) return false; throw new EOFException(); }
                off += r;
            }
            return true;
        }

        void readExact(byte[] b, int len) throws IOException {
            int off = 0;
            while (off < len) {
                int r = in.read(b, off, len - off);
                if (r < 0) throw new EOFException();
                off += r;
            }
        }

        static String cstr(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }

        static long num(byte[] b, int off, int len) {
            if ((b[off] & 0x80) != 0) {
                long v = 0;
                for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xff);
                return v;
            }
            String s = cstr(b, off, len).trim();
            if (s.isEmpty()) return 0;
            try { return Long.parseLong(s, 8); } catch (NumberFormatException e) { return 0; }
        }

        static String pax(byte[] data, String key) {
            String s = new String(data, StandardCharsets.UTF_8);
            for (String line : s.split("\n")) {
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String kv = line.substring(sp + 1);
                if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
            }
            return null;
        }
    }

    /** Потік, обмежений розміром поточного запису TAR. */
    static final class LimitedIn extends InputStream {
        final TarIn t;
        LimitedIn(TarIn t) { this.t = t; }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (t.remaining <= 0) return -1;
            int n = t.in.read(b, off, (int) Math.min(len, t.remaining));
            if (n < 0) throw new EOFException();
            t.remaining -= n;
            return n;
        }

        @Override
        public void close() {}
    }
}
