# Збирання і тести

[← Документація](../README.md)

## APK без Gradle

`build.sh` збирає підписаний APK однією командою:

1. Завантажує `bundletool` (в ньому `aapt2`, D8 і apksig) і `android.jar` (API 34) у `.tools/`, якщо їх ще немає.
2. `aapt2 compile` + `aapt2 link` — ресурси, маніфест (`minSdk 24`, `targetSdk 34`), інтерфейс з `app/assets`, `R.java`.
3. `javac --release 8` → D8 (`--min-api 24`) → `classes.dex`.
4. Вирівнювання (`app/tools/zipalign.py`) і підпис v2 + v3 (`app/tools/Sign.java` через apksig). Якщо ключа немає — створюється `keystore.p12`.

```bash
./build.sh                                                  # → build/Teka-1.0.apk
KEYSTORE=мій.p12 STORE_PASS=… VERSION_CODE=2 VERSION_NAME=1.1 ./build.sh
TOOLS=~/.cache/teka-tools ./build.sh                        # спільна папка інструментів
```

Потрібні JDK 17+, Python 3, `zip`, `unzip`, `curl`, `git`.

**Обмеження D8 з bundletool:** новий `javac` додає атрибут `MethodParameters` до синтетичних мостових методів, на якому ця версія D8 падає. Тому в коді немає класів, що перевизначають узагальнені методи (`Comparable<T>`, `LruCache<K,V>`…), — замість цього лямбди-компаратори і власний LRU у `Thumbs`.

## Тести на JVM

```bash
./test/run.sh
```

| Тест | Перевірок | Що перевіряє |
|---|---:|---|
| `EncodingsTest` | 60 | Визначення кодувань і BOM, кінці рядків, запис |
| `ArchiveTest` | 74 | Імена, ZIP/TAR/TAR.GZ/GZ, довгі кириличні назви, вибіркове розпакування, конфлікти, скасування, CP866, zip slip, шифрування |
| `PcServerTest` | 20 | Вхід за кодом, папки, Range, вивантаження, ZIP папки, вихід за межі |
| `ConvertTest` | 39 | PDF із JPEG (`pdfinfo`, `qpdf`, `pdftoppm`), BMP, WAV, перетворення PCM, діапазони сторінок |

Для `ConvertTest` потрібні `pdfinfo`, `qpdf`, `pdftoppm` (poppler-utils, qpdf) і `ffprobe` (ffmpeg). Тести запускаються з `LC_ALL=C.UTF-8`, щоб кириличні назви файлів працювали на будь-якій системі.

Android-частину (`MediaCodec`, `PdfRenderer`, `PackageInstaller`, WebView) на JVM не перевірити — вона компілюється разом з APK.

## Інтерфейс у браузері

```bash
./test/ui.sh      # http://localhost:8765/www/index.html
```

Скрипт створює `build/site` з посиланням на `app/assets/www`, генерує демо-картинки, звук і відео (`test/mockdata.py`, потрібні Pillow і ffmpeg) і запускає локальний сервер. Без Android-моста підключається `mock.js` — віртуальна пам’ять із фото, відео, архівами, документами й SD-картою, завдання з прогресом, доступ з ПК, застосунки. На ньому зняті скриншоти в `docs/screenshots` (Chromium, 390×800, масштаб 2).
