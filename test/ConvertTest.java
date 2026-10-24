import ua.teka.files.Codecs;
import ua.teka.files.JpegPdf;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.List;

/** Тести платформонезалежної частини конвертера. Запуск: test/run.sh */
public class ConvertTest {
    static int failed = 0, passed = 0;

    static void eq(String name, Object got, Object want) {
        if (want == null ? got == null : want.equals(got)) { passed++; return; }
        failed++;
        System.out.println("FAIL " + name + ": отримано «" + got + "», очікувалось «" + want + "»");
    }

    static byte[] jpeg(int w, int h, Color c, String type) throws Exception {
        BufferedImage im = new BufferedImage(w, h, type.equals("gray") ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w / 4, h / 4);
        g.dispose();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(im, "jpg", b);
        return b.toByteArray();
    }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("tekaconv").toFile();

        // ---------------- JPEG: розміри
        byte[] red = jpeg(800, 600, new Color(200, 30, 30), "rgb");
        byte[] gray = jpeg(300, 900, Color.GRAY, "gray");
        int[] ri = JpegPdf.jpegInfo(red, red.length);
        eq("jpeg size", ri[0] + "x" + ri[1] + "x" + ri[2], "800x600x3");
        int[] gi = JpegPdf.jpegInfo(gray, gray.length);
        eq("jpeg gray", gi[0] + "x" + gi[1] + "x" + gi[2], "300x900x1");
        eq("not jpeg", JpegPdf.jpegInfo(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, 4), null);

        // ---------------- PDF із JPEG
        File pdf = new File(dir, "фото.pdf");
        try (JpegPdf p = new JpegPdf(new FileOutputStream(pdf))) {
            float[] r = JpegPdf.fit(JpegPdf.A4_H, JpegPdf.A4_W, 20, 800, 600);
            p.page(red, red.length, JpegPdf.A4_H, JpegPdf.A4_W, r[0], r[1], r[2], r[3]);
            r = JpegPdf.fit(JpegPdf.A4_W, JpegPdf.A4_H, 0, 300, 900);
            p.page(gray, gray.length, JpegPdf.A4_W, JpegPdf.A4_H, r[0], r[1], r[2], r[3]);
            eq("pdf pages", p.pageCount(), 2);
        }
        String info = run("pdfinfo", pdf.getAbsolutePath());
        eq("pdfinfo pages", info.matches("(?s).*Pages:\\s+2\\s.*"), true);
        eq("pdfinfo producer", info.contains("Teka"), true);
        eq("pdfinfo no syntax error", info.contains("Syntax Error") || info.contains("Error"), false);
        String check = run("qpdf", "--check", pdf.getAbsolutePath());
        eq("qpdf check", check.contains("No syntax or stream encoding errors"), true);
        // зображення вбудовано без перекодування — PDF трохи більший за суму JPEG
        long overhead = pdf.length() - red.length - gray.length;
        eq("pdf overhead small", overhead > 0 && overhead < 2000, true);
        // відмальовуємо першу сторінку і перевіряємо колір у центрі
        run("pdftoppm", "-r", "30", "-f", "1", "-l", "1", "-png", pdf.getAbsolutePath(), new File(dir, "p").getAbsolutePath());
        File[] pngs = dir.listFiles((d, n) -> n.startsWith("p") && n.endsWith(".png"));
        eq("render page", pngs != null && pngs.length == 1, true);
        BufferedImage page = ImageIO.read(pngs[0]);
        Color mid = new Color(page.getRGB(page.getWidth() * 2 / 3, page.getHeight() * 2 / 3));
        eq("page landscape", page.getWidth() > page.getHeight(), true);
        eq("page red", mid.getRed() > 150 && mid.getGreen() < 80 && mid.getBlue() < 80, true);
        Color corner = new Color(page.getRGB(2, 2));
        eq("page margin white", corner.getRed() > 240 && corner.getGreen() > 240, true);

        // ---------------- fit
        float[] f = JpegPdf.fit(100, 200, 10, 50, 50);
        eq("fit", f[0] + "," + f[1] + "," + f[2] + "," + f[3], "10.0,60.0,80.0,80.0");

        // ---------------- BMP
        int w = 5, h = 3; // ширина 5 → рядок 15 байт, вирівнювання до 16
        int[][] img = new int[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img[y][x] = 0xFF000000 | (x * 50 << 16) | (y * 100 << 8) | 77;
        img[0][0] = 0x00123456; // повністю прозорий → білий
        File bmp = new File(dir, "зображення.bmp");
        try (FileOutputStream o = new FileOutputStream(bmp)) {
            Codecs.writeBmp(o, w, h, (y, row) -> System.arraycopy(img[y], 0, row, 0, w));
        }
        eq("bmp size", bmp.length(), 54L + 16 * 3);
        BufferedImage bi = ImageIO.read(bmp);
        eq("bmp dims", bi.getWidth() + "x" + bi.getHeight(), "5x3");
        eq("bmp pixel", Integer.toHexString(bi.getRGB(3, 2) & 0xFFFFFF), Integer.toHexString(img[2][3] & 0xFFFFFF));
        eq("bmp alpha→white", Integer.toHexString(bi.getRGB(0, 0) & 0xFFFFFF), "ffffff");
        eq("bmp ffprobe", run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", bmp.getAbsolutePath()).trim(), "bmp,5,3");

        // ---------------- WAV
        File wav = new File(dir, "звук.wav");
        int rate = 22050;
        try (Codecs.WavOut wo = new Codecs.WavOut(wav, rate, 2)) {
            ByteBuffer bb = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < rate; i++) {
                short s = (short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 12000);
                bb.putShort(s).putShort((short) -s);
            }
            byte[] arr = bb.array();
            wo.write(arr, 0, arr.length / 2);
            wo.write(arr, arr.length / 2, arr.length / 2);
        }
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wav)) {
            AudioFormat af = ais.getFormat();
            eq("wav fmt", (int) af.getSampleRate() + "/" + af.getChannels() + "/" + af.getSampleSizeInBits(), "22050/2/16");
            eq("wav frames", ais.getFrameLength(), (long) rate);
        }
        String probe = run("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", wav.getAbsolutePath()).trim();
        eq("wav duration", probe.startsWith("1.0"), true);

        // ---------------- PCM
        // float стерео 48 кГц → 16 біт без змін частоти
        Codecs.Pcm pf = new Codecs.Pcm(Codecs.PCM_FLOAT, 2, 48000, 2, 48000);
        ByteBuffer fb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        fb.putFloat(1.0f).putFloat(-1.0f).putFloat(0.5f).putFloat(2.0f);
        byte[] o16 = pf.convert(fb.array(), 0, 16);
        ByteBuffer ob = ByteBuffer.wrap(o16).order(ByteOrder.LITTLE_ENDIAN);
        eq("float→16", ob.getShort() + "," + ob.getShort() + "," + ob.getShort() + "," + ob.getShort(), "32767,-32767,16384,32767");
        // 6 каналів → 2 (перші два), 96 кГц → 48 кГц (середнє двох кадрів)
        Codecs.Pcm p6 = new Codecs.Pcm(Codecs.PCM_16, 6, 96000, 2, 48000);
        eq("decimate rate", p6.outRate(), 48000);
        eq("downmix ch", p6.outChannels(), 2);
        ByteBuffer in6 = ByteBuffer.allocate(4 * 12).order(ByteOrder.LITTLE_ENDIAN);
        short[][] fr = {{100, 200, 9, 9, 9, 9}, {300, 400, 9, 9, 9, 9}, {-100, -200, 9, 9, 9, 9}, {-300, -400, 9, 9, 9, 9}};
        for (short[] x : fr) for (short s : x) in6.putShort(s);
        byte[] all = in6.array();
        // подаємо шматками, що розрізають кадр навпіл
        byte[] a1 = p6.convert(all, 0, 17);
        byte[] a2 = p6.convert(all, 17, all.length - 17);
        ByteBuffer r6 = ByteBuffer.allocate(a1.length + a2.length).order(ByteOrder.LITTLE_ENDIAN);
        r6.put(a1).put(a2).flip();
        eq("decimate out", r6.remaining(), 8);
        eq("decimate values", r6.getShort() + "," + r6.getShort() + "," + r6.getShort() + "," + r6.getShort(), "200,300,-200,-300");
        // 8 біт → 16
        Codecs.Pcm p8 = new Codecs.Pcm(Codecs.PCM_8, 1, 8000, 2, 48000);
        ByteBuffer r8 = ByteBuffer.wrap(p8.convert(new byte[]{(byte) 128, (byte) 255, 0}, 0, 3)).order(ByteOrder.LITTLE_ENDIAN);
        eq("8→16", r8.getShort() + "," + r8.getShort() + "," + r8.getShort(), "0,32512,-32768");
        // 24 біт
        Codecs.Pcm p24 = new Codecs.Pcm(Codecs.PCM_24, 1, 44100, 2, 48000);
        ByteBuffer r24 = ByteBuffer.wrap(p24.convert(new byte[]{0x00, 0x34, 0x12, 0x00, 0x00, (byte) 0x80}, 0, 6)).order(ByteOrder.LITTLE_ENDIAN);
        eq("24→16", r24.getShort() + "," + r24.getShort(), "4660,-32768");
        // 192 кГц → 48 кГц
        eq("192k", new Codecs.Pcm(Codecs.PCM_16, 2, 192000, 2, 48000).outRate(), 48000);
        eq("88.2k", new Codecs.Pcm(Codecs.PCM_16, 2, 88200, 2, 48000).outRate(), 44100);
        eq("passthrough", new Codecs.Pcm(Codecs.PCM_16, 2, 44100, 2, 48000).passthrough(), true);

        // ---------------- діапазони сторінок
        eq("pages all", Codecs.pages("", 3).toString(), "[0, 1, 2]");
        eq("pages spec", Codecs.pages("1-2, 5, 7-", 8).toString(), "[0, 1, 4, 6, 7]");
        eq("pages dup", Codecs.pages("2,2,1–2", 3).toString(), "[0, 1]");
        eq("pages bad", Codecs.pages("0-2", 3), null);
        eq("pages over", Codecs.pages("4", 3), null);
        eq("pages text", Codecs.pages("abc", 3), null);
        List<Integer> open = Codecs.pages("-2", 5);
        eq("pages open start", open.toString(), "[0, 1]");

        System.out.println((failed == 0 ? "OK" : "ПОМИЛКИ") + ": пройдено " + passed + ", не пройдено " + failed);
        if (failed > 0) System.exit(1);
    }
}
