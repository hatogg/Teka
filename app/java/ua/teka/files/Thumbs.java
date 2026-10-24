package ua.teka.files;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Мініатюри: світлини, відео, обкладинки музики, значки APK, перша сторінка PDF. Кеш у пам’яті й на диску. */
final class Thumbs {
    private Thumbs() {}

    /** Кеш у пам’яті за давністю використання, обмежений сумарним розміром. */
    static final class Mem {
        final java.util.LinkedHashMap<String, byte[]> map = new java.util.LinkedHashMap<>(64, 0.75f, true);
        final long limit;
        long size;
        Mem(long limit) { this.limit = limit; }
        synchronized byte[] get(String k) { return map.get(k); }
        synchronized void put(String k, byte[] v) {
            byte[] old = map.put(k, v);
            if (old != null) size -= old.length;
            size += v.length;
            java.util.Iterator<java.util.Map.Entry<String, byte[]>> it = map.entrySet().iterator();
            while (size > limit && it.hasNext()) {
                java.util.Map.Entry<String, byte[]> e = it.next();
                if (e.getKey().equals(k)) continue;
                size -= e.getValue().length;
                it.remove();
            }
        }
    }

    static final Mem mem = new Mem(16L << 20);
    static final Set<String> failed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static final Semaphore gate = new Semaphore(3);

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "thumbs");
        d.mkdirs();
        return d;
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Байти JPEG або PNG; перший байт 0x89 означає PNG. null — мініатюри немає. */
    static byte[] get(Context c, File f, int size) {
        if (!f.isFile()) return null;
        size = Math.max(48, Math.min(1024, size));
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length() + "|" + size;
        byte[] b = mem.get(key);
        if (b != null) return b;
        if (failed.contains(key)) return null;
        File disk = new File(dir(c), sha1(key));
        if (disk.isFile()) {
            b = read(disk);
            if (b != null && b.length > 0) { mem.put(key, b); return b; }
        }
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            return null;
        }
        try {
            b = mem.get(key);
            if (b != null) return b;
            Bitmap bm = make(c, f, size);
            if (bm == null) { failed.add(key); return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean png = bm.hasAlpha();
            bm.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 82, out);
            bm.recycle();
            b = out.toByteArray();
            mem.put(key, b);
            try (FileOutputStream o = new FileOutputStream(disk)) { o.write(b); } catch (Exception ignored) {}
            return b;
        } catch (Throwable e) {
            failed.add(key);
            return null;
        } finally {
            gate.release();
        }
    }

    static byte[] read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return off == b.length ? b : Arrays.copyOf(b, off);
        } catch (Exception e) {
            return null;
        }
    }

    /** Масштаб так, щоб коротша сторона дорівнювала size (для обрізання «за заповненням» у сітці). */
    static Bitmap cover(Bitmap b, int size) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        float s = (float) size / Math.min(w, h);
        if (s >= 1f) return b;
        int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
        // дуже видовжені — обмежуємо довшу сторону
        if (nw > size * 3) nw = size * 3;
        if (nh > size * 3) nh = size * 3;
        Bitmap r = Bitmap.createScaledBitmap(b, nw, nh, true);
        if (r != b) b.recycle();
        return r;
    }

    static Bitmap make(Context c, File f, int size) throws Exception {
        String cat = Fs.category(f.getName());
        String ext = Names.ext(f.getName());
        switch (cat) {
            case "images": return image(f, size);
            case "video": return video(f, size);
            case "audio": return audio(f, size);
            case "apk": return "apk".equals(ext) ? apk(c, f, size) : null;
            default:
                if ("pdf".equals(ext)) return pdf(f, size);
                return null;
        }
    }

    static Bitmap image(File f, int size) throws Exception {
        if ("svg".equals(Names.ext(f.getName()))) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        Bitmap b = null;
        if (o.outWidth > 0) {
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (b != null) b = Convert.orient(b, Convert.exifOrientation(f));
        }
        if (b == null && Build.VERSION.SDK_INT >= 28) b = Convert.decodeModern(f, size * 2);
        return cover(b, size);
    }

    static Bitmap video(File f, int size) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return cover(ThumbnailUtils.createVideoThumbnail(f, new Size(size * 2, size * 2), null), size);
            } catch (Exception ignored) {}
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) b = r.getFrameAtTime();
            return cover(b, size);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static Bitmap audio(File f, int size) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            byte[] art = r.getEmbeddedPicture();
            if (art == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, o);
            int sample = 1;
            while (o.outWidth > 0 && Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return cover(BitmapFactory.decodeByteArray(art, 0, art.length, d), size);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static Bitmap apk(Context c, File f, int size) {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null || pi.applicationInfo == null) return null;
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = f.getAbsolutePath();
        ai.publicSourceDir = f.getAbsolutePath();
        return drawable(ai.loadIcon(pm), size);
    }

    static Bitmap drawable(Drawable d, int size) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            return Bitmap.createScaledBitmap(b, size, size, true);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(cv);
        return b;
    }

    static Bitmap pdf(File f, int size) throws Exception {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) {
            PdfRenderer r = new PdfRenderer(fd);
            try {
                if (r.getPageCount() == 0) return null;
                try (PdfRenderer.Page p = r.openPage(0)) {
                    float s = (float) size * 1.5f / Math.max(p.getWidth(), p.getHeight());
                    int w = Math.max(1, (int) (p.getWidth() * s)), h = Math.max(1, (int) (p.getHeight() * s));
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE);
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    // без прозорості — збережемо як JPEG
                    Bitmap o = b.copy(Bitmap.Config.RGB_565, false);
                    b.recycle();
                    return o;
                }
            } finally {
                r.close();
            }
        }
    }

    static byte[] appIcon(Context c, String pkg, int size) {
        size = Math.max(48, Math.min(256, size));
        String key = "app|" + pkg + "|" + size;
        byte[] b = mem.get(key);
        if (b != null) return b;
        try {
            Bitmap bm = drawable(c.getPackageManager().getApplicationIcon(pkg), size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            b = out.toByteArray();
            mem.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ сторінки PDF для перегляду

    static final Object pdfLock = new Object();
    static String pdfKey;
    static ParcelFileDescriptor pdfFd;
    static PdfRenderer pdfR;

    static void closePdf() {
        synchronized (pdfLock) {
            try { if (pdfR != null) pdfR.close(); } catch (Exception ignored) {}
            try { if (pdfFd != null) pdfFd.close(); } catch (Exception ignored) {}
            pdfR = null;
            pdfFd = null;
            pdfKey = null;
        }
    }

    /** Сторінка n (від 0) шириною width точок, JPEG. */
    static byte[] pdfPage(File f, int n, int width) throws Exception {
        width = Math.max(200, Math.min(2400, width));
        synchronized (pdfLock) {
            String key = f.getAbsolutePath() + "|" + f.lastModified();
            if (!key.equals(pdfKey)) {
                closePdf();
                pdfFd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfR = new PdfRenderer(pdfFd);
                pdfKey = key;
            }
            if (n < 0 || n >= pdfR.getPageCount()) return null;
            try (PdfRenderer.Page p = pdfR.openPage(n)) {
                float s = (float) width / p.getWidth();
                int w = width, h = Math.max(1, (int) (p.getHeight() * s));
                if ((long) w * h > 12_000_000L) {
                    float k = (float) Math.sqrt(12_000_000.0 / ((long) w * h));
                    w = (int) (w * k);
                    h = (int) (h * k);
                }
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 88, out);
                b.recycle();
                return out.toByteArray();
            }
        }
    }

    /** Прибирає дисковий кеш мініатюр, якщо він більший за 80 МБ (спершу найстаріші). */
    static void trim(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total < (80L << 20)) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : fs) {
            if (total < (50L << 20)) break;
            total -= f.length();
            f.delete();
        }
    }
}
