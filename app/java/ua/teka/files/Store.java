package ua.teka.files;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Внутрішнє сховище застосунку: сесія і налаштування (kv/) та резервні копії
 * незбережених вкладок (backup/). Запис атомарний: спершу тимчасовий файл, потім перейменування.
 */
final class Store {
    private Store() {}

    private static File dir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length() && i < 80; i++) {
            char ch = key.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.' ? ch : '_');
        }
        return sb.toString();
    }

    static synchronized boolean put(Context c, String folder, String key, String value) {
        File d = dir(c, folder);
        File f = new File(d, safe(key));
        File tmp = new File(d, safe(key) + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return false;
        }
        return tmp.renameTo(f);
    }

    static synchronized String get(Context c, String folder, String key) {
        File f = new File(dir(c, folder), safe(key));
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static synchronized void del(Context c, String folder, String key) {
        new File(dir(c, folder), safe(key)).delete();
    }

    static synchronized String[] keys(Context c, String folder) {
        String[] names = dir(c, folder).list();
        return names != null ? names : new String[0];
    }

    /** Розмір папки резервних копій у байтах. */
    static long size(Context c, String folder) {
        long total = 0;
        File[] fs = dir(c, folder).listFiles();
        if (fs != null) for (File f : fs) total += f.length();
        return total;
    }
}
