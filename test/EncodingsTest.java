import ua.teka.files.Encodings;

import java.nio.charset.Charset;
import java.util.Arrays;

/** Тести визначення кодувань і кінців рядків. Запуск: test/run.sh */
public class EncodingsTest {
    static int failed = 0, passed = 0;

    static void eq(String name, Object got, Object want) {
        if (want == null ? got == null : want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + name + ": отримано «" + got + "», очікувалось «" + want + "»");
    }

    static Encodings.Text rd(byte[] b) { return Encodings.read(b, b.length, "auto"); }

    public static void main(String[] a) throws Exception {
        String uk = "Тарас Шевченко народився в селі Моринці. Реве та стогне Дніпр широкий, сердитий вітер завива.\nЇжак їсть ґрунт, є щастя.\n";
        String ru = "Съешь же ещё этих мягких французских булок, да выпей чаю. Широкая электрификация южных губерний.\n";
        String de = "Größe, Übermäßig, Straße — ça va, naïve café.\n";

        // Без BOM
        eq("ascii", rd("hello\nworld\n".getBytes("US-ASCII")).enc, "utf-8");
        eq("utf8 uk", rd(uk.getBytes("UTF-8")).enc, "utf-8");
        eq("utf8 text", rd(uk.getBytes("UTF-8")).text, uk);
        eq("1251 uk", rd(uk.getBytes("windows-1251")).enc, "windows-1251");
        eq("1251 uk text", rd(uk.getBytes("windows-1251")).text, uk);
        eq("1251 ru", rd(ru.getBytes("windows-1251")).enc, "windows-1251");
        eq("koi8u uk", rd(uk.getBytes("KOI8-U")).enc, "koi8-u");
        eq("koi8r ru", rd(ru.getBytes("KOI8-R")).enc, "koi8-u"); // KOI8-U — надмножина для російського тексту
        eq("koi8r ru text", Encodings.decode(ru.getBytes("KOI8-R"), ru.getBytes("KOI8-R").length, "koi8-u"), ru);
        eq("cp866 ru", rd(ru.getBytes("IBM866")).enc, "cp866");
        eq("1252 de", rd(de.getBytes("windows-1252")).enc, "windows-1252");
        eq("1252 de text", rd(de.getBytes("windows-1252")).text, de);
        eq("short 1251", rd("Привіт".getBytes("windows-1251")).enc, "windows-1251");

        // BOM
        byte[] u8bom = Encodings.encode(uk, "utf-8", true);
        Encodings.Text t = rd(u8bom);
        eq("utf8 bom enc", t.enc, "utf-8");
        eq("utf8 bom flag", t.bom, true);
        eq("utf8 bom text", t.text, uk);
        t = rd(Encodings.encode(uk, "utf-16le", true));
        eq("utf16le bom", t.enc + t.bom, "utf-16letrue");
        eq("utf16le text", t.text, uk);
        t = rd(Encodings.encode(uk, "utf-16be", true));
        eq("utf16be bom", t.enc + t.bom, "utf-16betrue");
        // UTF-16 без BOM (латиниця)
        eq("utf16le nobom", rd("function main() { return 42; }\n".getBytes("UTF-16LE")).enc, "utf-16le");
        eq("utf16be nobom", rd("function main() { return 42; }\n".getBytes("UTF-16BE")).enc, "utf-16be");

        // Бінарний
        byte[] bin = new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 0, 62, 0, 1, 0, 0, 0, (byte) 0x90, 0x10};
        eq("binary", rd(bin).binary, true);
        eq("text not binary", rd(uk.getBytes("UTF-8")).binary, false);

        // Неправильний UTF-8
        eq("invalid utf8", Encodings.validUtf8(new byte[]{(byte) 0xC0, (byte) 0x80}, 2), false);
        eq("surrogate utf8", Encodings.validUtf8(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, 3), false);
        eq("emoji utf8", Encodings.validUtf8("😀".getBytes("UTF-8"), 4), true);

        // Кінці рядків
        eq("eol lf", Encodings.detectEol("a\nb\nc")[0], "LF");
        eq("eol crlf", Encodings.detectEol("a\r\nb\r\nc")[0], "CRLF");
        eq("eol cr", Encodings.detectEol("a\rb\rc")[0], "CR");
        eq("eol mixed", Encodings.detectEol("a\r\nb\nc\r\n")[1], "1");
        eq("eol none", Encodings.detectEol("abc")[0], "LF");
        t = rd("a\r\nb\r\n".getBytes("UTF-8"));
        eq("crlf normalized", t.text, "a\nb\n");
        eq("crlf kept", t.eol, "CRLF");
        eq("roundtrip crlf", Arrays.equals(Encodings.write(t.text, t.enc, t.bom, t.eol), "a\r\nb\r\n".getBytes("UTF-8")), true);
        eq("roundtrip cr", new String(Encodings.write("x\ny", "utf-8", false, "CR"), "UTF-8"), "x\ry");

        // Повний цикл 1251 + CRLF
        byte[] src = uk.replace("\n", "\r\n").getBytes("windows-1251");
        t = rd(src);
        eq("1251 crlf roundtrip", Arrays.equals(Encodings.write(t.text, t.enc, t.bom, t.eol), src), true);

        // Символи, яких немає в кодуванні
        eq("unmappable 1251", Encodings.unmappable("Привіт 😀 €ä中", "windows-1251"), 3); // € є в 1251
        eq("unmappable utf8", Encodings.unmappable("中文 😀", "utf-8"), 0);
        eq("unmappable koi8u", Encodings.unmappable("ґєії", "koi8-u"), 0);
        eq("unmappable 1252", Encodings.unmappable("Привіт", "windows-1252"), 6);

        // Примусове кодування
        eq("force 1251", Encodings.read(uk.getBytes("windows-1251"), uk.getBytes("windows-1251").length, "windows-1251").text, uk);

        // Усі заявлені кодування доступні
        for (String[] e : Encodings.SUPPORTED) eq("charset " + e[0], Charset.isSupported(e[1]), true);

        System.out.println((failed == 0 ? "OK" : "ПОМИЛКИ") + ": пройдено " + passed + ", не пройдено " + failed);
        if (failed > 0) System.exit(1);
    }
}
