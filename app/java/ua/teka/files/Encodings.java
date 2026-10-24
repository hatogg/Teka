package ua.teka.files;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Кодування і кінці рядків. Чистий Java без Android — покрито тестами на JVM.
 *
 * Ідентифікатори кодувань — малими літерами, як у списку {@link #SUPPORTED}.
 * Текст усередині редактора завжди має кінці рядків "\n"; справжні кінці
 * рядків файлу визначаються під час читання і повертаються під час запису.
 */
public final class Encodings {
    private Encodings() {}

    /** id, назва Java-кодування, людська назва. */
    public static final String[][] SUPPORTED = {
        {"utf-8", "UTF-8", "UTF-8"},
        {"utf-16le", "UTF-16LE", "UTF-16 LE"},
        {"utf-16be", "UTF-16BE", "UTF-16 BE"},
        {"windows-1251", "windows-1251", "Windows-1251 (кирилиця)"},
        {"koi8-u", "KOI8-U", "KOI8-U (кирилиця)"},
        {"koi8-r", "KOI8-R", "KOI8-R (кирилиця)"},
        {"cp866", "IBM866", "CP866 (DOS, кирилиця)"},
        {"iso-8859-5", "ISO-8859-5", "ISO-8859-5 (кирилиця)"},
        {"windows-1252", "windows-1252", "Windows-1252 (західна)"},
        {"iso-8859-1", "ISO-8859-1", "ISO-8859-1 (латиниця-1)"},
        {"windows-1250", "windows-1250", "Windows-1250 (центральна Європа)"},
        {"iso-8859-2", "ISO-8859-2", "ISO-8859-2 (латиниця-2)"},
        {"windows-1253", "windows-1253", "Windows-1253 (грецька)"},
        {"windows-1254", "windows-1254", "Windows-1254 (турецька)"},
        {"windows-1257", "windows-1257", "Windows-1257 (балтійська)"},
        {"gb18030", "GB18030", "GB18030 (китайська)"},
        {"big5", "Big5", "Big5 (китайська традиційна)"},
        {"shift_jis", "Shift_JIS", "Shift_JIS (японська)"},
        {"euc-kr", "EUC-KR", "EUC-KR (корейська)"},
    };

    public static String javaName(String id) {
        if (id == null) return "UTF-8";
        for (String[] e : SUPPORTED) if (e[0].equalsIgnoreCase(id)) return e[1];
        return id;
    }

    public static Charset charset(String id) {
        try {
            return Charset.forName(javaName(id));
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---------------------------------------------------------------- визначення

    public static final class Detected {
        public String enc;
        public boolean bom;
        public boolean binary;
        public Detected(String enc, boolean bom, boolean binary) {
            this.enc = enc; this.bom = bom; this.binary = binary;
        }
    }

    public static Detected detect(byte[] b, int len) {
        if (len >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return new Detected("utf-8", true, false);
        if (len >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE)
            return new Detected("utf-16le", true, false);
        if (len >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)
            return new Detected("utf-16be", true, false);

        // UTF-16 без BOM: багато нулів строго на парних або непарних позиціях.
        int n = Math.min(len, 8192);
        int zEven = 0, zOdd = 0, zeros = 0;
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                zeros++;
                if ((i & 1) == 0) zEven++; else zOdd++;
            }
        }
        if (n >= 4) {
            if (zOdd > n * 0.3 && zEven < n * 0.02) return new Detected("utf-16le", false, false);
            if (zEven > n * 0.3 && zOdd < n * 0.02) return new Detected("utf-16be", false, false);
        }
        boolean binary = zeros > 0;

        boolean high = false;
        for (int i = 0; i < len; i++) if ((b[i] & 0x80) != 0) { high = true; break; }
        if (!high || validUtf8(b, len)) return new Detected("utf-8", false, binary);

        return new Detected(guessSingleByte(b, len), false, binary);
    }

    /** Строга перевірка UTF-8; обрізаний наприкінці символ (неповний буфер) дозволено. */
    public static boolean validUtf8(byte[] b, int len) {
        int i = 0;
        while (i < len) {
            int c = b[i] & 0xff;
            int need;
            if (c < 0x80) { i++; continue; }
            else if (c >= 0xC2 && c <= 0xDF) need = 1;
            else if (c >= 0xE0 && c <= 0xEF) need = 2;
            else if (c >= 0xF0 && c <= 0xF4) need = 3;
            else return false;
            if (i + need >= len) {
                // символ обрізано кінцем буфера: перевіряємо лише наявні байти
                for (int k = 1; i + k < len; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
                return true;
            }
            for (int k = 1; k <= need; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            if (need == 2) {
                int c1 = b[i + 1] & 0xff;
                if (c == 0xE0 && c1 < 0xA0) return false;      // надлишкове кодування
                if (c == 0xED && c1 > 0x9F) return false;      // сурогати
            } else if (need == 3) {
                int c1 = b[i + 1] & 0xff;
                if (c == 0xF0 && c1 < 0x90) return false;
                if (c == 0xF4 && c1 > 0x8F) return false;
            }
            i += need + 1;
        }
        return true;
    }

    private static final String[] CYR_CANDIDATES = {"windows-1251", "koi8-u", "cp866"};
    /** Найчастіші літери української та російської — бонус за них. */
    private static final String COMMON = "оеаинтсрвлікдмпуяз";

    /** Вибір однобайтового кодування за частотою кириличних малих літер. */
    static String guessSingleByte(byte[] b, int len) {
        int highCount = 0, mixed = 0;
        int n = Math.min(len, 65536);
        for (int i = 0; i < n; i++) {
            if ((b[i] & 0x80) == 0) continue;
            highCount++;
            // У кириличному тексті слова складаються лише з «високих» байтів;
            // у західноєвропейському окремі літери з діакритикою стоять поруч із латиницею.
            if ((i > 0 && asciiLetter(b[i - 1])) || (i + 1 < n && asciiLetter(b[i + 1]))) mixed++;
        }
        if (highCount == 0) return "windows-1252";
        if (mixed > highCount * 0.35) return "windows-1252";
        String best = "windows-1252";
        double bestScore = 0.45; // нижче — вважаємо текст західноєвропейським
        for (String cand : CYR_CANDIDATES) {
            Charset cs = charset(cand);
            String s = new String(b, 0, n, cs);
            int lower = 0, upper = 0, common = 0;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch < 0x80) continue;
                if ((ch >= 'а' && ch <= 'я') || ch == 'і' || ch == 'ї' || ch == 'є' || ch == 'ґ' || ch == 'ё') {
                    lower++;
                    if (COMMON.indexOf(ch) >= 0) common++;
                } else if ((ch >= 'А' && ch <= 'Я') || ch == 'І' || ch == 'Ї' || ch == 'Є' || ch == 'Ґ' || ch == 'Ё') {
                    upper++;
                }
            }
            double score = (lower + 0.3 * upper + 0.5 * common) / highCount;
            if (score > bestScore) { bestScore = score; best = cand; }
        }
        return best;
    }

    private static boolean asciiLetter(byte c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // ---------------------------------------------------------------- читання і запис

    public static int bomLength(byte[] b, int len, String enc, boolean bom) {
        if (!bom) return 0;
        if ("utf-8".equals(enc) && len >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return 3;
        if (("utf-16le".equals(enc) || "utf-16be".equals(enc)) && len >= 2) {
            int a = b[0] & 0xff, c = b[1] & 0xff;
            if ((a == 0xFF && c == 0xFE) || (a == 0xFE && c == 0xFF)) return 2;
        }
        return 0;
    }

    /** Декодує байти, пропускаючи BOM, якщо він є. Невідомі байти стають U+FFFD. */
    public static String decode(byte[] b, int len, String enc) {
        int skip = 0;
        if ("utf-8".equals(enc) && len >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) skip = 3;
        else if ("utf-16le".equals(enc) && len >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) skip = 2;
        else if ("utf-16be".equals(enc) && len >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) skip = 2;
        return new String(b, skip, len - skip, charset(enc));
    }

    public static byte[] encode(String s, String enc, boolean bom) {
        Charset cs = charset(enc);
        CharsetEncoder ce = cs.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer bb;
        try {
            bb = ce.encode(CharBuffer.wrap(s));
        } catch (CharacterCodingException e) {
            bb = ByteBuffer.wrap(s.getBytes(cs));
        }
        byte[] body = new byte[bb.remaining()];
        bb.get(body);
        byte[] prefix = new byte[0];
        if (bom) {
            if ("utf-8".equals(enc)) prefix = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            else if ("utf-16le".equals(enc)) prefix = new byte[]{(byte) 0xFF, (byte) 0xFE};
            else if ("utf-16be".equals(enc)) prefix = new byte[]{(byte) 0xFE, (byte) 0xFF};
        }
        if (prefix.length == 0) return body;
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        return out;
    }

    /** Скільки символів не можна записати в кодуванні (вони стануть «?»). */
    public static int unmappable(String s, String enc) {
        Charset cs = charset(enc);
        String name = cs.name().toUpperCase();
        if (name.startsWith("UTF-") || name.equals("GB18030")) return 0;
        CharsetEncoder ce = cs.newEncoder();
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) continue;
            if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
                if (!ce.canEncode(s.substring(i, i + 2))) count++;
                i++;
                continue;
            }
            if (!ce.canEncode(c)) count++;
        }
        return count;
    }

    // ---------------------------------------------------------------- кінці рядків

    /** Повертає [домінантний EOL ("LF"|"CRLF"|"CR"), "1" якщо змішані]. */
    public static String[] detectEol(String s) {
        int crlf = 0, lf = 0, cr = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                if (i + 1 < n && s.charAt(i + 1) == '\n') { crlf++; i++; }
                else cr++;
            } else if (c == '\n') lf++;
        }
        String eol = "LF";
        if (crlf > lf && crlf >= cr) eol = "CRLF";
        else if (cr > lf && cr > crlf) eol = "CR";
        int kinds = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
        return new String[]{eol, kinds > 1 ? "1" : "0"};
    }

    public static String toLf(String s) {
        if (s.indexOf('\r') < 0) return s;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String fromLf(String s, String eol) {
        if ("CRLF".equals(eol)) return s.replace("\n", "\r\n");
        if ("CR".equals(eol)) return s.replace('\n', '\r');
        return s;
    }

    /** Повний цикл читання: визначення (або задане кодування), декодування, EOL. */
    public static final class Text {
        public String text, enc, eol;
        public boolean bom, binary, mixed;
    }

    public static Text read(byte[] b, int len, String forceEnc) {
        Text t = new Text();
        Detected d = detect(b, len);
        t.binary = d.binary;
        if (forceEnc != null && forceEnc.length() > 0 && !"auto".equals(forceEnc)) {
            t.enc = forceEnc.toLowerCase();
            t.bom = bomLength(b, len, t.enc, true) > 0;
        } else {
            t.enc = d.enc;
            t.bom = d.bom;
        }
        String raw = decode(b, len, t.enc);
        String[] e = detectEol(raw);
        t.eol = e[0];
        t.mixed = "1".equals(e[1]);
        t.text = toLf(raw);
        return t;
    }

    public static byte[] write(String lfText, String enc, boolean bom, String eol) {
        return encode(fromLf(lfText, eol), enc, bom);
    }
}
