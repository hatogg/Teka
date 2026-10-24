'use strict';
/* Імітація моста Android для перевірки інтерфейсу у звичайному браузері. На телефоні не завантажується. */

function makeMock() {
  const NOW = Date.now(), DAY = 86400000;
  const ROOT = '/storage/emulated/0', SD = '/storage/1A2B-3C4D';
  const fs = new Map(); // шлях → {name, dir, size, mtime, media}
  const add = (path, o = {}) => {
    const parts = path.split('/');
    for (let i = 3; i < parts.length; i++) {
      const p = parts.slice(0, i).join('/');
      if (!fs.has(p) && p.length > 9) fs.set(p, { dir: true, mtime: NOW - 40 * DAY });
    }
    fs.set(path, Object.assign({ dir: false, size: 1000, mtime: NOW - DAY }, o));
  };
  const dir = (path, ago) => add(path, { dir: true, mtime: NOW - (ago || 20) * DAY });
  [ROOT, SD].forEach(r => fs.set(r, { dir: true, mtime: NOW }));
  ['Alarms', 'Android', 'Android/data', 'Android/media', 'Android/obb', 'Audiobooks', 'Notifications', 'Podcasts', 'Recordings', 'Ringtones'].forEach((d, i) => dir(ROOT + '/' + d, 60 + i));
  for (let i = 0; i < 14; i++) add(ROOT + '/DCIM/Camera/IMG_202610' + (24 - (i >> 1)) + '_' + (183015 - i * 731) + '.jpg', { size: 3.1e6 + i * 211e3, mtime: NOW - i * 0.45 * DAY - 3600e3, media: 'photo' + (i % 7) });
  add(ROOT + '/DCIM/Camera/VID_20261023_174412.mp4', { size: 48.2e6, mtime: NOW - 1.2 * DAY, media: 'video' });
  add(ROOT + '/DCIM/Screenshots/Screenshot_20261024-0912.png', { size: 412e3, mtime: NOW - 0.4 * DAY, media: 'photo3' });
  add(ROOT + '/Download/Звіт_Q3_2026.pdf', { size: 2.4e6, mtime: NOW - 0.2 * DAY, media: 'pdf' });
  add(ROOT + '/Download/Фото з відпустки.zip', { size: 184.6e6, mtime: NOW - 2 * DAY, media: 'zip' });
  add(ROOT + '/Download/backup-сайту.tar.gz', { size: 62.1e6, mtime: NOW - 5 * DAY, media: 'zip' });
  add(ROOT + '/Download/Тека-1.0.apk', { size: 0.31e6, mtime: NOW - 0.1 * DAY, media: 'apk' });
  add(ROOT + '/Download/Договір оренди.docx', { size: 87e3, mtime: NOW - 9 * DAY });
  add(ROOT + '/Download/Бюджет 2026.xlsx', { size: 46e3, mtime: NOW - 12 * DAY });
  add(ROOT + '/Download/qr-код.png', { size: 23e3, mtime: NOW - 3 * DAY, media: 'photo5' });
  add(ROOT + '/Download/Лекція 4 — запис.m4a', { size: 31.7e6, mtime: NOW - 6 * DAY, media: 'audio' });
  add(ROOT + '/Documents/Нотатки.txt', { size: 2318, mtime: NOW - 0.6 * DAY, media: 'text' });
  add(ROOT + '/Documents/Список покупок.md', { size: 412, mtime: NOW - 4 * DAY, media: 'text' });
  add(ROOT + '/Documents/Резюме.pdf', { size: 312e3, mtime: NOW - 30 * DAY, media: 'pdf' });
  add(ROOT + '/Documents/старий_лист.txt', { size: 5120, mtime: NOW - 400 * DAY, media: 'cp1251' });
  dir(ROOT + '/Documents/Скани', 7);
  for (let i = 1; i <= 4; i++) add(ROOT + '/Documents/Скани/Паспорт_стор_' + i + '.jpg', { size: 1.4e6, mtime: NOW - 7 * DAY, media: 'scan' });
  ['Океан Ельзи — Обійми.mp3', 'Go_A — Шум.mp3', 'KALUSH — Стефанія.mp3', 'Jamala — 1944.flac'].forEach((n, i) => add(ROOT + '/Music/' + n, { size: 8.4e6 + i * 1.1e6, mtime: NOW - (20 + i) * DAY, media: 'audio' }));
  add(ROOT + '/Movies/Відео з концерту.mp4', { size: 712e6, mtime: NOW - 15 * DAY, media: 'video' });
  add(ROOT + '/Movies/Мультфільм.mkv', { size: 1.42e9, mtime: NOW - 70 * DAY, media: 'video' });
  dir(ROOT + '/Pictures/Telegram', 3);
  for (let i = 0; i < 5; i++) add(ROOT + '/Pictures/Telegram/IMG_' + (8812 + i) + '.jpg', { size: 180e3 + i * 31e3, mtime: NOW - (3 + i) * DAY, media: 'photo' + ((i + 3) % 7) });
  add(ROOT + '/.thumbnails/.database_uuid', { size: 36 });
  dir(SD + '/Фото 2024', 200);
  for (let i = 0; i < 6; i++) add(SD + '/Фото 2024/DSC_0' + (412 + i) + '.jpg', { size: 6.2e6, mtime: NOW - 300 * DAY, media: 'photo' + i });
  add(SD + '/Фільми/Тіні забутих предків (1965).mkv', { size: 2.1e9, mtime: NOW - 500 * DAY, media: 'video' });

  const trash = [];
  const kv = {};
  let pc = { on: false };
  const list = (p, hidden) => {
    const n = fs.get(p);
    if (!n) return { ok: false, err: 'missing', path: p };
    if (!n.dir) return { ok: false, err: 'notdir', path: p };
    const items = [];
    for (const [k, v] of fs) {
      if (k.startsWith(p + '/') && k.indexOf('/', p.length + 1) < 0) {
        const name = k.slice(p.length + 1);
        if (!hidden && name.startsWith('.')) continue;
        items.push(item(k, v));
      }
    }
    return { ok: true, path: p, items, writable: true };
  };
  const item = (k, v) => {
    const o = { name: k.slice(k.lastIndexOf('/') + 1), path: k, dir: v.dir, mtime: v.mtime };
    if (v.dir) { let n = 0; for (const x of fs.keys()) if (x.startsWith(k + '/') && x.indexOf('/', k.length + 1) < 0) n++; o.n = n; } else o.size = v.size;
    return o;
  };
  const sizeOf = p => { let s = 0, n = 0; for (const [k, v] of fs) if ((k === p || k.startsWith(p + '/')) && !v.dir) { s += v.size; n++; } return [s, n]; };
  const unique = (d, name) => {
    if (!fs.has(d + '/' + name)) return name;
    const i = name.lastIndexOf('.'), b = i > 0 ? name.slice(0, i) : name, e = i > 0 ? name.slice(i) : '';
    for (let n = 1; ; n++) if (!fs.has(d + '/' + b + ' (' + n + ')' + e)) return b + ' (' + n + ')' + e;
  };
  const moveTree = (from, to, copy) => {
    const ents = [...fs].filter(([k]) => k === from || k.startsWith(from + '/'));
    for (const [k, v] of ents) {
      fs.set(to + k.slice(from.length), Object.assign({}, v, copy ? {} : {}));
      if (!copy) fs.delete(k);
    }
  };
  const ext = n => { const i = n.lastIndexOf('.'); return i > 0 ? n.slice(i + 1).toLowerCase() : ''; };
  const KINDS = { images: /^(jpe?g|png|webp|gif|heic)$/, video: /^(mp4|mkv|webm)$/, audio: /^(mp3|m4a|flac|ogg|wav)$/, docs: /^(pdf|docx?|xlsx?|pptx?|txt|md)$/, archives: /^(zip|gz|tgz|tar|7z|rar)$/, apk: /^(apk|apks|xapk)$/ };
  const category = (kind, limit) => {
    const out = [];
    for (const [k, v] of fs) {
      if (v.dir || k.includes('/.')) continue;
      const e = ext(k);
      const okk = kind === 'recent' ? NOW - v.mtime < 30 * DAY : kind === 'large' ? v.size > 50e6 : (KINDS[kind] || /^$/).test(e);
      if (okk) out.push(item(k, v));
    }
    out.sort(kind === 'large' ? (a, b) => b.size - a.size : (a, b) => b.mtime - a.mtime);
    return { ok: true, kind, total: out.length, items: out.slice(0, limit || 200) };
  };
  const TEXTS = {
    text: 'Нотатки — 24 жовтня 2026\n\n• Перенести фото з телефону на ноутбук (Доступ з ПК → браузер)\n• Стиснути скани паспорта в один PDF для банку\n• Конвертувати запис лекції в M4A, щоб займав менше місця\n• Розпакувати «Фото з відпустки.zip» на SD-карту\n\nПаролі від Wi-Fi не зберігати тут!\n\nСписок справ на вихідні:\n1. Прибрати в кошику телефона (Тека → Інструменти → Кошик)\n2. Зробити копію APK улюблених застосунків\n3. Перевірити, що займає місце: Аналіз сховища\n',
    cp1251: 'Шановний пане Іваненко!\r\n\r\nНадсилаю Вам договір і акт виконаних робіт за вересень.\r\nПрошу підписати та повернути один примірник.\r\n\r\nЗ повагою,\r\nОлена\r\n',
  };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const emitTask = (id, d) => setTimeout(() => window.__teka && __teka.onTask(id, d), 0);
  const cancels = {};

  async function runTask(id, kind, p) {
    const steps = { copy: 14, move: 6, delete: 5, trash: 4, zip: 16, tgz: 16, tar: 10, extract: 14, convert: 18, hash: 8, size: 3, search: 6, analyze: 10, stats: 1, apps: 2, trashList: 1, trashClean: 1, restore: 4, purge: 3, apkBackup: 8, renameMany: 2, installBundle: 8 }[kind] || 4;
    const total = kind === 'copy' || kind === 'move' ? (p.srcs || []).reduce((a, s) => a + sizeOf(s)[0], 0) : 1e8;
    for (let i = 1; i <= steps; i++) {
      await sleep(window.__fast ? 5 : 180);
      if (cancels[id]) return emitTask(id, { state: 'cancelled', kind });
      emitTask(id, { state: 'run', kind, done: total * i / steps, total, files: i, filesTotal: steps, cur: (p.srcs && baseName(p.srcs[0])) || '' });
    }
    const res = { state: 'done', kind };
    try {
      switch (kind) {
        case 'copy': case 'move': {
          const paths = [];
          let skipped = 0;
          for (const s of p.srcs) {
            let name = baseName(s);
            if (fs.has(p.dest + '/' + name)) { if (p.conflict === 'skip') { skipped++; continue; } if (p.conflict === 'rename') name = unique(p.dest, name); }
            moveTree(s, p.dest + '/' + name, kind === 'copy');
            paths.push(p.dest + '/' + name);
          }
          Object.assign(res, { paths, skipped, files: paths.length });
          break;
        }
        case 'delete': for (const s of p.paths) moveTree(s, '/dev/null/' + Math.random(), false); res.failed = 0; break;
        case 'trash':
          for (const s of p.paths) { const v = fs.get(s); trash.push({ trash: ROOT + '/.teka_trash', entry: Date.now() + '_' + baseName(s), path: ROOT + '/.teka_trash/' + baseName(s), name: baseName(s), orig: s, time: Date.now(), dir: v.dir, size: v.dir ? -1 : v.size, _tree: [...fs].filter(([k]) => k === s || k.startsWith(s + '/')) }); moveTree(s, '/dev/null/' + Math.random(), false); }
          res.moved = p.paths.length; break;
        case 'trashList': res.items = trash.map(t => Object.assign({}, t, { _tree: undefined })); break;
        case 'restore': case 'purge': {
          const paths = [];
          for (const e of p.entries) {
            const i = trash.findIndex(t => t.entry === e.entry);
            if (i < 0) continue;
            if (kind === 'restore') { for (const [k, v] of trash[i]._tree) fs.set(k, v); paths.push(trash[i].orig); }
            trash.splice(i, 1);
          }
          res.paths = paths; break;
        }
        case 'zip': case 'tgz': case 'tar': {
          const e = { zip: '.zip', tgz: '.tar.gz', tar: '.tar' }[kind];
          const name = unique(p.dest, p.name.endsWith(e) ? p.name : p.name + e);
          const sz = p.srcs.reduce((a, s) => a + sizeOf(s)[0], 0) * 0.62;
          add(p.dest + '/' + name, { size: sz, mtime: Date.now(), media: 'zip' });
          Object.assign(res, { path: p.dest + '/' + name, size: sz }); break;
        }
        case 'extract': {
          const base = baseName(p.archive).replace(/\.(zip|tar\.gz|tgz|tar|gz)$/i, '');
          const d = p.folder ? p.dest + '/' + unique(p.dest, base) : p.dest;
          for (let i = 1; i <= 5; i++) add(d + '/IMG_' + (5000 + i) + '.jpg', { size: 2.9e6, mtime: Date.now(), media: 'photo' + i });
          Object.assign(res, { path: d, count: 5 }); break;
        }
        case 'convert': {
          const paths = [];
          const fmtExt = { image: '.' + (p.format || 'jpg'), img2pdf: '.pdf', txt2pdf: '.pdf', audio: '.' + (p.format || 'm4a'), mute: '.mp4' };
          if (p.mode === 'img2pdf') { const n = unique(p.dest, (p.name || 'Документ') + '.pdf'); add(p.dest + '/' + n, { size: 3.2e6, mtime: Date.now(), media: 'pdf' }); paths.push(p.dest + '/' + n); }
          else for (const s of p.srcs) {
            const b = baseName(s).replace(/\.[^.]+$/, '');
            const n = unique(p.dest, b + (p.mode === 'mute' ? ' (без звуку)' : '') + (fmtExt[p.mode] || '.txt'));
            const src = fs.get(s);
            add(p.dest + '/' + n, { size: (src ? src.size : 1e6) * 0.4, mtime: Date.now(), media: src && src.media });
            paths.push(p.dest + '/' + n);
          }
          Object.assign(res, { paths, errors: [] }); break;
        }
        case 'hash': Object.assign(res, { md5: '5d41402abc4b2a76b9719d911017c592', sha1: 'aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d', sha256: '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' }); break;
        case 'size': { let s = 0, n = 0; for (const x of p.paths) { const [a, b] = sizeOf(x); s += a; n += b; } Object.assign(res, { size: s, fileCount: n, dirCount: 1 }); break; }
        case 'search': {
          const q = p.q.toLowerCase();
          res.items = [...fs].filter(([k, v]) => k.startsWith(p.root + '/') && baseName(k).toLowerCase().includes(q) && (!p.type || (p.type === 'dir' ? v.dir : !v.dir && (KINDS[p.type] || /^$/).test(ext(k))))).map(([k, v]) => item(k, v));
          res.found = res.items.length; break;
        }
        case 'analyze': {
          const children = list(p.root, true).items.map(i => { const [s, n] = i.dir ? sizeOf(i.path) : [i.size, 1]; return { name: i.name, path: i.path, dir: i.dir, size: s, files: n }; });
          const types = {};
          const largest = [];
          for (const [k, v] of fs) if (!v.dir && k.startsWith(p.root + '/')) {
            const e = ext(k);
            const t = Object.keys(KINDS).find(x => KINDS[x].test(e)) || 'other';
            types[t] = types[t] || { size: 0, count: 0 };
            types[t].size += v.size; types[t].count++;
            largest.push({ path: k, name: baseName(k), size: v.size });
          }
          largest.sort((a, b) => b.size - a.size);
          Object.assign(res, { size: children.reduce((a, c) => a + c.size, 0), children, types, largest: largest.slice(0, 50), capacity: 128e9, free: 41.3e9 });
          break;
        }
        case 'stats': { const st = {}; for (const k of Object.keys(KINDS)) { const c = category(k, 1e4); st[k] = { count: c.total, size: c.items.reduce((a, i) => a + i.size, 0) }; } res.stats = st; break; }
        case 'apps': res.apps = [
          ['Monobank', 'com.ftband.mono', '2.41.1', 168e6, 4], ['Дія', 'ua.gov.diia.app', '4.12.0', 121e6, 3], ['Telegram', 'org.telegram.messenger', '11.2.3', 92e6, 0],
          ['Укрзалізниця', 'ua.gov.uz.app', '3.8.0', 54e6, 0], ['Нова пошта', 'ua.novaposhta.app', '6.1.4', 77e6, 2], ['Тека', 'ua.teka.files', '1.0', 0.31e6, 0],
          ['Google Chrome', 'com.android.chrome', '130.0', 248e6, 6, true], ['Камера', 'com.android.camera', '5.2', 31e6, 0, true],
        ].map(([label, pkg, version, size, splits, system], i) => ({ label, pkg, version, code: 100 + i, size, splits, system: !!system, updated: !!system && i === 6, launch: true, installed: NOW - (200 - i * 20) * DAY, modified: NOW - i * 3 * DAY, target: 34 }));
          break;
        case 'apkBackup': { const d = ROOT + '/Download/Тека/APK'; const paths = p.pkgs.map(k => { const n = unique(d, k + '.apk'); add(d + '/' + n, { size: 12e6, mtime: Date.now(), media: 'apk' }); return d + '/' + n; }); Object.assign(res, { paths, dir: d }); break; }
        case 'renameMany': { let n = 0; const paths = []; for (const [from, to] of p.pairs) { const t = dirName(from) + '/' + to; if (!fs.has(t)) { moveTree(from, t, false); n++; paths.push(t); } } Object.assign(res, { renamed: n, paths }); break; }
        case 'trashClean': res.purged = 0; break;
        default: break;
      }
    } catch (e) { Object.assign(res, { state: 'error', err: String(e) }); }
    emitTask(id, res);
  }

  const M = (kind, ...a) => {
    const p = a[0], node = fs.get(p) || {};
    const media = node.media || '';
    if (kind === 'app') return '/mockdata/app_' + (p.split('.').pop()) + '.png';
    if (kind === 'pdf') return '/mockdata/pdfpage' + (a[1] % 3) + '.png';
    if (media.startsWith('photo')) return '/mockdata/' + media + '.jpg';
    if (media === 'scan') return '/mockdata/scan.jpg';
    if (media === 'video') return kind === 'f' ? '/mockdata/clip.mp4' : '/mockdata/frame.jpg';
    if (media === 'audio') return kind === 'f' ? '/mockdata/song.m4a' : '/mockdata/cover.jpg';
    if (media === 'apk') return '/www/icon.png';
    if (media === 'pdf') return '/mockdata/pdfpage0.png';
    return '/mockdata/none';
  };

  return {
    __url: M,
    hello: () => 'mock',
    info: () => JSON.stringify({ version: '1.0', sdk: 34, model: 'Браузер', night: matchMedia('(prefers-color-scheme: dark)').matches, perm: 'full', home: ROOT, downloads: ROOT + '/Download', dcim: ROOT + '/DCIM', pictures: ROOT + '/Pictures', music: ROOT + '/Music', movies: ROOT + '/Movies', documents: ROOT + '/Documents',
      encodings: [['utf-8', 'UTF-8'], ['utf-16le', 'UTF-16 LE'], ['windows-1251', 'Windows-1251 (кирилиця)'], ['koi8-u', 'KOI8-U (кирилиця)'], ['cp866', 'CP866 (DOS, кирилиця)'], ['iso-8859-1', 'ISO-8859-1 (латиниця-1)']] }),
    perm: () => window.__noperm ? 'none' : 'full',
    requestPerm: () => { window.__noperm = false; setTimeout(() => __teka.onPerm('full'), 300); },
    needNotif: () => false,
    requestNotif: () => {},
    volumes: () => JSON.stringify([{ path: ROOT, name: 'Внутрішня пам’ять', primary: true, removable: false, total: 128e9, free: 41.3e9 }, { path: SD, name: 'SD-карта', primary: false, removable: true, total: 64e9, free: 22.8e9 }]),
    list: (t, p, hidden) => JSON.stringify(window.__noperm ? { ok: false, err: 'access', path: p } : list(p, hidden)),
    stat: (t, p) => { const v = fs.get(p); return JSON.stringify(v ? Object.assign(item(p, v), { exists: true, canWrite: true, mime: ext(p) === 'jpg' ? 'image/jpeg' : 'application/octet-stream' }) : { exists: false }); },
    mkdir: (t, d, n) => { if (fs.has(d + '/' + n)) return JSON.stringify({ ok: false, err: '«' + n + '» уже існує' }); fs.set(d + '/' + n, { dir: true, mtime: Date.now() }); return JSON.stringify({ ok: true, path: d + '/' + n }); },
    mkfile: (t, d, n) => { if (fs.has(d + '/' + n)) return JSON.stringify({ ok: false, err: '«' + n + '» уже існує' }); fs.set(d + '/' + n, { dir: false, size: 0, mtime: Date.now(), media: 'text' }); return JSON.stringify({ ok: true, path: d + '/' + n }); },
    rename: (t, p, n) => { const to = dirName(p) + '/' + n; if (fs.has(to)) return JSON.stringify({ ok: false, err: '«' + n + '» уже існує' }); moveTree(p, to, false); return JSON.stringify({ ok: true, path: to }); },
    conflicts: (t, srcs, d) => JSON.stringify(JSON.parse(srcs).filter(s => dirName(s) !== d && fs.has(d + '/' + baseName(s))).map(baseName)),
    exists: (t, p) => String(fs.has(p)),
    call: (t, id, name, json) => {
      const p = JSON.parse(json);
      setTimeout(() => {
        let r = { ok: false, err: 'mock' };
        const node = fs.get(p.path) || {};
        switch (name) {
          case 'category': r = category(p.kind, p.limit); break;
          case 'archiveList': r = { ok: true, charset: 'UTF-8', entries: [
            { name: 'Відпустка 2026/', dir: true, size: 0, csize: 0, mtime: NOW - 30 * DAY },
            ...Array.from({ length: 12 }, (_, i) => ({ name: 'Відпустка 2026/' + (i < 4 ? 'Карпати/' : 'Одеса/') + 'IMG_' + (4100 + i) + '.jpg', dir: false, size: 4.1e6 + i * 1e5, csize: 3.9e6 + i * 1e5, mtime: NOW - 30 * DAY })),
            { name: 'Відпустка 2026/маршрут.txt', dir: false, size: 1200, csize: 610, mtime: NOW - 31 * DAY },
            { name: 'Відпустка 2026/Відео/захід сонця.mp4', dir: false, size: 92e6, csize: 91e6, mtime: NOW - 29 * DAY }] }; break;
          case 'archiveOpen': r = { ok: true, path: ROOT + '/DCIM/Camera/' + [...fs.keys()].find(k => k.includes('/DCIM/Camera/IMG')).split('/').pop() }; break;
          case 'apkInfo': r = { ok: true, pkg: 'ua.teka.files', label: 'Тека', version: '1.0', code: 1, target: 34, min: 24, perms: ['android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.INTERNET', 'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.FOREGROUND_SERVICE'], status: 'new' }; break;
          case 'mediaInfo': r = { ok: true, duration: '214000', bitrate: '320000', title: baseName(p.path), artist: 'Виконавець', tracks: [{ mime: 'audio/mpeg', rate: 44100, channels: 2 }] }; break;
          case 'imageInfo': r = { ok: true, width: 4080, height: 3072, date: '2026:10:23 18:30:15', camera: 'Google Pixel 8', fnumber: '1.68', exposure: 0.004, iso: '64', focal: '6.9/1' }; break;
          case 'pdfInfo': r = { ok: true, pages: 6, w: 595, h: 842 }; break;
          case 'readText': { const tx = TEXTS[node.media] || TEXTS.text; r = { ok: true, text: node.size === 0 ? '' : tx, enc: node.media === 'cp1251' ? 'windows-1251' : 'utf-8', eol: node.media === 'cp1251' ? 'CRLF' : 'LF', bom: false, binary: false, size: node.size || 0, truncated: false }; break; }
          case 'writeText': node.size = p.text.length; node.mtime = Date.now(); r = { ok: true, size: p.text.length, mtime: Date.now() }; break;
          case 'unmappable': r = { ok: true, count: 0 }; break;
          case 'folderSize': { const [s, n] = sizeOf(p.path); r = { ok: true, size: s, files: n }; break; }
        }
        __teka.onResult(id, r);
      }, 120);
    },
    task: (t, id, kind, json) => { runTask(id, kind, JSON.parse(json)); },
    cancel: (t, id) => { cancels[id] = true; },
    open: (t, p) => console.log('open', p),
    share: (t, p) => console.log('share', p),
    install: (t, p) => console.log('install', p),
    appAction: (t, pkg, a) => console.log('app', pkg, a),
    pcStart: () => { pc = { on: true, pin: '482913', port: 8080, ip: '192.168.1.37', url: 'http://192.168.1.37:8080', event: '' }; setTimeout(() => __teka.onPc(pc), 400); },
    pcStop: () => { pc = { on: false, ip: '192.168.1.37' }; setTimeout(() => __teka.onPc(pc), 100); },
    pcStatus: () => JSON.stringify(pc),
    kvPut: (t, k, v) => { kv[k] = v; return true; },
    kvGet: (t, k) => kv[k] || null,
    clip: (t, x) => console.log('clip', x),
    intent: () => '',
    bars: () => {},
    keepScreen: () => {},
    debug: () => {},
    exit: () => {},
  };
}
