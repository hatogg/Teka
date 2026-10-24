package ua.teka.files;

import java.io.File;
import java.util.Locale;

/** Імена файлів: розширення, вільна назва «файл (1).txt», перевірка допустимості. Чистий Java. */
public final class Names {
    private Names() {}

    /** Подвійні розширення, які розглядаємо як одне. */
    static final String[] DOUBLE = {".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst"};

    /** [основа, розширення з крапкою або ""]. Файли на кшталт «.bashrc» розширення не мають. */
    public static String[] split(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String d : DOUBLE) {
            if (lower.endsWith(d) && name.length() > d.length()) {
                return new String[]{name.substring(0, name.length() - d.length()), name.substring(name.length() - d.length())};
            }
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return new String[]{name, ""};
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }

    public static String ext(String name) {
        String e = split(name)[1];
        return e.isEmpty() ? "" : e.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Повертає назву, якої ще немає в папці: «звіт.pdf» → «звіт (1).pdf», «звіт (2).pdf»… */
    public static String unique(File dir, String name) {
        if (!new File(dir, name).exists()) return name;
        String[] p = split(name);
        String base = p[0];
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*) \\((\\d+)\\)$").matcher(base);
        int n = 1;
        if (m.matches()) {
            base = m.group(1);
            try { n = Integer.parseInt(m.group(2)) + 1; } catch (NumberFormatException ignored) {}
        }
        for (; n < 100000; n++) {
            String cand = base + " (" + n + ")" + p[1];
            if (!new File(dir, cand).exists()) return cand;
        }
        return base + " (" + System.currentTimeMillis() + ")" + p[1];
    }

    /** Чи можна так назвати файл у файловій системі Android (FAT/exFAT на SD-картках найсуворіші). */
    public static String invalid(String name) {
        if (name == null || name.trim().isEmpty()) return "Порожня назва";
        if (name.equals(".") || name.equals("..")) return "Недопустима назва";
        if (name.length() > 255) return "Задовга назва";
        for (char c : name.toCharArray()) {
            if (c == '/' || c == '\0') return "Символ «/» не можна вживати в назві";
            if ("\\:*?\"<>|".indexOf(c) >= 0) return "Символ «" + c + "» не підтримують SD-картки й Windows";
            if (c < 32) return "Керувальні символи в назві";
        }
        return null;
    }

    /** Чи лежить child всередині parent (або збігається з ним). */
    public static boolean inside(File child, File parent) {
        try {
            String c = child.getCanonicalPath(), p = parent.getCanonicalPath();
            return c.equals(p) || c.startsWith(p.endsWith(File.separator) ? p : p + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        String[] u = {"КБ", "МБ", "ГБ", "ТБ"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        String s = v < 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.0f", v);
        return s.replace('.', ',') + " " + u[i];
    }
}
