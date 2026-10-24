#!/usr/bin/env bash
# Інтерфейс у браузері з імітацією телефона: http://localhost:8765/www/index.html
set -euo pipefail
cd "$(dirname "$0")/.."
SITE=build/site
mkdir -p "$SITE"
ln -sfn "$(pwd)/app/assets/www" "$SITE/www"
[ -f "$SITE/mockdata/photo0.jpg" ] || python3 test/mockdata.py "$SITE/mockdata"
echo "Відкрийте http://localhost:8765/www/index.html (Ctrl+C — зупинити)"
cd "$SITE" && python3 -m http.server 8765
