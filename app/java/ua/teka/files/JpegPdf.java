package ua.teka.files;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Найпростіший PDF із JPEG-зображень: кожна сторінка — одне зображення, вбудоване як є
 * (фільтр DCTDecode), без перекодування. Тому PDF зі світлин займає приблизно стільки ж,
 * скільки самі JPEG-файли, а якість не втрачається.
 */
public final class JpegPdf implements Closeable {
    public static final float A4_W = 595.28f, A4_H = 841.89f, LETTER_W = 612f, LETTER_H = 792f;

    /** Ширина, висота, кількість каналів і чи це Adobe CMYK (інвертований) — або null, якщо не JPEG. */
    public static int[] jpegInfo(byte[] b, int len) {
        if (len < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;
        int i = 2;
        boolean adobe = false;
        while (i + 4 <= len) {
            if ((b[i] & 0xFF) != 0xFF) return null;
            int m = b[i + 1] & 0xFF;
            if (m == 0xFF) { i++; continue; }
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
            if (m == 0xD9 || m == 0xDA) return null; // дійшли до даних, а розмірів немає
            int seg = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (seg < 2) return null;
            if (m == 0xEE && seg >= 7 && i + 9 <= len && new String(b, i + 4, 5, StandardCharsets.US_ASCII).equals("Adobe")) adobe = true;
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                if (i + 10 > len) return null;
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                int comps = b[i + 9] & 0xFF;
                if (w <= 0 || h <= 0) return null;
                return new int[]{w, h, comps, adobe ? 1 : 0};
            }
            i += 2 + seg;
        }
        return null;
    }

    static final class Counting extends FilterOutputStream {
        long n;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
    }

    final Counting out;
    final List<Long> offsets = new ArrayList<>(); // індекс = номер об'єкта - 1
    final List<Integer> pages = new ArrayList<>();
    boolean closed;

    public JpegPdf(OutputStream o) throws IOException {
        out = new Counting(o);
        // 1 — каталог, 2 — дерево сторінок: запишемо наприкінці
        offsets.add(0L);
        offsets.add(0L);
        raw("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    void raw(byte[] b) throws IOException { out.write(b); }
    void str(String s) throws IOException { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); }

    int begin() throws IOException {
        offsets.add(out.n);
        int id = offsets.size();
        str(id + " 0 obj\n");
        return id;
    }

    void begin(int id) throws IOException {
        offsets.set(id - 1, out.n);
        str(id + " 0 obj\n");
    }

    static String num(float v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s.equals("-0") ? "0" : s;
    }

    /**
     * Сторінка pageW×pageH пунктів (1/72 дюйма); зображення малюється в прямокутнику
     * (x, y, w, h) від лівого нижнього кута.
     */
    public void page(byte[] jpeg, int len, float pageW, float pageH, float x, float y, float w, float h) throws IOException {
        int[] info = jpegInfo(jpeg, len);
        if (info == null) throw new IOException("Не JPEG");
        String cs = info[2] == 1 ? "/DeviceGray" : info[2] == 4 ? "/DeviceCMYK" : "/DeviceRGB";
        String decode = info[2] == 4 && info[3] == 1 ? " /Decode [1 0 1 0 1 0 1 0]" : "";
        int img = begin();
        str("<< /Type /XObject /Subtype /Image /Width " + info[0] + " /Height " + info[1]
            + " /ColorSpace " + cs + " /BitsPerComponent 8" + decode + " /Filter /DCTDecode /Length " + len + " >>\nstream\n");
        out.write(jpeg, 0, len);
        str("\nendstream\nendobj\n");

        byte[] content = ("q " + num(w) + " 0 0 " + num(h) + " " + num(x) + " " + num(y) + " cm /Im0 Do Q\n").getBytes(StandardCharsets.ISO_8859_1);
        int cont = begin();
        str("<< /Length " + content.length + " >>\nstream\n");
        raw(content);
        str("endstream\nendobj\n");

        int pg = begin();
        str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(pageW) + " " + num(pageH) + "] /Resources << /XObject << /Im0 "
            + img + " 0 R >> >> /Contents " + cont + " 0 R >>\nendobj\n");
        pages.add(pg);
    }

    /** Розміщення зображення w×h на сторінці з полями: вписати, зберігши пропорції, по центру. */
    public static float[] fit(float pageW, float pageH, float margin, int imgW, int imgH) {
        float aw = Math.max(1, pageW - 2 * margin), ah = Math.max(1, pageH - 2 * margin);
        float s = Math.min(aw / imgW, ah / imgH);
        float w = imgW * s, h = imgH * s;
        return new float[]{(pageW - w) / 2, (pageH - h) / 2, w, h};
    }

    public int pageCount() { return pages.size(); }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        StringBuilder kids = new StringBuilder();
        for (int p : pages) kids.append(p).append(" 0 R ");
        begin(2);
        str("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>\nendobj\n");
        begin(1);
        str("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int info = begin();
        str("<< /Producer (Teka) >>\nendobj\n");
        long xref = out.n;
        StringBuilder sb = new StringBuilder();
        sb.append("xref\n0 ").append(offsets.size() + 1).append('\n');
        sb.append("0000000000 65535 f \n");
        for (long off : offsets) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
        sb.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R /Info ").append(info).append(" 0 R >>\nstartxref\n")
            .append(xref).append("\n%%EOF\n");
        str(sb.toString());
        out.flush();
        out.close();
    }
}
