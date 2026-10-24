#!/usr/bin/env bash
# Тести чистої Java-частини на звичайній JVM: архіватор, імена, кодування, HTTP-доступ з ПК,
# PDF із JPEG, BMP, WAV, перетворення звуку. Для ConvertTest потрібні pdfinfo, qpdf, pdftoppm, ffprobe.
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8  # кириличні назви файлів у тестах
cd "$(dirname "$0")/.."
OUT=build/test
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=app/java/ua/teka/files
javac -nowarn -encoding UTF-8 -d "$OUT" $SRC/Encodings.java $SRC/Names.java $SRC/Archive.java \
  $SRC/PcServer.java $SRC/JpegPdf.java $SRC/Codecs.java test/*.java
for t in EncodingsTest ArchiveTest PcServerTest ConvertTest; do
  echo "== $t"
  java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" "$t"
done
