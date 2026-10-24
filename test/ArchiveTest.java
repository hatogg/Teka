import ua.teka.files.Archive;
import ua.teka.files.Names;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Тести архіватора й імен. Запуск: test/run.sh */
public class ArchiveTest {
    static int failed = 0, passed = 0;

    static void eq(String name, Object got, Object want) {
        if (want == null ? got == null : want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + name + ": отримано «" + got + "», очікувалось «" + want + "»");
    }

    static File tmp() throws Exception { return Files.createTempDirectory("teka").toFile(); }

    static void write(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }

    static byte[] read(File f) throws Exception { return Files.readAllBytes(f.toPath()); }

    static List<String> names(List<Archive.Entry> es) {
        List<String> l = new ArrayList<>();
        for (Archive.Entry e : es) l.add(e.name + (e.dir && !e.name.endsWith("/") ? "/" : ""));
        Collections.sort(l);
        return l;
    }

    static final String LONG = "дуже-довга-назва-файлу-яка-не-вміщається-в-сто-байтів-заголовка-tar-ще-трохи-тексту.txt";

    public static void main(String[] a) throws Exception {
        // ---------------- імена
        eq("split tar.gz", Arrays.toString(Names.split("backup.tar.gz")), "[backup, .tar.gz]");
        eq("split dotfile", Arrays.toString(Names.split(".bashrc")), "[.bashrc, ]");
        eq("split plain", Arrays.toString(Names.split("photo.JPG")), "[photo, .JPG]");
        eq("ext lower", Names.ext("photo.JPG"), "jpg");
        File d0 = tmp();
        write(new File(d0, "звіт.pdf"), new byte[]{1});
        eq("unique 1", Names.unique(d0, "звіт.pdf"), "звіт (1).pdf");
        write(new File(d0, "звіт (1).pdf"), new byte[]{1});
        eq("unique 2", Names.unique(d0, "звіт.pdf"), "звіт (2).pdf");
        eq("unique from numbered", Names.unique(d0, "звіт (1).pdf"), "звіт (2).pdf");
        eq("unique free", Names.unique(d0, "інший.pdf"), "інший.pdf");
        eq("invalid slash", Names.invalid("a/b") != null, true);
        eq("invalid colon", Names.invalid("a:b") != null, true);
        eq("valid cyr", Names.invalid("Фото 2026.jpg"), null);
        eq("inside", Names.inside(new File(d0, "x/y"), d0), true);
        eq("not inside", Names.inside(d0.getParentFile(), d0), false);
        eq("human", Names.human(1536), "1,5 КБ");
        eq("kind", Archive.kind("a.tar.gz") + Archive.kind("b.TGZ") + Archive.kind("c.apk") + Archive.kind("d.gz") + Archive.kind("e.txt"), "tgztgzzipgznull");

        // ---------------- вихідні файли
        File src = tmp();
        byte[] big = new byte[300_000];
        new Random(1).nextBytes(big);
        write(new File(src, "Проєкт/Привіт.txt"), "Привіт, світе!\n".getBytes(StandardCharsets.UTF_8));
        write(new File(src, "Проєкт/дані/вкладений/файл.bin"), big);
        write(new File(src, "Проєкт/" + LONG), "довге ім’я".getBytes(StandardCharsets.UTF_8));
        new File(src, "Проєкт/порожня").mkdirs();
        List<File> in = Collections.singletonList(new File(src, "Проєкт"));
        long total = Archive.total(in);
        eq("total", total, (long) big.length + "Привіт, світе!\n".getBytes(StandardCharsets.UTF_8).length + "довге ім’я".getBytes(StandardCharsets.UTF_8).length);

        for (String fmt : new String[]{"zip", "tar", "tar.gz"}) {
            File out = new File(tmp(), "архів." + fmt);
            long[] last = {0};
            Archive.Progress p = (done, cur) -> { last[0] = done; return true; };
            if (fmt.equals("zip")) Archive.zip(in, out, 6, p);
            else Archive.tar(in, out, fmt.equals("tar.gz"), p);
            eq(fmt + " progress", last[0], total);
            List<String> ls = names(Archive.list(out, null));
            eq(fmt + " has long", ls.contains("Проєкт/" + LONG), true);
            eq(fmt + " has nested", ls.contains("Проєкт/дані/вкладений/файл.bin"), true);
            eq(fmt + " has empty dir", ls.contains("Проєкт/порожня/"), true);

            File dst = tmp();
            int n = Archive.extract(out, dst, null, null, "rename", null);
            eq(fmt + " count", n, 3);
            eq(fmt + " txt", new String(read(new File(dst, "Проєкт/Привіт.txt")), StandardCharsets.UTF_8), "Привіт, світе!\n");
            eq(fmt + " bin", Arrays.equals(read(new File(dst, "Проєкт/дані/вкладений/файл.bin")), big), true);
            eq(fmt + " long", new File(dst, "Проєкт/" + LONG).exists(), true);
            eq(fmt + " empty dir", new File(dst, "Проєкт/порожня").isDirectory(), true);

            // повторно з перейменуванням
            Archive.extract(out, dst, new HashSet<>(Collections.singletonList("Проєкт/Привіт.txt")), null, "rename", null);
            eq(fmt + " rename", new File(dst, "Привіт.txt").exists(), true);
            Archive.extract(out, dst, new HashSet<>(Collections.singletonList("Проєкт/Привіт.txt")), null, "rename", null);
            eq(fmt + " rename 2", new File(dst, "Привіт (1).txt").exists(), true);
            // пропуск
            int skipped = Archive.extract(out, dst, new HashSet<>(Collections.singletonList("Проєкт/Привіт.txt")), null, "skip", null);
            eq(fmt + " skip", skipped, 0);

            // вибрана папка — без батьківських папок архіву
            File dst2 = tmp();
            Archive.extract(out, dst2, new HashSet<>(Collections.singletonList("Проєкт/дані/")), null, "rename", null);
            eq(fmt + " subset", Arrays.equals(read(new File(dst2, "дані/вкладений/файл.bin")), big), true);
            eq(fmt + " subset only", new File(dst2, "Привіт.txt").exists(), false);

            // скасування
            File dst3 = tmp();
            boolean cancelled = false;
            try {
                Archive.extract(out, dst3, null, null, "rename", (done, cur) -> done < 100_000);
            } catch (Archive.Cancelled c) {
                cancelled = true;
            }
            eq(fmt + " cancel", cancelled, true);
            eq(fmt + " cancel cleanup", new File(dst3, "Проєкт/дані/вкладений/файл.bin").exists(), false);
        }

        // ---------------- GZ одного файлу
        File g = new File(tmp(), "дані.bin.gz");
        Archive.gzip(new File(src, "Проєкт/дані/вкладений/файл.bin"), g, null);
        eq("gz list", Archive.list(g, null).get(0).name, "дані.bin");
        eq("gz size", Archive.list(g, null).get(0).size, (long) big.length);
        File gd = tmp();
        Archive.extract(g, gd, null, null, "rename", null);
        eq("gz data", Arrays.equals(read(new File(gd, "дані.bin")), big), true);

        // ---------------- ZIP з Windows: назви в CP866
        File cp = new File(tmp(), "windows.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(cp), Charset.forName("IBM866"))) {
            z.putNextEntry(new ZipEntry("Документи/Справа.txt"));
            z.write("ok".getBytes(StandardCharsets.US_ASCII));
            z.closeEntry();
        }
        eq("cp866 detect", Archive.zipCharset(cp), "IBM866");
        eq("cp866 names", names(Archive.list(cp, null)).toString(), "[Документи/Справа.txt]");
        File cpd = tmp();
        Archive.extract(cp, cpd, null, null, "rename", null);
        eq("cp866 extract", new File(cpd, "Документи/Справа.txt").exists(), true);

        // ---------------- захист від «zip slip»
        File evil = new File(tmp(), "evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../../вирвався.txt"));
            z.write(1);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("норм.txt"));
            z.write(2);
            z.closeEntry();
        }
        File ed = new File(tmp(), "inner");
        int en = Archive.extract(evil, ed, null, null, "rename", null);
        eq("zip slip blocked", new File(ed.getParentFile().getParentFile(), "вирвався.txt").exists(), false);
        eq("zip slip rest", en, 1);

        // ---------------- зашифрований ZIP (прапорець у заголовку)
        File enc = new File(tmp(), "secret.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(enc))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write(1);
            z.closeEntry();
        }
        byte[] eb = read(enc);
        eb[6] |= 1;
        write(enc, eb);
        eq("encrypted flag", Archive.zipEncrypted(enc), true);
        boolean thrown = false;
        try { Archive.list(enc, null); } catch (Archive.Encrypted e) { thrown = true; }
        eq("encrypted list", thrown, true);

        System.out.println((failed == 0 ? "OK" : "ПОМИЛКИ") + ": пройдено " + passed + ", не пройдено " + failed);
        if (failed > 0) System.exit(1);
    }
}
