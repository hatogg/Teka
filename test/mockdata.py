#!/usr/bin/env python3
"""Демо-картинки, звук і відео для перевірки інтерфейсу в браузері (mock.js бере їх з /mockdata/).
Потрібні Pillow і ffmpeg. Запуск: python3 test/mockdata.py <папка>"""
import math, os, random, subprocess, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = sys.argv[1] if len(sys.argv) > 1 else 'build/site/mockdata'
os.makedirs(out, exist_ok=True)
P = lambda n: os.path.join(out, n)

def font(size, bold=False):
    for f in ['/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf' % ('-Bold' if bold else ''), '/Library/Fonts/Arial.ttf']:
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()

# «світлини»: небо, сонце, пагорби
W, H = 800, 600
PAL = [((255, 170, 90), (120, 60, 140), (40, 30, 60)), ((140, 200, 255), (40, 120, 200), (20, 60, 40)),
       ((255, 220, 150), (250, 120, 80), (60, 40, 50)), ((180, 230, 255), (90, 170, 230), (30, 90, 60)),
       ((255, 200, 200), (160, 100, 180), (50, 40, 70)), ((200, 240, 220), (80, 180, 160), (30, 70, 80)),
       ((250, 240, 200), (230, 170, 90), (90, 60, 40))]
for i, (top, mid, ground) in enumerate(PAL):
    im = Image.new('RGB', (W, H)); d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(top[k] * (1 - t) + mid[k] * t) for k in range(3)))
    d.ellipse([W * .62, H * .18, W * .62 + 90, H * .18 + 90], fill=(255, 245, 220))
    random.seed(i)
    for layer in range(3):
        base = H * (.55 + layer * .12)
        pts = [(0, H)] + [(x, base - random.random() * 80 * (1 - layer * .25) - 30 * math.sin(x / 90 + i)) for x in range(0, W + 40, 40)] + [(W, H)]
        d.polygon(pts, fill=tuple(int(ground[k] + (20 - layer * 10) * (2 - layer)) for k in range(3)))
    im.filter(ImageFilter.GaussianBlur(.6)).save(P('photo%d.jpg' % i), quality=85)

im = Image.open(P('photo2.jpg')).resize((640, 360)); ImageDraw.Draw(im).polygon([(290, 150), (290, 210), (345, 180)], fill='white'); im.save(P('frame.jpg'))
c = Image.new('RGB', (512, 512), (40, 30, 70)); d = ImageDraw.Draw(c)
for r in range(240, 0, -12):
    d.ellipse([256 - r, 256 - r, 256 + r, 256 + r], outline=(200, 120, 255) if r % 24 else (255, 190, 80), width=5)
c.save(P('cover.jpg'))
random.seed(1)
s = Image.new('RGB', (600, 850), (245, 243, 238)); d = ImageDraw.Draw(s); d.rectangle([60, 80, 540, 140], fill=(60, 90, 160))
for y in range(200, 780, 34):
    d.rectangle([60, y, 60 + random.randint(250, 480), y + 12], fill=(150, 150, 150))
s.save(P('scan.jpg'))

# сторінки PDF
for n, title in enumerate(['Звіт за III квартал 2026', 'Розділ 2. Продажі', 'Додаток А']):
    p = Image.new('RGB', (1190, 1684), 'white'); d = ImageDraw.Draw(p)
    d.text((100, 110), title, fill=(20, 20, 20), font=font(26))
    random.seed(n); y = 200
    while y < 1550:
        d.rectangle([100, y, 100 + random.randint(600, 990), y + 14], fill=(170, 170, 170)); y += 34
        if random.random() < .08:
            y += 20; d.rectangle([100, y, 1090, y + 260], fill=(235, 240, 250))
            for b in range(8):
                hh = random.randint(40, 230); d.rectangle([150 + b * 110, y + 250 - hh, 220 + b * 110, y + 250], fill=(70, 120, 220))
            y += 290
    p.save(P('pdfpage%d.png' % n))

# значки застосунків
for k, col in {'mono': (0, 0, 0), 'app': (255, 255, 255), 'messenger': (42, 171, 238), 'files': (28, 27, 25), 'chrome': (255, 255, 255), 'camera': (60, 60, 60)}.items():
    im = Image.new('RGBA', (192, 192), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, 191, 191], radius=48, fill=col + (255,))
    fg = (244, 183, 64) if k == 'files' else ((255, 255, 255) if sum(col) < 400 else (40, 40, 40))
    d.text((70, 58), k[0].upper(), fill=fg, font=font(72, True))
    im.save(P('app_%s.png' % k))

# коротке відео і звук
subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=24', '-f', 'lavfi', '-i', 'sine=frequency=440',
                '-t', '4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', P('clip.mp4')], check=False)
subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=330:duration=6', '-c:a', 'aac', P('song.m4a')], check=False)
print('Готово:', out)
