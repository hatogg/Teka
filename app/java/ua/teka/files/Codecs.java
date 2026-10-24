package ua.teka.files;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Платформонезалежні частини конвертера: BMP, WAV, перетворення PCM, діапазони сторінок. */
public final class Codecs {
    private Codecs() {}

    // ------------------------------------------------------------ BMP

    public interface Rows { void row(int y, int[] argb); }

    /** 24-бітний BMP. Прозорість накладається на білий фон. */
    public static void writeBmp(OutputStream o, int w, int h, Rows rows) throws IOException {
        int stride = (w * 3 + 3) & ~3;
        long dataSize = (long) stride * h;
        if (dataSize + 54 > Integer.MAX_VALUE) throw new IOException("Зображення завелике для BMP");
        OutputStream out = new BufferedOutputStream(o, 1 << 16);
        byte[] hdr = new byte[54];
        hdr[0] = 'B'; hdr[1] = 'M';
        le32(hdr, 2, (int) (54 + dataSize));
        le32(hdr, 10, 54);
        le32(hdr, 14, 40);
        le32(hdr, 18, w);
        le32(hdr, 22, h);          // додатна висота — рядки знизу вгору
        le16(hdr, 26, 1);
        le16(hdr, 28, 24);
        le32(hdr, 34, (int) dataSize);
        le32(hdr, 38, 2835);       // 72 dpi
        le32(hdr, 42, 2835);
        out.write(hdr);
        int[] px = new int[w];
        byte[] line = new byte[stride];
        for (int y = h - 1; y >= 0; y--) {
            rows.row(y, px);
            for (int x = 0; x < w; x++) {
                int c = px[x];
                int a = c >>> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (a != 255) {
                    r = (r * a + 255 * (255 - a)) / 255;
                    g = (g * a + 255 * (255 - a)) / 255;
                    b = (b * a + 255 * (255 - a)) / 255;
                }
                line[x * 3] = (byte) b;
                line[x * 3 + 1] = (byte) g;
                line[x * 3 + 2] = (byte) r;
            }
            out.write(line);
        }
        out.flush();
    }

    static void le16(byte[] b, int off, int v) { b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); }
    static void le32(byte[] b, int off, int v) { le16(b, off, v); le16(b, off + 2, v >> 16); }

    // ------------------------------------------------------------ WAV

    /** PCM 16 біт у WAV; розміри в заголовку дописуються під час закриття. */
    public static final class WavOut implements Closeable {
        final File file;
        final OutputStream out;
        final int rate, ch;
        long data;

        public WavOut(File file, int rate, int ch) throws IOException {
            this.file = file; this.rate = rate; this.ch = ch;
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(header(0));
        }

        byte[] header(long dataLen) {
            byte[] h = new byte[44];
            int d = (int) Math.min(dataLen, 0xFFFFFFFFL - 36);
            h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
            le32(h, 4, 36 + d);
            h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
            h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
            le32(h, 16, 16);
            le16(h, 20, 1);
            le16(h, 22, ch);
            le32(h, 24, rate);
            le32(h, 28, rate * ch * 2);
            le16(h, 32, ch * 2);
            le16(h, 34, 16);
            h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
            le32(h, 40, d);
            return h;
        }

        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            data += len;
        }

        @Override
        public void close() throws IOException {
            out.close();
            try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
                f.seek(0);
                f.write(header(data));
            }
        }
    }

    // ------------------------------------------------------------ PCM

    public static final int PCM_16 = 2, PCM_8 = 3, PCM_FLOAT = 4, PCM_24 = 21, PCM_32 = 22;

    /**
     * Перетворює PCM декодера на 16-бітний, щонайбільше maxCh каналів, частота не вища за maxRate
     * (проріджування в ціле число разів з усередненням: 96 кГц → 48 кГц, 88,2 → 44,1).
     */
    public static final class Pcm {
        final int enc, inCh, outCh, factor, bytesPer;
        final int outRate;
        final long[] acc;
        int accN;
        byte[] carry = new byte[0];

        public Pcm(int enc, int channels, int rate, int maxCh, int maxRate) {
            this.enc = enc;
            this.inCh = Math.max(1, channels);
            this.outCh = Math.min(inCh, maxCh);
            int f = 1;
            if (maxRate > 0) while (rate / f > maxRate) f++;
            this.factor = f;
            this.outRate = rate / f;
            this.bytesPer = enc == PCM_8 ? 1 : enc == PCM_24 ? 3 : (enc == PCM_FLOAT || enc == PCM_32) ? 4 : 2;
            this.acc = new long[outCh];
        }

        public int outRate() { return outRate; }
        public int outChannels() { return outCh; }
        public boolean passthrough() { return enc == PCM_16 && inCh == outCh && factor == 1; }

        int sample(byte[] b, int i) {
            switch (enc) {
                case PCM_8: return ((b[i] & 0xFF) - 128) << 8;
                case PCM_24: return ((b[i + 2]) << 8) | (b[i + 1] & 0xFF);
                case PCM_32: return ((b[i + 3]) << 8) | (b[i + 2] & 0xFF);
                case PCM_FLOAT: {
                    int bits = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
                    float v = Float.intBitsToFloat(bits);
                    int s = Math.round(v * 32767f);
                    return s > 32767 ? 32767 : (s < -32768 ? -32768 : s);
                }
                default: return (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            }
        }

        /** Повертає 16-бітний PCM (little-endian, чергування каналів). */
        public byte[] convert(byte[] in, int off, int len) {
            if (passthrough() && carry.length == 0 && len % (2 * inCh) == 0) {
                byte[] r = new byte[len];
                System.arraycopy(in, off, r, 0, len);
                return r;
            }
            byte[] b;
            if (carry.length > 0) {
                b = new byte[carry.length + len];
                System.arraycopy(carry, 0, b, 0, carry.length);
                System.arraycopy(in, off, b, carry.length, len);
                off = 0;
                len = b.length;
            } else b = in;
            int frame = bytesPer * inCh;
            int frames = len / frame;
            int rest = len - frames * frame;
            carry = new byte[rest];
            if (rest > 0) System.arraycopy(b, off + frames * frame, carry, 0, rest);
            byte[] out = new byte[(frames / factor + 1) * outCh * 2];
            int o = 0;
            for (int f = 0; f < frames; f++) {
                int base = off + f * frame;
                for (int c = 0; c < outCh; c++) acc[c] += sample(b, base + c * bytesPer);
                if (++accN == factor) {
                    for (int c = 0; c < outCh; c++) {
                        int s = (int) (acc[c] / factor);
                        out[o++] = (byte) s;
                        out[o++] = (byte) (s >> 8);
                        acc[c] = 0;
                    }
                    accN = 0;
                }
            }
            if (o == out.length) return out;
            byte[] r = new byte[o];
            System.arraycopy(out, 0, r, 0, o);
            return r;
        }
    }

    // ------------------------------------------------------------ діапазони сторінок

    /**
     * «1-3, 5, 8-» → індекси сторінок від 0. Порожній рядок — усі сторінки.
     * Повертає null, якщо запис неправильний або виходить за межі документа.
     */
    public static List<Integer> pages(String spec, int total) {
        List<Integer> out = new ArrayList<>();
        if (spec == null || spec.trim().isEmpty()) {
            for (int i = 0; i < total; i++) out.add(i);
            return out;
        }
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : spec.replace(';', ',').split(",")) {
            String p = part.trim().replace('–', '-').replace('—', '-');
            if (p.isEmpty()) continue;
            int a, b;
            try {
                int dash = p.indexOf('-');
                if (dash < 0) {
                    a = b = Integer.parseInt(p);
                } else {
                    String l = p.substring(0, dash).trim(), r = p.substring(dash + 1).trim();
                    a = l.isEmpty() ? 1 : Integer.parseInt(l);
                    b = r.isEmpty() ? total : Integer.parseInt(r);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            if (a < 1 || b > total || a > b) return null;
            for (int i = a; i <= b; i++) set.add(i - 1);
        }
        if (set.isEmpty()) return null;
        out.addAll(set);
        return out;
    }
}
