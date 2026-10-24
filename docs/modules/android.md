# Android-частина

[← Документація](../README.md)

Файли: `MainActivity.java` (~840 рядків), `Server.java`, `FilesProvider.java`, `Thumbs.java`, `AndroidManifest.xml`.

## MainActivity

Єдиний екран: `FrameLayout` з WebView, що завантажує `https://appassets.androidplatform.net/www/index.html`. Усі слухачі — статичні вкладені класи або лямбди (так D8 із bundletool збирає код без сюрпризів).

- **Тема й системні панелі** — колір рядка стану й навігації задає сторінка через `bars()`; зміна теми системи передається в `__teka.config(night)`.
- **Назад** — `onBackPressed` питає сторінку `__teka.back()`: закрити шар, зняти вибір, піднятися на папку вище; лише `"exit"` закриває застосунок. Повноекранне відео (`onShowCustomView`) закривається першим.
- **Дозвіл на файли** — `perm()`: на Android 11+ `Environment.isExternalStorageManager()`, раніше — `WRITE_EXTERNAL_STORAGE`. `requestPerm()` відкриває системний екран «Доступ до всіх файлів» для пакета. Після повернення `onResume` повідомляє сторінку (`__teka.resume(perm)`), і вона перечитує сховища.
- **«Відкрити за допомогою»** — для ZIP, TAR, GZ, JAR. `content://` перетворюється на шлях через стовпець `_data`, а якщо його немає — копіюється в `cache/incoming`. Сповіщення доступу з ПК відкриває екран доступу (`ua.teka.files.PC`).
- **Після падіння WebView** (`onRenderProcessGone`) екран перестворюється.

### JS-міст `window.Android`

WebView вбудовує об’єкт у всі фрейми, тому кожен виклик перевіряє маркер, який головна сторінка отримує один раз через `hello()`.

| Група | Методи |
|---|---|
| Сторінка | `hello`, `info` (версія, SDK, тема, стандартні папки, кодування), `intent`, `bars`, `keepScreen`, `debug`, `exit` |
| Дозволи | `perm`, `requestPerm`, `needNotif`, `requestNotif` |
| Швидкі файлові | `volumes`, `list`, `stat`, `mkdir`, `mkfile`, `rename`, `conflicts`, `exists` |
| Завдання | `task(id, kind, json)`, `cancel(id)` → події `__teka.onTask(id, …)` |
| Запити у фоні | `call(id, name, json)` → `__teka.onResult(id, …)`: `category`, `archiveList`, `archiveOpen`, `apkInfo`, `mediaInfo`, `imageInfo`, `pdfInfo`, `readText`, `writeText`, `unmappable`, `folderSize` |
| Інші застосунки | `open`, `share`, `install`, `appAction` (`launch`, `uninstall`, `details`, `market`) |
| Доступ з ПК | `pcStart`, `pcStop`, `pcStatus` → події `__teka.onPc` |
| Різне | `kvPut`, `kvGet` (налаштування), `clip`, `hashText` |

`writeText` пише через тимчасовий файл і перейменування, щоб збій не залишив половину файлу.

## Server — віртуальний сайт

`shouldInterceptRequest` віддає все з домену `appassets.androidplatform.net`, нічого не йде в мережу:

| Адреса | Відповідь |
|---|---|
| `/www/…` | Інтерфейс з `assets/www` |
| `/f?p=шлях[&dl=1]` | Файл; `Range` → `206 Partial Content` (перемотування відео й музики), `416` для неправильного діапазону |
| `/t?p=шлях&s=розмір` | Мініатюра JPEG/PNG або 404 |
| `/pdf?p=шлях&n=сторінка&w=ширина` | Сторінка PDF у JPEG |
| `/app?pkg=пакет&s=розмір` | Значок застосунку |

## Thumbs — мініатюри

- Фото — `BitmapFactory` з `inSampleSize` і поворотом за EXIF; HEIC/AVIF на Android 9+ — `ImageDecoder`.
- Відео — `ThumbnailUtils.createVideoThumbnail` (Android 10+) або кадр на 1-й секунді через `MediaMetadataRetriever`.
- Музика — вбудована обкладинка. APK — значок з `getPackageArchiveInfo`. PDF — перша сторінка `PdfRenderer`.
- Не більше 3 мініатюр одночасно (семафор). Кеш у пам’яті 16 МБ (LRU за розміром) і на диску (`cache/thumbs`, ключ — SHA-1 від шляху, часу зміни, розміру й розміру мініатюри). Під час запуску дисковий кеш понад 80 МБ зменшується до 50 МБ. Файли без мініатюри запам’ятовуються, щоб не пробувати знову.
- Сторінки PDF для переглядача рендеряться одним відкритим `PdfRenderer` під замком; зображення до 12 Мп.

## FilesProvider

Видає файли іншим застосункам як `content://ua.teka.files.provider/f/<шлях>` для «Відкрити в…», «Поділитися» і встановлення APK. Постачальник не експортується (`exported=false`): доступ отримують лише застосунки, яким «Тека» дала дозвіл на конкретну адресу. Шляхи поза сховищами й кешем застосунку відхиляються (перевірка канонічного шляху).

## Маніфест

- Дозволи: `MANAGE_EXTERNAL_STORAGE` (+ `READ/WRITE_EXTERNAL_STORAGE` для старих Android, `requestLegacyExternalStorage` для Android 10), `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, мережа й Wi-Fi, `WAKE_LOCK`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`.
- `ServerService` — `foregroundServiceType="specialUse"` з поясненням у `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- `Apps$InstallReceiver` — відповіді `PackageInstaller` під час встановлення наборів APK.
- `allowBackup=false`, `largeHeap=true` (великі зображення в конвертері).
