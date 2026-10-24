package ua.teka.files;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Файлова система: сховища, вміст папок, властивості, категорії з медіатеки Android. */
final class Fs {
    private Fs() {}

    static final String TRASH = ".teka_trash";

    // ------------------------------------------------------------ сховища

    static final class Vol {
        File dir;
        String name;
        boolean primary, removable;
    }

    static List<Vol> vols(Context c) {
        List<Vol> out = new ArrayList<>();
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (!Environment.MEDIA_MOUNTED.equals(v.getState()) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
                File d = volDir(v);
                if (d == null || !d.exists()) continue;
                Vol x = new Vol();
                x.dir = d;
                x.primary = v.isPrimary();
                x.removable = v.isRemovable();
                String desc = v.getDescription(c);
                x.name = x.primary ? "Внутрішня пам’ять" : (desc != null && !desc.isEmpty() ? desc : (x.removable ? "SD-карта" : "Сховище"));
                out.add(x);
            }
        }
        if (out.isEmpty()) {
            Vol x = new Vol();
            x.dir = Environment.getExternalStorageDirectory();
            x.primary = true;
            x.name = "Внутрішня пам’ять";
            out.add(x);
        }
        return out;
    }

    static File volDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            Object f = StorageVolume.class.getMethod("getPathFile").invoke(v);
            if (f instanceof File) return (File) f;
        } catch (Exception ignored) {}
        try {
            Object p = StorageVolume.class.getMethod("getPath").invoke(v);
            if (p instanceof String) return new File((String) p);
        } catch (Exception ignored) {}
        return null;
    }

    static JSONArray volumes(Context c) throws Exception {
        JSONArray arr = new JSONArray();
        for (Vol v : vols(c)) {
            arr.put(new JSONObject()
                .put("path", v.dir.getAbsolutePath())
                .put("name", v.name)
                .put("primary", v.primary)
                .put("removable", v.removable)
                .put("total", v.dir.getTotalSpace())
                .put("free", v.dir.getUsableSpace()));
        }
        return arr;
    }

    /** Корінь сховища, на якому лежить файл. */
    static File volumeOf(Context c, File f) {
        String p = f.getAbsolutePath();
        File best = null;
        for (Vol v : vols(c)) {
            String r = v.dir.getAbsolutePath();
            if ((p.equals(r) || p.startsWith(r + "/")) && (best == null || r.length() > best.getAbsolutePath().length())) best = v.dir;
        }
        return best;
    }

    // ------------------------------------------------------------ папки й файли

    static JSONObject item(File f) throws Exception {
        boolean dir = f.isDirectory();
        JSONObject o = new JSONObject()
            .put("name", f.getName())
            .put("path", f.getAbsolutePath())
            .put("dir", dir)
            .put("mtime", f.lastModified());
        if (dir) {
            String[] ch = f.list();
            o.put("n", ch == null ? -1 : ch.length);
        } else {
            o.put("size", f.length());
        }
        return o;
    }

    static JSONObject list(String path, boolean hidden) throws Exception {
        File d = new File(path);
        File[] fs = d.listFiles();
        JSONObject o = new JSONObject().put("path", d.getAbsolutePath());
        if (fs == null) {
            return o.put("ok", false).put("err", !d.exists() ? "missing" : (!d.isDirectory() ? "notdir" : "access"));
        }
        JSONArray items = new JSONArray();
        for (File f : fs) {
            if (!hidden && f.getName().startsWith(".")) continue;
            if (f.getName().equals(TRASH)) continue;
            items.put(item(f));
        }
        return o.put("ok", true).put("items", items).put("writable", d.canWrite());
    }

    static JSONObject stat(String path) throws Exception {
        File f = new File(path);
        JSONObject o = item(f);
        o.put("exists", f.exists())
            .put("canWrite", f.canWrite())
            .put("hidden", f.isHidden())
            .put("parent", f.getParent())
            .put("mime", mime(f.getName()));
        return o;
    }

    static String mime(String name) {
        String e = Names.ext(name);
        switch (e) {
            case "": return "application/octet-stream";
            case "apk": return "application/vnd.android.package-archive";
            case "md": return "text/markdown";
            case "mkv": return "video/x-matroska";
            case "opus": return "audio/ogg";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "avif": return "image/avif";
            case "js": return "text/javascript";
            case "json": return "application/json";
            case "kt": case "java": case "py": case "sh": case "c": case "cpp": case "h": case "ini": case "cfg": case "conf": case "log": case "yml": case "yaml": case "toml":
                return "text/plain";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
                return m != null ? m : "application/octet-stream";
        }
    }

    static JSONObject mkdir(String parent, String name) throws Exception {
        String bad = Names.invalid(name);
        if (bad != null) return new JSONObject().put("ok", false).put("err", bad);
        File f = new File(parent, name);
        if (f.exists()) return new JSONObject().put("ok", false).put("err", "«" + name + "» уже існує");
        return new JSONObject().put("ok", f.mkdirs()).put("path", f.getAbsolutePath()).put("err", "Немає доступу до папки");
    }

    static JSONObject mkfile(String parent, String name) throws Exception {
        String bad = Names.invalid(name);
        if (bad != null) return new JSONObject().put("ok", false).put("err", bad);
        File f = new File(parent, name);
        if (f.exists()) return new JSONObject().put("ok", false).put("err", "«" + name + "» уже існує");
        boolean ok;
        try { ok = f.createNewFile(); } catch (Exception e) { ok = false; }
        return new JSONObject().put("ok", ok).put("path", f.getAbsolutePath()).put("err", "Немає доступу до папки");
    }

    static JSONObject rename(String path, String name) throws Exception {
        String bad = Names.invalid(name);
        if (bad != null) return new JSONObject().put("ok", false).put("err", bad);
        File f = new File(path);
        File t = new File(f.getParentFile(), name);
        if (t.exists() && !t.getAbsolutePath().equalsIgnoreCase(f.getAbsolutePath())) {
            return new JSONObject().put("ok", false).put("err", "«" + name + "» уже існує");
        }
        boolean ok = f.renameTo(t);
        if (!ok && t.getAbsolutePath().equalsIgnoreCase(f.getAbsolutePath())) {
            // зміна лише регістру на FAT/exFAT — через тимчасову назву
            File tmp = new File(f.getParentFile(), ".teka_tmp_" + System.nanoTime());
            ok = f.renameTo(tmp) && tmp.renameTo(t);
        }
        return new JSONObject().put("ok", ok).put("path", t.getAbsolutePath()).put("err", "Не вдалося перейменувати");
    }

    /** Які назви з джерел уже є в папці призначення. */
    static JSONArray conflicts(JSONArray srcs, String dest) throws Exception {
        JSONArray out = new JSONArray();
        File d = new File(dest);
        for (int i = 0; i < srcs.length(); i++) {
            File s = new File(srcs.getString(i));
            if (s.getParentFile() != null && s.getParentFile().getAbsolutePath().equals(d.getAbsolutePath())) continue;
            if (new File(d, s.getName()).exists()) out.put(s.getName());
        }
        return out;
    }

    // ------------------------------------------------------------ категорії (медіатека)

    static final String[] DOCS = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "rtf", "csv", "epub", "fb2", "djvu", "md"};
    static final String[] ARCHIVES = {"zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"};
    static final String[] APKS = {"apk", "apks", "xapk"};

    static final String[] COLS = {
        MediaStore.Files.FileColumns.DATA,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    };

    static String[] selection(String kind) {
        String hidden = MediaStore.Files.FileColumns.DATA + " NOT LIKE '%/.%'";
        List<String> args = new ArrayList<>();
        String sel;
        switch (kind) {
            case "images": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE; break;
            case "video": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO; break;
            case "audio": sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO; break;
            case "docs": sel = likes(DOCS, args); break;
            case "archives": sel = likes(ARCHIVES, args); break;
            case "apk": sel = likes(APKS, args); break;
            case "recent":
                sel = MediaStore.Files.FileColumns.DATE_MODIFIED + ">=" + (System.currentTimeMillis() / 1000 - 30L * 86400)
                    + " AND " + MediaStore.Files.FileColumns.SIZE + ">0 AND " + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL";
                break;
            case "large": sel = MediaStore.Files.FileColumns.SIZE + ">" + (50L << 20); break;
            default: sel = "1=0";
        }
        sel = "(" + sel + ") AND " + hidden;
        String[] out = new String[args.size() + 1];
        out[0] = sel;
        for (int i = 0; i < args.size(); i++) out[i + 1] = args.get(i);
        return out;
    }

    static String likes(String[] exts, List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String e : exts) {
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(MediaStore.Files.FileColumns.DATA).append(" LIKE ?");
            args.add("%." + e);
        }
        return sb.toString();
    }

    static String order(String kind) {
        if ("large".equals(kind)) return MediaStore.Files.FileColumns.SIZE + " DESC";
        return MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
    }

    static Uri filesUri() {
        return MediaStore.Files.getContentUri("external");
    }

    static JSONObject category(Context c, String kind, int offset, int limit) throws Exception {
        String[] s = selection(kind);
        String[] args = new String[s.length - 1];
        System.arraycopy(s, 1, args, 0, args.length);
        JSONArray items = new JSONArray();
        int total = 0;
        ContentResolver cr = c.getContentResolver();
        try (Cursor cur = cr.query(filesUri(), COLS, s[0], args, order(kind))) {
            if (cur != null) {
                total = cur.getCount();
                if (offset < total && cur.moveToPosition(offset)) {
                    int n = 0;
                    do {
                        String path = cur.getString(0);
                        if (path == null) continue;
                        File f = new File(path);
                        if (!f.exists() || f.isDirectory()) continue;
                        items.put(new JSONObject()
                            .put("name", f.getName())
                            .put("path", path)
                            .put("dir", false)
                            .put("size", cur.getLong(1))
                            .put("mtime", cur.getLong(2) * 1000));
                        n++;
                    } while (n < limit && cur.moveToNext());
                }
            }
        }
        return new JSONObject().put("ok", true).put("kind", kind).put("total", total).put("items", items);
    }

    /** Кількість і сумарний розмір для кожної категорії. */
    static JSONObject categoryStats(Context c) throws Exception {
        JSONObject out = new JSONObject();
        ContentResolver cr = c.getContentResolver();
        for (String kind : new String[]{"images", "video", "audio", "docs", "archives", "apk"}) {
            String[] s = selection(kind);
            String[] args = new String[s.length - 1];
            System.arraycopy(s, 1, args, 0, args.length);
            long size = 0;
            int count = 0;
            try (Cursor cur = cr.query(filesUri(), new String[]{MediaStore.Files.FileColumns.SIZE}, s[0], args, null)) {
                if (cur != null) while (cur.moveToNext()) { size += cur.getLong(0); count++; }
            } catch (Exception ignored) {}
            out.put(kind, new JSONObject().put("count", count).put("size", size));
        }
        return out;
    }

    static String category(String name) {
        String e = Names.ext(name).toLowerCase(Locale.ROOT);
        if (e.matches("jpe?g|png|gif|webp|heic|heif|bmp|avif|svg|tiff?|dng|raw")) return "images";
        if (e.matches("mp4|mkv|webm|avi|mov|3gp|m4v|ts|wmv|flv")) return "video";
        if (e.matches("mp3|m4a|aac|ogg|opus|flac|wav|amr|wma|mid|midi")) return "audio";
        for (String d : DOCS) if (d.equals(e)) return "docs";
        for (String d : ARCHIVES) if (d.equals(e)) return "archives";
        for (String d : APKS) if (d.equals(e)) return "apk";
        return "other";
    }
}
