package ua.teka.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Конвертер: зображення (JPG, PNG, WEBP, BMP; зміна розміру), зображення → PDF, PDF → зображення,
 * текст → PDF, перекодування тексту, звук → M4A (AAC) або WAV, звук із відео, відео без звуку.
 * Працює всередині завдання {@link Ops.Job}: прогрес — 1000 одиниць на кожен файл.
 */
final class Convert {
    private Convert() {}

    static final long MAX_PIXELS = 48_000_000L;

    static void run(Context c, Ops.Job j, JSONObject p, JSONObject res) throws Exception {
        String mode = p.getString("mode");
        List<File> srcs = j.files("srcs");
        String destS = p.optString("dest", "");
        j.filesTotal = "img2pdf".equals(mode) ? 1 : srcs.size();
        j.total = 1000L * srcs.size();
        JSONArray outs = new JSONArray(), errs = new JSONArray();
        if ("img2pdf".equals(mode)) {
            File dest = destS.isEmpty() ? srcs.get(0).getParentFile() : new File(destS);
            File out = imagesToPdf(j, srcs, dest, p, errs);
            outs.put(out.getAbsolutePath());
            j.touched.add(out.getAbsolutePath());
            j.files = 1;
        } else {
            for (int i = 0; i < srcs.size(); i++) {
                File s = srcs.get(i);
                j.check();
                j.done = 1000L * i;
                j.cur = s.getName();
                j.progress(true);
                File dest = destS.isEmpty() ? s.getParentFile() : new File(destS);
                try {
                    for (File f : one(j, i, mode, s, dest, p)) {
                        outs.put(f.getAbsolutePath());
                        j.touched.add(f.getAbsolutePath());
                    }
                } catch (Ops.Cancelled | Archive.Cancelled e) {
                    throw e;
                } catch (OutOfMemoryError e) {
                    errs.put(new JSONObject().put("name", s.getName()).put("err", "забракло пам’яті"));
                } catch (Exception e) {
                    errs.put(new JSONObject().put("name", s.getName()).put("err", msg(e)));
                }
                j.files++;
            }
        }
        j.done = j.total;
        res.put("paths", outs).put("errors", errs);
        if (outs.length() == 0 && errs.length() > 0) throw new IOException(errs.getJSONObject(0).getString("err"));
    }

    static String msg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        if (m.contains("ENOSPC")) return "немає вільного місця";
        if (m.contains("EACCES") || m.contains("EPERM")) return "немає доступу";
        return m;
    }

    static void frac(Ops.Job j, int idx, double f) {
        if (f < 0) f = 0;
        if (f > 1) f = 1;
        j.done = 1000L * idx + (long) (f * 999);
        j.progress(false);
    }

    static List<File> one(Ops.Job j, int idx, String mode, File s, File dest, JSONObject p) throws Exception {
        switch (mode) {
            case "image": return list(image(s, dest, p));
            case "pdf2img": return pdfToImages(j, idx, s, dest, p);
            case "txt2pdf": return list(textToPdf(j, idx, s, dest, p));
            case "text": return list(reencode(s, dest, p));
            case "audio": return list(audio(j, idx, s, dest, p));
            case "mute": return list(mute(j, idx, s, dest));
            default: throw new IOException("Невідомий вид конвертації: " + mode);
        }
    }

    static List<File> list(File f) {
        List<File> l = new ArrayList<>();
        l.add(f);
        return l;
    }

    static File target(File dest, String base, String ext) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Немає папки призначення");
        return new File(dest, Names.unique(dest, base + ext));
    }

    static byte[] readAll(File f, long limit, String tooBig) throws IOException {
        long len = f.length();
        if (len > limit) throw new IOException(tooBig);
        byte[] b = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            if (off < b.length) return Arrays.copyOf(b, off);
        }
        return b;
    }

    // ================================================================ зображення

    static int exifOrientation(File f) {
        try {
            return new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    static Bitmap orient(Bitmap b, int o) {
        if (o <= 1 || o > 8) return b;
        Matrix m = new Matrix();
        switch (o) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(270); break;
            default: return b;
        }
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /** Розміри зображення з урахуванням повороту EXIF: {ширина, висота} або null. */
    static int[] bounds(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int or = exifOrientation(f);
        boolean swap = or >= 5 && or <= 8;
        return swap ? new int[]{o.outHeight, o.outWidth} : new int[]{o.outWidth, o.outHeight};
    }

    /** Декодує зображення, повертає за EXIF і зменшує так, щоб довша сторона не перевищувала maxSide (0 — без обмеження). */
    static Bitmap decode(File f, int maxSide) throws IOException {
        String path = f.getAbsolutePath();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        Bitmap b = null;
        int rot = exifOrientation(f);
        if (o.outWidth > 0 && o.outHeight > 0) {
            int sample = 1;
            if (maxSide > 0) while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            while ((long) (o.outWidth / sample) * (o.outHeight / sample) > MAX_PIXELS) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeFile(path, d);
        }
        if (b == null && Build.VERSION.SDK_INT >= 28) {
            b = decodeModern(f, maxSide);
            rot = 1; // ImageDecoder сам враховує поворот
        }
        if (b == null) throw new IOException("Не вдалося прочитати зображення (формат не підтримується)");
        b = orient(b, rot);
        if (maxSide > 0 && Math.max(b.getWidth(), b.getHeight()) > maxSide) {
            float s = (float) maxSide / Math.max(b.getWidth(), b.getHeight());
            Bitmap r = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    static Bitmap decodeModern(File f, int maxSide) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(f);
            return ImageDecoder.decodeBitmap(src, (dec, info, s) -> {
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float k = 1f;
                if (maxSide > 0 && Math.max(w, h) > maxSide) k = (float) maxSide / Math.max(w, h);
                if ((long) (w * k) * (long) (h * k) > MAX_PIXELS) k = (float) Math.sqrt((double) MAX_PIXELS / ((long) w * h));
                if (k < 1f) dec.setTargetSize(Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)));
            });
        } catch (Throwable e) {
            return null;
        }
    }

    static Bitmap onWhite(Bitmap b) {
        if (!b.hasAlpha()) return b;
        Bitmap w = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(w);
        cv.drawColor(Color.WHITE);
        cv.drawBitmap(b, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return w;
    }

    @SuppressWarnings("deprecation")
    static void encode(Bitmap b, String fmt, int q, OutputStream out) throws IOException {
        if ("bmp".equals(fmt)) {
            final Bitmap bb = b;
            final int w = b.getWidth();
            Codecs.writeBmp(out, w, b.getHeight(), (y, row) -> bb.getPixels(row, 0, w, 0, y, w, 1));
            return;
        }
        Bitmap src = b;
        Bitmap.CompressFormat cf;
        switch (fmt) {
            case "png": cf = Bitmap.CompressFormat.PNG; break;
            case "webp":
                if (Build.VERSION.SDK_INT >= 30) cf = q >= 100 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP_LOSSY;
                else cf = Bitmap.CompressFormat.WEBP;
                break;
            default:
                cf = Bitmap.CompressFormat.JPEG;
                src = onWhite(b);
        }
        boolean ok = src.compress(cf, Math.max(1, Math.min(100, q)), out);
        if (src != b) src.recycle();
        if (!ok) throw new IOException("Не вдалося записати зображення");
    }

    static final String[] EXIF_TAGS = {
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    };

    static void copyExif(File from, File to) {
        try {
            ExifInterface a = new ExifInterface(from.getAbsolutePath());
            ExifInterface b = new ExifInterface(to.getAbsolutePath());
            boolean any = false;
            for (String t : EXIF_TAGS) {
                String v = a.getAttribute(t);
                if (v != null) { b.setAttribute(t, v); any = true; }
            }
            if (any) {
                b.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                b.saveAttributes();
            }
        } catch (Throwable ignored) {}
    }

    static String extFor(String fmt) {
        return "jpg".equals(fmt) || "jpeg".equals(fmt) ? ".jpg" : "." + fmt;
    }

    static File image(File s, File dest, JSONObject p) throws Exception {
        String fmt = p.optString("format", "jpg").toLowerCase(Locale.ROOT);
        int q = p.optInt("quality", 90);
        int maxSide = p.optInt("maxSide", 0);
        int percent = p.optInt("percent", 100);
        if (percent > 0 && percent < 100 && maxSide == 0) {
            int[] wh = bounds(s);
            if (wh != null) maxSide = Math.max(1, Math.max(wh[0], wh[1]) * percent / 100);
        }
        Bitmap b = decode(s, maxSide);
        File out = target(dest, Names.split(s.getName())[0], extFor(fmt));
        boolean ok = false;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            encode(b, fmt, q, o);
            ok = true;
        } finally {
            b.recycle();
            if (!ok) out.delete();
        }
        if (".jpg".equals(extFor(fmt)) && p.optBoolean("keepExif", true)) copyExif(s, out);
        out.setLastModified(s.lastModified());
        return out;
    }

    // ================================================================ зображення → PDF

    static File imagesToPdf(Ops.Job j, List<File> srcs, File dest, JSONObject p, JSONArray errs) throws Exception {
        String base = p.optString("name", "");
        if (base.isEmpty()) base = srcs.size() == 1 ? Names.split(srcs.get(0).getName())[0] : Names.split(srcs.get(0).getName())[0] + " та ін.";
        if (base.toLowerCase(Locale.ROOT).endsWith(".pdf")) base = base.substring(0, base.length() - 4);
        String bad = Names.invalid(base + ".pdf");
        if (bad != null) throw new IOException(bad);
        String page = p.optString("page", "a4");
        float margin = (float) p.optDouble("margin", 0);
        String quality = p.optString("quality", "original");
        int maxSide = "high".equals(quality) ? 3000 : "medium".equals(quality) ? 2000 : "low".equals(quality) ? 1200 : 0;
        File out = target(dest, base, ".pdf");
        boolean ok = false;
        int added = 0;
        try (JpegPdf pdf = new JpegPdf(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            for (int i = 0; i < srcs.size(); i++) {
                File s = srcs.get(i);
                j.check();
                j.cur = s.getName();
                frac(j, i, 0);
                try {
                    byte[] jpg = null;
                    int w = 0, h = 0;
                    if (maxSide == 0 && s.length() < (64L << 20) && exifOrientation(s) <= 1) {
                        byte[] raw = readAll(s, 64L << 20, "");
                        int[] info = JpegPdf.jpegInfo(raw, raw.length);
                        if (info != null) { jpg = raw; w = info[0]; h = info[1]; }
                    }
                    if (jpg == null) {
                        Bitmap b = decode(s, maxSide);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        try { encode(b, "jpg", maxSide == 0 ? 92 : 85, bo); } finally { w = b.getWidth(); h = b.getHeight(); b.recycle(); }
                        jpg = bo.toByteArray();
                    }
                    float pw, ph, m = margin;
                    if ("fit".equals(page)) {
                        // сторінка за розміром зображення (96 точок на дюйм), не більша за межу PDF 14 400 пт
                        pw = w * 0.75f + 2 * margin;
                        ph = h * 0.75f + 2 * margin;
                        float k = Math.min(1f, 14400f / Math.max(pw, ph));
                        pw *= k; ph *= k; m *= k;
                    } else {
                        float a = "letter".equals(page) ? JpegPdf.LETTER_W : JpegPdf.A4_W;
                        float bb = "letter".equals(page) ? JpegPdf.LETTER_H : JpegPdf.A4_H;
                        boolean land = w > h;
                        pw = land ? bb : a;
                        ph = land ? a : bb;
                    }
                    float[] r = JpegPdf.fit(pw, ph, m, w, h);
                    pdf.page(jpg, jpg.length, pw, ph, r[0], r[1], r[2], r[3]);
                    added++;
                } catch (Ops.Cancelled e) {
                    throw e;
                } catch (OutOfMemoryError e) {
                    errs.put(new JSONObject().put("name", s.getName()).put("err", "забракло пам’яті"));
                } catch (Exception e) {
                    errs.put(new JSONObject().put("name", s.getName()).put("err", msg(e)));
                }
                frac(j, i, 1);
            }
            if (added == 0) throw new IOException(errs.length() > 0 ? errs.getJSONObject(0).getString("err") : "Немає зображень");
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
        return out;
    }

    // ================================================================ PDF → зображення

    static List<File> pdfToImages(Ops.Job j, int idx, File s, File dest, JSONObject p) throws Exception {
        String fmt = p.optString("format", "jpg").toLowerCase(Locale.ROOT);
        int dpi = Math.max(36, Math.min(400, p.optInt("dpi", 150)));
        int q = p.optInt("quality", 90);
        List<File> out = new ArrayList<>();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(s, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer r;
            try {
                r = new PdfRenderer(fd);
            } catch (SecurityException e) {
                throw new IOException("PDF захищено паролем");
            } catch (IOException e) {
                throw new IOException("Пошкоджений або непідтримуваний PDF");
            }
            try {
                int n = r.getPageCount();
                List<Integer> pages = Codecs.pages(p.optString("pages", ""), n);
                if (pages == null) throw new IOException("Неправильні номери сторінок (у документі " + n + ")");
                String base = Names.split(s.getName())[0];
                File folder = dest;
                if (pages.size() > 1) {
                    folder = new File(dest, Names.unique(dest, base));
                    if (!folder.mkdirs()) throw new IOException("Не вдалося створити папку");
                }
                int digits = Math.max(2, String.valueOf(n).length());
                for (int k = 0; k < pages.size(); k++) {
                    j.check();
                    int pi = pages.get(k);
                    try (PdfRenderer.Page pg = r.openPage(pi)) {
                        double scale = dpi / 72.0;
                        double w = pg.getWidth() * scale, h = pg.getHeight() * scale;
                        double lim = Math.min(1.0, Math.min(10000.0 / Math.max(w, h), Math.sqrt(40_000_000.0 / (w * h))));
                        int bw = Math.max(1, (int) (w * lim)), bh = Math.max(1, (int) (h * lim));
                        Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                        bm.eraseColor(Color.WHITE);
                        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String name = base + "-" + String.format(Locale.ROOT, "%0" + digits + "d", pi + 1);
                        File f = target(folder, name, extFor(fmt));
                        boolean ok = false;
                        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
                            encode(bm, fmt, q, o);
                            ok = true;
                        } finally {
                            bm.recycle();
                            if (!ok) f.delete();
                        }
                        out.add(f);
                    }
                    frac(j, idx, (k + 1.0) / pages.size());
                }
            } finally {
                r.close();
            }
        }
        return out;
    }

    // ================================================================ текст → PDF

    static File textToPdf(Ops.Job j, int idx, File s, File dest, JSONObject p) throws Exception {
        byte[] b = readAll(s, 5L << 20, "Текст завеликий для PDF (понад 5 МБ)");
        Encodings.Text t = Encodings.read(b, b.length, p.optString("from", "auto"));
        if (t.binary) throw new IOException("Це не текстовий файл");
        float size = (float) Math.max(6, Math.min(24, p.optDouble("fontSize", 11)));
        boolean mono = p.optBoolean("mono", false);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(size);
        tp.setColor(Color.BLACK);
        tp.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT);
        int pw = 595, ph = 842, margin = 48, footer = 18;
        int cw = pw - 2 * margin, chH = ph - 2 * margin - footer;
        String text = t.text.replace("\t", "    ");
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, cw)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0, 1.15f)
            .setIncludePad(false)
            .build();
        TextPaint fp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        fp.setTextSize(8);
        fp.setColor(0xFF888888);
        fp.setTextAlign(Paint.Align.CENTER);
        PdfDocument doc = new PdfDocument();
        File out = target(dest, Names.split(s.getName())[0], ".pdf");
        boolean ok = false;
        try {
            int lines = layout.getLineCount(), line = 0, pageNo = 0;
            do {
                j.check();
                int top = lines == 0 ? 0 : layout.getLineTop(line);
                int end = line;
                while (end < lines && layout.getLineBottom(end) - top <= chH) end++;
                if (end == line && lines > 0) end = line + 1;
                PdfDocument.Page pg = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, ++pageNo).create());
                Canvas cv = pg.getCanvas();
                if (lines > 0) {
                    int bottom = layout.getLineBottom(end - 1);
                    cv.save();
                    cv.translate(margin, margin - top);
                    cv.clipRect(0, top, cw, bottom);
                    layout.draw(cv);
                    cv.restore();
                }
                cv.drawText(String.valueOf(pageNo), pw / 2f, ph - margin + footer / 2f, fp);
                doc.finishPage(pg);
                line = end;
                frac(j, idx, lines == 0 ? 1 : (double) line / lines);
            } while (line < lines);
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                doc.writeTo(o);
            }
            ok = true;
        } finally {
            doc.close();
            if (!ok) out.delete();
        }
        return out;
    }

    // ================================================================ перекодування тексту

    static String encLabel(String id) {
        for (String[] e : Encodings.SUPPORTED) if (e[0].equalsIgnoreCase(id)) return e[1].replace("IBM866", "CP866");
        return id.toUpperCase(Locale.ROOT);
    }

    static File reencode(File s, File dest, JSONObject p) throws Exception {
        byte[] b = readAll(s, 64L << 20, "Файл завеликий (понад 64 МБ)");
        Encodings.Text t = Encodings.read(b, b.length, p.optString("from", "auto"));
        if (t.binary) throw new IOException("Це не текстовий файл");
        String enc = p.optString("enc", "utf-8");
        String eol = p.optString("eol", "keep");
        if ("keep".equals(eol)) eol = t.eol;
        int bad = Encodings.unmappable(t.text, enc);
        if (bad > 0 && !p.optBoolean("force", false)) {
            throw new IOException(bad + " символ(ів) не можна записати в " + encLabel(enc) + " — оберіть інше кодування");
        }
        byte[] out = Encodings.write(t.text, enc, p.optBoolean("bom", false), eol);
        String[] nm = Names.split(s.getName());
        File f;
        if (p.optBoolean("inPlace", false)) {
            f = s;
            File tmp = new File(s.getParentFile(), ".teka_enc_" + System.nanoTime());
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(out); }
            if (!tmp.renameTo(s)) {
                tmp.delete();
                throw new IOException("Не вдалося замінити файл");
            }
        } else {
            f = target(dest, nm[0] + " (" + encLabel(enc) + ")", nm[1]);
            try (OutputStream o = new FileOutputStream(f)) { o.write(out); }
        }
        return f;
    }

    // ================================================================ звук і відео

    static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;

    static int trackOf(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    static File audio(Ops.Job j, int idx, File s, File dest, JSONObject p) throws Exception {
        String fmt = p.optString("format", "m4a");
        int kbps = Math.max(32, Math.min(320, p.optInt("bitrate", 192)));
        String base = Names.split(s.getName())[0];
        File out = target(dest, base, "wav".equals(fmt) ? ".wav" : ".m4a");
        boolean ok = false;
        try {
            MediaExtractor probe = new MediaExtractor();
            String mime;
            try {
                probe.setDataSource(s.getAbsolutePath());
                int t = trackOf(probe, "audio/");
                if (t < 0) throw new IOException("У файлі немає звукової доріжки");
                mime = probe.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
            } catch (IOException e) {
                throw e.getMessage() != null && e.getMessage().startsWith("У файлі") ? e : new IOException("Формат файлу не підтримується");
            } finally {
                probe.release();
            }
            boolean copied = false;
            if (!"wav".equals(fmt) && AAC.equals(mime) && !p.optBoolean("reencode", false)) {
                try {
                    remux(j, idx, s, out, true, false, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    copied = true;
                } catch (Ops.Cancelled e) {
                    throw e;
                } catch (Exception e) {
                    out.delete();
                }
            }
            if (!copied) transcode(j, idx, s, out, "wav".equals(fmt), kbps);
            ok = true;
        } finally {
            if (!ok) out.delete();
        }
        return out;
    }

    static File mute(Ops.Job j, int idx, File s, File dest) throws Exception {
        MediaExtractor probe = new MediaExtractor();
        String mime;
        try {
            probe.setDataSource(s.getAbsolutePath());
            int t = trackOf(probe, "video/");
            if (t < 0) throw new IOException("У файлі немає відео");
            mime = probe.getTrackFormat(t).getString(MediaFormat.KEY_MIME);
        } finally {
            probe.release();
        }
        boolean webm = mime.contains("vp8") || mime.contains("vp9");
        File out = target(dest, Names.split(s.getName())[0] + " (без звуку)", webm ? ".webm" : ".mp4");
        boolean ok = false;
        try {
            remux(j, idx, s, out, false, true, webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            ok = true;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Цей формат відео не вдається зберегти без перекодування");
        } finally {
            if (!ok) out.delete();
        }
        out.setLastModified(s.lastModified());
        return out;
    }

    static int rotation(File s) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(s.getAbsolutePath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /** Перепаковує вибрані доріжки без перекодування. */
    static void remux(Ops.Job j, int idx, File s, File out, boolean audio, boolean video, int format) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ex.setDataSource(s.getAbsolutePath());
            mux = new MediaMuxer(out.getAbsolutePath(), format);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            Arrays.fill(map, -1);
            long dur = 0;
            int maxSize = 256 << 10;
            boolean any = false, hasVideo = false;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                boolean isA = m.startsWith("audio/"), isV = m.startsWith("video/");
                if (!((isA && audio) || (isV && video))) continue;
                if (isV && hasVideo) continue;
                map[i] = mux.addTrack(f);
                ex.selectTrack(i);
                any = true;
                if (isV) { hasVideo = true; maxSize = Math.max(maxSize, 8 << 20); }
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = Math.max(dur, f.getLong(MediaFormat.KEY_DURATION));
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = Math.max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                if (isA && !video) break;
            }
            if (!any) throw new IOException(audio ? "У файлі немає звукової доріжки" : "У файлі немає відео");
            if (hasVideo) {
                int rot = rotation(s);
                if (rot != 0) mux.setOrientationHint(rot);
            }
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                j.check();
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (map[t] >= 0) {
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = Math.max(0, ex.getSampleTime());
                    bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    mux.writeSampleData(map[t], buf, bi);
                    if (dur > 0) frac(j, idx, (double) bi.presentationTimeUs / dur);
                }
                ex.advance();
            }
        } finally {
            ex.release();
            if (mux != null) {
                try { if (started) mux.stop(); } catch (Exception ignored) {}
                try { mux.release(); } catch (Exception ignored) {}
            }
        }
    }

    /** Декодує звукову доріжку і кодує в AAC (M4A) або пише PCM у WAV. */
    static void transcode(Ops.Job j, int idx, File s, File out, boolean wavOut, int kbps) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        Codecs.WavOut wav = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(s.getAbsolutePath());
            int track = trackOf(ex, "audio/");
            if (track < 0) throw new IOException("У файлі немає звукової доріжки");
            MediaFormat inF = ex.getTrackFormat(track);
            ex.selectTrack(track);
            long dur = inF.containsKey(MediaFormat.KEY_DURATION) ? inF.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = inF.getString(MediaFormat.KEY_MIME);
            try {
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(inF, null, null, 0);
            } catch (Exception e) {
                throw new IOException("Немає декодера для " + mime.replace("audio/", ""));
            }
            dec.start();

            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), ei = new MediaCodec.BufferInfo();
            boolean inEos = false, decEos = false, encInEos = false, done = false;
            Codecs.Pcm pcm = null;
            byte[] pending = null;
            int pendingOff = 0;
            long samples = 0;
            int muxTrack = -1;
            int frame = 4;

            while (!done) {
                j.check();
                // 1. файл → декодер
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, Math.max(0, ex.getSampleTime()), 0);
                            ex.advance();
                        }
                    }
                }
                // 2. декодер → PCM
                if (!decEos && pending == null) {
                    int oi = dec.dequeueOutputBuffer(di, 5000);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (oi >= 0 && pcm == null)) {
                        MediaFormat of = dec.getOutputFormat();
                        int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        int pe = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : Codecs.PCM_16;
                        if (pcm == null) {
                            pcm = wavOut ? new Codecs.Pcm(pe, ch, rate, 8, 0) : new Codecs.Pcm(pe, ch, rate, 2, 48000);
                            frame = 2 * pcm.outChannels();
                            if (wavOut) {
                                wav = new Codecs.WavOut(out, pcm.outRate(), pcm.outChannels());
                            } else {
                                MediaFormat ef = MediaFormat.createAudioFormat(AAC, pcm.outRate(), pcm.outChannels());
                                ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                                ef.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
                                ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 16);
                                try {
                                    enc = MediaCodec.createEncoderByType(AAC);
                                    enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                                } catch (Exception e) {
                                    throw new IOException("Кодер AAC не підтримує " + pcm.outRate() + " Гц");
                                }
                                enc.start();
                                mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                            }
                        }
                    }
                    if (oi >= 0) {
                        if (di.size > 0) {
                            ByteBuffer ob = dec.getOutputBuffer(oi);
                            byte[] raw = new byte[di.size];
                            ob.position(di.offset);
                            ob.get(raw, 0, di.size);
                            byte[] conv = pcm.convert(raw, 0, raw.length);
                            if (wav != null) {
                                wav.write(conv, 0, conv.length);
                            } else if (conv.length > 0) {
                                pending = conv;
                                pendingOff = 0;
                            }
                            if (dur > 0) frac(j, idx, (double) di.presentationTimeUs / dur);
                        }
                        dec.releaseOutputBuffer(oi, false);
                        if ((di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decEos = true;
                            if (pcm == null) throw new IOException("Не вдалося декодувати звук");
                            if (wavOut) done = true;
                        }
                    }
                }
                if (wavOut || enc == null) {
                    if (decEos && enc == null && !wavOut) throw new IOException("Не вдалося декодувати звук");
                    continue;
                }
                // 3. PCM → кодер
                if (pending != null || (decEos && !encInEos)) {
                    int ii = enc.dequeueInputBuffer(5000);
                    if (ii >= 0) {
                        ByteBuffer b = enc.getInputBuffer(ii);
                        b.clear();
                        long pts = samples * 1_000_000L / pcm.outRate();
                        if (pending != null) {
                            int n = Math.min(b.remaining(), pending.length - pendingOff);
                            n -= n % frame;
                            if (n <= 0) n = Math.min(b.remaining(), pending.length - pendingOff);
                            b.put(pending, pendingOff, n);
                            enc.queueInputBuffer(ii, 0, n, pts, 0);
                            samples += n / frame;
                            pendingOff += n;
                            if (pendingOff >= pending.length) pending = null;
                        } else {
                            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            encInEos = true;
                        }
                    }
                }
                // 4. кодер → M4A
                int oi = enc.dequeueOutputBuffer(ei, 5000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (oi >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                    if (ei.size > 0 && muxStarted && ob != null) {
                        ob.position(ei.offset);
                        ob.limit(ei.offset + ei.size);
                        mux.writeSampleData(muxTrack, ob, ei);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
        } finally {
            ex.release();
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) {} dec.release(); }
            if (enc != null) { try { enc.stop(); } catch (Exception ignored) {} enc.release(); }
            if (mux != null) {
                try { if (muxStarted) mux.stop(); } catch (Exception ignored) {}
                try { mux.release(); } catch (Exception ignored) {}
            }
            if (wav != null) wav.close();
        }
    }

    // ================================================================ відомості про медіа

    static JSONObject mediaInfo(File f) throws Exception {
        JSONObject o = new JSONObject();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            put(o, "duration", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            put(o, "title", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            put(o, "artist", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            put(o, "album", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            put(o, "year", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
            put(o, "bitrate", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            put(o, "width", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            put(o, "height", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            put(o, "rotation", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            put(o, "mime", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            JSONArray tracks = new JSONArray();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                JSONObject t = new JSONObject().put("mime", mf.getString(MediaFormat.KEY_MIME));
                if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) t.put("rate", mf.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) t.put("channels", mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                if (mf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try { t.put("fps", mf.getInteger(MediaFormat.KEY_FRAME_RATE)); } catch (Exception e) { t.put("fps", mf.getFloat(MediaFormat.KEY_FRAME_RATE)); }
                }
                tracks.put(t);
            }
            o.put("tracks", tracks);
        } catch (Exception ignored) {
        } finally {
            ex.release();
        }
        return o;
    }

    static void put(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.isEmpty()) o.put(k, v);
    }

    static JSONObject imageInfo(File f) throws Exception {
        JSONObject o = new JSONObject();
        int[] wh = bounds(f);
        if (wh != null) o.put("width", wh[0]).put("height", wh[1]);
        try {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            put(o, "date", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null ? e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) : e.getAttribute(ExifInterface.TAG_DATETIME));
            String make = e.getAttribute(ExifInterface.TAG_MAKE), model = e.getAttribute(ExifInterface.TAG_MODEL);
            if (model != null) put(o, "camera", make != null && !model.startsWith(make) ? make + " " + model : model);
            put(o, "fnumber", e.getAttribute(ExifInterface.TAG_F_NUMBER));
            put(o, "exposure", e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME));
            put(o, "iso", e.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS));
            put(o, "focal", e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
            float[] ll = new float[2];
            if (e.getLatLong(ll)) o.put("lat", ll[0]).put("lon", ll[1]);
        } catch (Throwable ignored) {}
        return o;
    }

    static JSONObject pdfInfo(File f) throws Exception {
        JSONObject o = new JSONObject();
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer r = new PdfRenderer(fd);
            try {
                o.put("ok", true).put("pages", r.getPageCount());
                if (r.getPageCount() > 0) {
                    try (PdfRenderer.Page p = r.openPage(0)) {
                        o.put("w", p.getWidth()).put("h", p.getHeight());
                    }
                }
            } finally {
                r.close();
            }
        } catch (SecurityException e) {
            o.put("ok", false).put("err", "PDF захищено паролем");
        } catch (Exception e) {
            o.put("ok", false).put("err", "Не вдалося відкрити PDF");
        }
        return o;
    }
}
