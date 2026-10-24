package ua.teka.files;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Довгі операції у фоні з прогресом: копіювання, переміщення, видалення, кошик, архіви,
 * конвертація, розмір папок, пошук, контрольні суми, аналіз сховища, перейменування групою.
 */
final class Ops {
    interface Sink { void emit(String id, String json); }

    final Context c;
    final Sink sink;
    final ExecutorService ex = Executors.newFixedThreadPool(3);
    final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();

    Ops(Context c, Sink sink) {
        this.c = c.getApplicationContext();
        this.sink = sink;
    }

    void start(String id, String kind, JSONObject p) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancels.put(id, cancel);
        ex.execute(new Job(this, id, kind, p, cancel));
    }

    void cancel(String id) {
        AtomicBoolean b = cancels.get(id);
        if (b != null) b.set(true);
    }

    void shutdown() {
        for (AtomicBoolean b : cancels.values()) b.set(true);
        ex.shutdownNow();
    }

    /** Повідомити медіатеку Android про змінені й видалені файли, щоб галерея й музика оновилися. */
    void scan(Iterable<String> paths) {
        List<String> l = new ArrayList<>();
        for (String p : paths) { l.add(p); if (l.size() >= 5000) break; }
        if (l.isEmpty()) return;
        try { MediaScannerConnection.scanFile(c, l.toArray(new String[0]), null, null); } catch (Exception ignored) {}
    }

    static final class Cancelled extends RuntimeException {}

    // ================================================================ завдання

    static final class Job implements Runnable, Archive.Progress {
        final Ops ops;
        final String id, kind;
        final JSONObject p;
        final AtomicBoolean cancel;
        long done, total, base;
        int files, filesTotal;
        String cur = "";
        long lastEmit;
        final Set<String> touched = new HashSet<>();

        Job(Ops ops, String id, String kind, JSONObject p, AtomicBoolean cancel) {
            this.ops = ops; this.id = id; this.kind = kind; this.p = p; this.cancel = cancel;
        }

        void emit(JSONObject o) { ops.sink.emit(id, o.toString()); }

        void check() { if (cancel.get()) throw new Cancelled(); }

        void progress(boolean force) {
            check();
            long now = SystemClock.uptimeMillis();
            if (!force && now - lastEmit < 200) return;
            lastEmit = now;
            try {
                emit(new JSONObject().put("state", "run").put("kind", kind).put("done", done).put("total", total)
                    .put("files", files).put("filesTotal", filesTotal).put("cur", cur));
            } catch (Exception ignored) {}
        }

        @Override
        public boolean tick(long bytesDone, String current) {
            done = base + bytesDone;
            cur = current;
            if (cancel.get()) return false;
            progress(false);
            return true;
        }

        @Override
        public void run() {
            JSONObject res = new JSONObject();
            try {
                progress(true);
                switch (kind) {
                    case "copy": case "move": copyMove(res, "move".equals(kind)); break;
                    case "delete": delete(res); break;
                    case "trash": trash(res); break;
                    case "restore": restore(res); break;
                    case "purge": purge(res); break;
                    case "trashClean": trashClean(res); break;
                    case "size": size(res); break;
                    case "search": search(res); break;
                    case "hash": hash(res); break;
                    case "analyze": analyze(res); break;
                    case "zip": case "tar": case "tgz": compress(res); break;
                    case "extract": extract(res); break;
                    case "convert": Convert.run(ops.c, this, p, res); break;
                    case "stats": res.put("stats", Fs.categoryStats(ops.c)); break;
                    case "apkBackup": Apps.backup(ops.c, this, p, res); break;
                    case "apps": res.put("apps", Apps.list(ops.c, this)); break;
                    case "installBundle": Apps.installBundle(ops.c, this, p, res); break;
                    case "trashList": res.put("items", trashList(ops.c)); break;
                    case "renameMany": renameMany(res); break;
                    default: throw new IOException("unknown task " + kind);
                }
                res.put("state", "done").put("kind", kind);
            } catch (Cancelled | Archive.Cancelled e) {
                try { res.put("state", "cancelled").put("kind", kind); } catch (Exception ignored) {}
            } catch (Archive.Encrypted e) {
                try { res.put("state", "error").put("kind", kind).put("err", "Архів захищено паролем — такі архіви «Тека» поки не відкриває"); } catch (Exception ignored) {}
            } catch (OutOfMemoryError e) {
                try { res.put("state", "error").put("kind", kind).put("err", "Забракло пам’яті — спробуйте менший файл або розмір"); } catch (Exception ignored) {}
            } catch (Throwable e) {
                String m = e.getMessage();
                if (m != null && m.contains("ENOSPC")) m = "Немає вільного місця";
                else if (m != null && (m.contains("EACCES") || m.contains("EPERM") || m.contains("Permission denied"))) m = "Немає доступу";
                try { res.put("state", "error").put("kind", kind).put("err", m == null ? e.toString() : m); } catch (Exception ignored) {}
            } finally {
                ops.cancels.remove(id);
                if (!touched.isEmpty()) ops.scan(touched);
                try { res.put("done", done).put("total", total).put("files", files); } catch (Exception ignored) {}
                emit(res);
            }
        }

        // ---------------------------------------------------------- допоміжні

        List<File> files(String key) throws Exception {
            List<File> l = new ArrayList<>();
            JSONArray a = p.getJSONArray(key);
            for (int i = 0; i < a.length(); i++) l.add(new File(a.getString(i)));
            return l;
        }

        void measure(File f) {
            check();
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) for (File x : ch) measure(x);
            } else {
                total += f.length();
                filesTotal++;
            }
        }

        void copyFile(File s, File t) throws IOException {
            cur = s.getName();
            byte[] buf = new byte[1 << 18];
            boolean ok = false;
            try (InputStream in = new FileInputStream(s); OutputStream out = new BufferedOutputStream(new FileOutputStream(t), 1 << 18)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    progress(false);
                }
                ok = true;
            } finally {
                if (!ok) t.delete();
            }
            t.setLastModified(s.lastModified());
            files++;
            touched.add(t.getAbsolutePath());
        }

        /** Копіювання дерева. Якщо файл у призначенні вже є — замінюємо (злиття папок). */
        void copyTree(File s, File t) throws IOException {
            check();
            if (s.isDirectory()) {
                if (!t.exists() && !t.mkdirs()) throw new IOException("Не вдалося створити папку " + t.getName() + ": немає доступу");
                File[] ch = s.listFiles();
                if (ch != null) for (File x : ch) copyTree(x, new File(t, x.getName()));
                t.setLastModified(s.lastModified());
            } else {
                if (t.isDirectory()) deleteTree(t, false);
                copyFile(s, t);
            }
        }

        void deleteTree(File f, boolean count) {
            check();
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) for (File x : ch) deleteTree(x, count);
            } else if (count) {
                cur = f.getName();
                files++;
                progress(false);
            }
            touched.add(f.getAbsolutePath());
            f.delete();
        }

        // ---------------------------------------------------------- копіювання і переміщення

        void copyMove(JSONObject res, boolean move) throws Exception {
            List<File> srcs = files("srcs");
            File dest = new File(p.getString("dest"));
            String conflict = p.optString("conflict", "rename");
            if (!dest.isDirectory()) throw new IOException("Папки призначення немає");
            for (File s : srcs) {
                if (s.isDirectory() && Names.inside(dest, s)) throw new IOException("Не можна " + (move ? "перемістити" : "скопіювати") + " папку саму в себе");
            }
            for (File s : srcs) measure(s);
            progress(true);
            int skipped = 0;
            JSONArray created = new JSONArray();
            for (File s : srcs) {
                check();
                if (!s.exists()) continue;
                boolean sameDir = s.getParentFile() != null && s.getParentFile().getAbsolutePath().equals(dest.getAbsolutePath());
                if (move && sameDir) continue;
                File t = new File(dest, s.getName());
                if (t.exists() || sameDir) {
                    if ("skip".equals(conflict) && !sameDir) { skipped++; continue; }
                    if ("rename".equals(conflict) || sameDir) t = new File(dest, Names.unique(dest, s.getName()));
                    else if ("replace".equals(conflict) && !(s.isDirectory() && t.isDirectory())) deleteTree(t, false);
                }
                if (move && !t.exists() && s.renameTo(t)) {
                    // та сама файлова система — миттєво
                    if (s.isDirectory()) { files += countFiles(t); }
                    else files++;
                    done += sizeOf(t);
                    touched.add(s.getAbsolutePath());
                    touched.add(t.getAbsolutePath());
                    progress(false);
                } else {
                    copyTree(s, t);
                    if (move) deleteTree(s, false);
                }
                created.put(t.getAbsolutePath());
            }
            res.put("skipped", skipped).put("paths", created);
        }

        static int countFiles(File f) {
            if (!f.isDirectory()) return 1;
            int n = 0;
            File[] ch = f.listFiles();
            if (ch != null) for (File x : ch) n += countFiles(x);
            return n;
        }

        static long sizeOf(File f) {
            if (!f.isDirectory()) return f.length();
            long n = 0;
            File[] ch = f.listFiles();
            if (ch != null) for (File x : ch) n += sizeOf(x);
            return n;
        }

        void delete(JSONObject res) throws Exception {
            List<File> srcs = files("paths");
            for (File s : srcs) filesTotal += countFiles(s);
            progress(true);
            int failed = 0;
            for (File s : srcs) {
                deleteTree(s, true);
                if (s.exists()) failed++;
            }
            res.put("failed", failed);
        }

        // ---------------------------------------------------------- кошик

        File trashDir(File f) {
            File root = Fs.volumeOf(ops.c, f);
            if (root == null) root = f.getParentFile();
            File t = new File(root, Fs.TRASH);
            if (!t.exists()) {
                t.mkdirs();
                try { new File(t, ".nomedia").createNewFile(); } catch (IOException ignored) {}
            }
            return t;
        }

        static JSONObject readIndex(File trash) {
            File idx = new File(trash, ".index.json");
            if (!idx.exists()) return new JSONObject();
            try (InputStream in = new FileInputStream(idx)) {
                byte[] b = new byte[(int) idx.length()];
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                return new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static void writeIndex(File trash, JSONObject idx) throws IOException {
            File tmp = new File(trash, ".index.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(idx.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(trash, ".index.json"))) throw new IOException("index");
        }

        void trash(JSONObject res) throws Exception {
            List<File> srcs = files("paths");
            filesTotal = srcs.size();
            int moved = 0;
            for (File s : srcs) {
                check();
                File tr = trashDir(s);
                JSONObject idx = readIndex(tr);
                String entry = System.currentTimeMillis() + "_" + s.getName();
                File t = new File(tr, entry);
                cur = s.getName();
                boolean dir = s.isDirectory();
                long size = dir ? -1 : s.length();
                if (!s.renameTo(t)) {
                    copyTree(s, t);
                    deleteTree(s, false);
                }
                touched.add(s.getAbsolutePath());
                idx.put(entry, new JSONObject().put("path", s.getAbsolutePath()).put("time", System.currentTimeMillis()).put("dir", dir).put("size", size));
                writeIndex(tr, idx);
                moved++;
                files++;
                progress(false);
            }
            res.put("moved", moved);
        }

        void restore(JSONObject res) throws Exception {
            JSONArray es = p.getJSONArray("entries");
            filesTotal = es.length();
            JSONArray restored = new JSONArray();
            for (int i = 0; i < es.length(); i++) {
                check();
                JSONObject e = es.getJSONObject(i);
                File tr = new File(e.getString("trash"));
                String name = e.getString("entry");
                JSONObject idx = readIndex(tr);
                JSONObject meta = idx.optJSONObject(name);
                File src = new File(tr, name);
                if (meta == null || !src.exists()) continue;
                File orig = new File(meta.getString("path"));
                File parent = orig.getParentFile();
                if (parent != null) parent.mkdirs();
                File t = orig.exists() ? new File(parent, Names.unique(parent, orig.getName())) : orig;
                if (!src.renameTo(t)) { copyTree(src, t); deleteTree(src, false); }
                touched.add(t.getAbsolutePath());
                idx.remove(name);
                writeIndex(tr, idx);
                restored.put(t.getAbsolutePath());
                files++;
                progress(false);
            }
            res.put("paths", restored);
        }

        void purge(JSONObject res) throws Exception {
            JSONArray es = p.getJSONArray("entries");
            filesTotal = es.length();
            for (int i = 0; i < es.length(); i++) {
                check();
                JSONObject e = es.getJSONObject(i);
                File tr = new File(e.getString("trash"));
                String name = e.getString("entry");
                deleteTree(new File(tr, name), false);
                JSONObject idx = readIndex(tr);
                idx.remove(name);
                writeIndex(tr, idx);
                files++;
                progress(false);
            }
            touched.clear();
        }

        void trashClean(JSONObject res) throws Exception {
            long days = p.optLong("days", 30);
            long limit = System.currentTimeMillis() - days * 86400000L;
            int n = 0;
            for (Fs.Vol v : Fs.vols(ops.c)) {
                File tr = new File(v.dir, Fs.TRASH);
                if (!tr.isDirectory()) continue;
                JSONObject idx = readIndex(tr);
                List<String> drop = new ArrayList<>();
                Iterator<String> it = idx.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject m = idx.optJSONObject(k);
                    if (m == null || m.optLong("time") < limit || !new File(tr, k).exists()) drop.add(k);
                }
                for (String k : drop) {
                    File f = new File(tr, k);
                    if (f.exists()) { deleteTree(f, false); n++; }
                    idx.remove(k);
                }
                if (!drop.isEmpty()) writeIndex(tr, idx);
            }
            touched.clear();
            res.put("purged", n);
        }

        // ---------------------------------------------------------- розмір, пошук, хеші, аналіз

        void size(JSONObject res) throws Exception {
            List<File> srcs = files("paths");
            long[] acc = new long[3]; // байти, файли, папки
            for (File s : srcs) walkSize(s, acc);
            res.put("size", acc[0]).put("fileCount", acc[1]).put("dirCount", acc[2]);
        }

        void walkSize(File f, long[] acc) {
            check();
            if (f.isDirectory()) {
                acc[2]++;
                File[] ch = f.listFiles();
                if (ch != null) for (File x : ch) walkSize(x, acc);
            } else {
                acc[0] += f.length();
                acc[1]++;
            }
            done = acc[0];
            files = (int) acc[1];
            progress(false);
        }

        void search(JSONObject res) throws Exception {
            File root = new File(p.getString("root"));
            String q = p.getString("q").trim();
            boolean hidden = p.optBoolean("hidden");
            boolean content = p.optBoolean("content");
            String type = p.optString("type", "");
            Pattern glob = null;
            String low = q.toLowerCase(Locale.ROOT);
            if (q.contains("*") || q.contains("?")) {
                StringBuilder r = new StringBuilder();
                for (char ch : q.toCharArray()) {
                    if (ch == '*') r.append(".*");
                    else if (ch == '?') r.append('.');
                    else r.append(Pattern.quote(String.valueOf(ch)));
                }
                glob = Pattern.compile(r.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            }
            JSONArray batch = new JSONArray();
            int found = 0;
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(root);
            long lastBatch = SystemClock.uptimeMillis();
            while (!stack.isEmpty() && found < 1000) {
                check();
                File d = stack.pop();
                File[] ch = d.listFiles();
                if (ch == null) continue;
                for (File f : ch) {
                    String name = f.getName();
                    if (!hidden && name.startsWith(".")) continue;
                    if (name.equals(Fs.TRASH)) continue;
                    if (f.isDirectory()) stack.push(f);
                    files++;
                    boolean typeOk = type.isEmpty() || (type.equals("dir") ? f.isDirectory() : (!f.isDirectory() && Fs.category(name).equals(type)));
                    if (!typeOk) continue;
                    boolean hit = glob != null ? glob.matcher(name).matches() : name.toLowerCase(Locale.ROOT).contains(low);
                    if (!hit && content && !f.isDirectory() && f.length() < (2 << 20) && textual(name)) hit = contains(f, low);
                    if (hit) {
                        batch.put(Fs.item(f));
                        found++;
                    }
                }
                cur = d.getName();
                long now = SystemClock.uptimeMillis();
                if (batch.length() > 0 && now - lastBatch > 300) {
                    emit(new JSONObject().put("state", "run").put("kind", kind).put("items", batch).put("files", files));
                    batch = new JSONArray();
                    lastBatch = now;
                } else progress(false);
            }
            res.put("items", batch).put("found", found).put("limit", found >= 1000);
        }

        static boolean textual(String name) {
            String e = Names.ext(name);
            return e.matches("txt|md|csv|json|xml|html?|css|js|ts|java|kt|py|c|cpp|h|sh|ini|cfg|conf|log|yml|yaml|toml|srt|sql|php|rb|go|rs|gradle|properties");
        }

        static boolean contains(File f, String low) {
            try (InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                Encodings.Text t = Encodings.read(b, off, "auto");
                if (t.binary) return false;
                return t.text.toLowerCase(Locale.ROOT).contains(low);
            } catch (Exception e) {
                return false;
            }
        }

        void hash(JSONObject res) throws Exception {
            File f = new File(p.getString("path"));
            total = f.length();
            MessageDigest md5 = MessageDigest.getInstance("MD5"), sha1 = MessageDigest.getInstance("SHA-1"), sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 18];
            try (InputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md5.update(buf, 0, n);
                    sha1.update(buf, 0, n);
                    sha256.update(buf, 0, n);
                    done += n;
                    progress(false);
                }
            }
            res.put("md5", hex(md5.digest())).put("sha1", hex(sha1.digest())).put("sha256", hex(sha256.digest()));
        }

        static String hex(byte[] d) {
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        }

        static final class Big {
            final String path; final long size;
            Big(String path, long size) { this.path = path; this.size = size; }
        }

        void analyze(JSONObject res) throws Exception {
            File root = new File(p.getString("root"));
            File[] top = root.listFiles();
            if (top == null) throw new IOException("Немає доступу");
            PriorityQueue<Big> big = new PriorityQueue<>(64, (x, y) -> Long.compare(x.size, y.size));
            Map<String, long[]> types = new java.util.HashMap<>();
            JSONArray children = new JSONArray();
            long all = 0;
            for (File t : top) {
                check();
                cur = t.getName();
                long[] acc = new long[2];
                walkAnalyze(t, acc, big, types);
                all += acc[0];
                if (!t.getName().equals(Fs.TRASH)) children.put(new JSONObject().put("name", t.getName()).put("path", t.getAbsolutePath()).put("dir", t.isDirectory()).put("size", acc[0]).put("files", acc[1]));
            }
            JSONArray bigs = new JSONArray();
            List<Big> sorted = new ArrayList<>(big);
            java.util.Collections.sort(sorted, (x, y) -> Long.compare(y.size, x.size));
            for (Big b : sorted) bigs.put(new JSONObject().put("path", b.path).put("name", new File(b.path).getName()).put("size", b.size));
            JSONObject ty = new JSONObject();
            for (Map.Entry<String, long[]> e : types.entrySet()) ty.put(e.getKey(), new JSONObject().put("size", e.getValue()[0]).put("count", e.getValue()[1]));
            res.put("size", all).put("children", children).put("largest", bigs).put("types", ty)
                .put("capacity", root.getTotalSpace()).put("free", root.getUsableSpace());
        }

        void walkAnalyze(File f, long[] acc, PriorityQueue<Big> big, Map<String, long[]> types) {
            if (cancel.get()) throw new Cancelled();
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) for (File x : ch) walkAnalyze(x, acc, big, types);
                return;
            }
            long len = f.length();
            acc[0] += len;
            acc[1]++;
            files++;
            done += len;
            String cat = f.getAbsolutePath().contains("/" + Fs.TRASH + "/") ? "trash" : Fs.category(f.getName());
            long[] t = types.get(cat);
            if (t == null) { t = new long[2]; types.put(cat, t); }
            t[0] += len;
            t[1]++;
            if (big.size() < 50) big.add(new Big(f.getAbsolutePath(), len));
            else if (len > big.peek().size) { big.poll(); big.add(new Big(f.getAbsolutePath(), len)); }
            progress(false);
        }

        // ---------------------------------------------------------- архіви

        void compress(JSONObject res) throws Exception {
            List<File> srcs = files("srcs");
            File dest = new File(p.getString("dest"));
            String ext = "zip".equals(kind) ? ".zip" : "tar".equals(kind) ? ".tar" : ".tar.gz";
            String name = p.optString("name", srcs.size() == 1 ? Names.split(srcs.get(0).getName())[0] : "Архів");
            if (!name.toLowerCase(Locale.ROOT).endsWith(ext)) name = name + ext;
            String bad = Names.invalid(name);
            if (bad != null) throw new IOException(bad);
            File out = new File(dest, Names.unique(dest, name));
            total = Archive.total(srcs);
            for (File s : srcs) filesTotal += countFiles(s);
            progress(true);
            if ("zip".equals(kind)) Archive.zip(srcs, out, p.optInt("level", 6), this);
            else Archive.tar(srcs, out, "tgz".equals(kind), this);
            touched.add(out.getAbsolutePath());
            res.put("path", out.getAbsolutePath()).put("size", out.length());
        }

        void extract(JSONObject res) throws Exception {
            File arc = new File(p.getString("archive"));
            File dest = new File(p.getString("dest"));
            if (p.optBoolean("folder")) {
                String base = Names.split(arc.getName())[0];
                dest = new File(dest, Names.unique(dest, base));
            }
            Set<String> only = null;
            JSONArray o = p.optJSONArray("only");
            if (o != null && o.length() > 0) {
                only = new HashSet<>();
                for (int i = 0; i < o.length(); i++) only.add(o.getString(i));
            }
            long t = 0;
            List<Archive.Entry> entries = Archive.list(arc, p.optString("charset", null));
            for (Archive.Entry e : entries) if (!e.dir && Archive.wanted(e.name, only)) { t += Math.max(0, e.size); filesTotal++; }
            total = t;
            progress(true);
            int n = Archive.extract(arc, dest, only, p.optString("charset", null), p.optString("conflict", "rename"), this);
            files = n;
            addTree(dest);
            res.put("path", dest.getAbsolutePath()).put("count", n);
        }

        void addTree(File f) {
            if (touched.size() > 5000) return;
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) for (File x : ch) addTree(x);
            } else touched.add(f.getAbsolutePath());
        }

        // ---------------------------------------------------------- групове перейменування

        void renameMany(JSONObject res) throws Exception {
            JSONArray pairs = p.getJSONArray("pairs");
            filesTotal = pairs.length();
            List<File[]> stage = new ArrayList<>();
            // 1) усе в тимчасові назви — щоб обмін назвами (a→b, b→a) не конфліктував
            for (int i = 0; i < pairs.length(); i++) {
                check();
                JSONArray pr = pairs.getJSONArray(i);
                File from = new File(pr.getString(0));
                String to = pr.getString(1);
                if (Names.invalid(to) != null || !from.exists()) continue;
                File tmp = new File(from.getParentFile(), ".teka_rn_" + i + "_" + System.nanoTime());
                if (from.renameTo(tmp)) stage.add(new File[]{tmp, new File(from.getParentFile(), to), from});
            }
            // 2) з тимчасових — у фінальні
            int ok = 0;
            JSONArray out = new JSONArray();
            for (File[] s : stage) {
                File target = s[1];
                if (target.exists()) target = new File(target.getParentFile(), Names.unique(target.getParentFile(), target.getName()));
                if (s[0].renameTo(target)) {
                    ok++;
                    out.put(target.getAbsolutePath());
                    touched.add(s[2].getAbsolutePath());
                    touched.add(target.getAbsolutePath());
                } else s[0].renameTo(s[2]);
                files++;
                progress(false);
            }
            res.put("renamed", ok).put("paths", out);
        }
    }

    /** Вміст кошика на всіх сховищах. */
    static JSONArray trashList(Context c) throws Exception {
        JSONArray out = new JSONArray();
        for (Fs.Vol v : Fs.vols(c)) {
            File tr = new File(v.dir, Fs.TRASH);
            if (!tr.isDirectory()) continue;
            JSONObject idx = Job.readIndex(tr);
            Iterator<String> it = idx.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject m = idx.optJSONObject(k);
                File f = new File(tr, k);
                if (m == null || !f.exists()) continue;
                String orig = m.optString("path");
                out.put(new JSONObject()
                    .put("trash", tr.getAbsolutePath())
                    .put("entry", k)
                    .put("path", f.getAbsolutePath())
                    .put("name", new File(orig).getName())
                    .put("orig", orig)
                    .put("time", m.optLong("time"))
                    .put("dir", f.isDirectory())
                    .put("size", f.isDirectory() ? -1 : f.length()));
            }
        }
        return out;
    }
}
