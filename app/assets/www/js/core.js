'use strict';
/* Спільне: налаштування, типи файлів, формати, рядки списку, панель завдань. */

// ------------------------------------------------------------ налаштування

const Settings = {
  d: {
    theme: 'auto', hidden: false, foldersFirst: true, sortBy: 'name', sortDesc: false, view: 'list',
    thumbs: true, useTrash: true, trashDays: 30, pcPort: 8080, pcPin: '', bookmarks: [], lastPath: '',
    viewByPath: {}, zipLevel: 6,
  },
  load() {
    const s = Native.kvGet('settings', {}) || {};
    Settings.v = Object.assign({}, Settings.d, s);
  },
  get: k => Settings.v[k],
  set(k, val) {
    Settings.v[k] = val;
    Native.kvPut('settings', Settings.v);
  },
};

// ------------------------------------------------------------ формати

function fmtSize(n) {
  if (n == null || n < 0) return '';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  const s = i === 0 ? String(v) : v < 10 ? v.toFixed(1) : String(Math.round(v));
  return s.replace('.', ',') + ' ' + u[i];
}

const MONTHS = ['січ', 'лют', 'бер', 'квіт', 'трав', 'черв', 'лип', 'серп', 'вер', 'жовт', 'лист', 'груд'];
const pad2 = n => (n < 10 ? '0' : '') + n;

function fmtDate(t, long) {
  if (!t) return '';
  const d = new Date(t), now = new Date();
  const hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  const sameDay = d.toDateString() === now.toDateString();
  const y = new Date(now); y.setDate(now.getDate() - 1);
  if (sameDay) return (long ? 'сьогодні, ' : '') + hm;
  if (d.toDateString() === y.toDateString()) return 'учора' + (long ? ', ' + hm : '');
  const base = d.getDate() + ' ' + MONTHS[d.getMonth()] + '.';
  if (d.getFullYear() !== now.getFullYear()) return base + ' ' + d.getFullYear() + (long ? ', ' + hm : '');
  return base + (long ? ', ' + hm : '');
}

function fmtDur(ms) {
  ms = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(ms / 3600), m = Math.floor(ms % 3600 / 60), s = ms % 60;
  return (h ? h + ':' + pad2(m) : m) + ':' + pad2(s);
}

function plural(n, one, few, many) {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b === 1) return one;
  if (b >= 2 && b <= 4) return few;
  return many;
}
const nItems = n => n + ' ' + plural(n, 'об’єкт', 'об’єкти', 'об’єктів');
const nFiles = n => n + ' ' + plural(n, 'файл', 'файли', 'файлів');

const baseName = p => { const s = String(p).replace(/\/+$/, ''); return s.slice(s.lastIndexOf('/') + 1); };
const dirName = p => { const s = String(p).replace(/\/+$/, ''); const i = s.lastIndexOf('/'); return i <= 0 ? '/' : s.slice(0, i); };
const joinPath = (a, b) => a.replace(/\/+$/, '') + '/' + b;

/** «назва.tar.gz» → ['назва', '.tar.gz']; «.bashrc» → ['.bashrc', '']. */
function splitName(name) {
  const lower = name.toLowerCase();
  for (const d of ['.tar.gz', '.tar.bz2', '.tar.xz']) {
    if (lower.endsWith(d) && name.length > d.length) return [name.slice(0, -d.length), name.slice(-d.length)];
  }
  const i = name.lastIndexOf('.');
  if (i <= 0) return [name, ''];
  return [name.slice(0, i), name.slice(i)];
}
const extOf = name => { const e = splitName(name)[1]; return e ? e.slice(1).toLowerCase() : ''; };

// ------------------------------------------------------------ типи файлів

const Kind = {
  map: {},
  init() {
    const add = (kind, list) => list.split(' ').forEach(e => { Kind.map[e] = kind; });
    add('image', 'jpg jpeg png gif webp heic heif bmp avif svg tif tiff dng ico');
    add('video', 'mp4 mkv webm avi mov 3gp m4v ts wmv flv mpg mpeg');
    add('audio', 'mp3 m4a aac ogg oga opus flac wav amr wma mid midi');
    add('pdf', 'pdf');
    add('doc', 'doc docx odt rtf xls xlsx ods csv ppt pptx odp epub fb2 djvu');
    add('text', 'txt md log ini cfg conf json xml html htm css js mjs ts tsx jsx java kt kts py c h cpp hpp cs go rs rb php sh bat ps1 yml yaml toml sql srt vtt properties gradle gitignore env lua pl swift dart');
    add('archive', 'zip rar 7z tar gz tgz bz2 xz jar apks xapk apkm');
    add('apk', 'apk');
  },
  of(item) {
    if (item.dir) return 'dir';
    const lower = item.name.toLowerCase();
    if (/\.tar\.(gz|bz2|xz)$/.test(lower)) return 'archive';
    return Kind.map[extOf(item.name)] || 'other';
  },
  icon: { dir: 'folder', image: 'image', video: 'video', audio: 'music', pdf: 'doc', doc: 'doc', text: 'type', archive: 'archive', apk: 'package', other: 'file' },
  label: { dir: 'Папка', image: 'Зображення', video: 'Відео', audio: 'Аудіо', pdf: 'PDF', doc: 'Документ', text: 'Текст', archive: 'Архів', apk: 'Застосунок', other: 'Файл' },
  thumb(item) {
    if (!Settings.get('thumbs')) return false;
    const k = Kind.of(item), e = extOf(item.name);
    if (k === 'image') return e !== 'svg' && e !== 'ico';
    return k === 'video' || k === 'apk' || k === 'pdf' || (k === 'audio' && /^(mp3|m4a|flac|ogg|opus)$/.test(e));
  },
  /** Чи можна відкрити як архів у «Теці». */
  archive(item) {
    const lower = item.name.toLowerCase();
    if (/\.(tar\.gz|tgz|tar|gz)$/.test(lower)) return true;
    return /\.(zip|jar|apks|xapk|apkm|aar|docx|xlsx|pptx|odt|ods|odp|epub|cbz|xpi|war|kmz|3mf)$/.test(lower);
  },
};
Kind.init();

const FOLDER_SVG = '<svg class="fold" viewBox="0 0 48 48" aria-hidden="true"><path fill="var(--k-dir)" opacity=".55" d="M7 9h12.5l4 4H41a3 3 0 0 1 3 3v21a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3V12a3 3 0 0 1 3-3z"/><path fill="var(--k-dir)" d="M4 18.5a2.5 2.5 0 0 1 2.5-2.5h35a2.5 2.5 0 0 1 2.5 2.5V37a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3z"/></svg>';

/** Значок або мініатюра файлу. */
function fileIcon(item, size, noThumb) {
  const k = Kind.of(item);
  if (k === 'dir') return h('div', { class: 'ric dir', html: FOLDER_SVG });
  const box = h('div', { class: 'ric', style: { '--c': 'var(--k-' + k + ')' } });
  const ext = extOf(item.name);
  const badge = (k === 'doc' || k === 'text' || k === 'archive' || k === 'other') && ext && ext.length <= 4;
  box.innerHTML = ic(Kind.icon[k]);
  if (badge) {
    box.classList.add('has-badge');
    box.appendChild(h('span', { class: 'badge', text: ext }));
  }
  if (!noThumb && Kind.thumb(item)) {
    const img = h('img', { loading: 'lazy', decoding: 'async', alt: '' });
    img.onload = () => img.classList.add('ok');
    img.onerror = () => img.remove();
    img.src = Native.thumbUrl(item.path, size || 160, item.mtime);
    box.appendChild(img);
    if (k === 'video') box.appendChild(h('span', { class: 'play', html: ic('play') }));
  }
  return box;
}

function metaOf(item) {
  if (item.dir) return (item.n >= 0 ? nItems(item.n) : 'Немає доступу') + (item.mtime ? ' · ' + fmtDate(item.mtime) : '');
  return fmtSize(item.size) + (item.mtime ? ' · ' + fmtDate(item.mtime) : '');
}

// ------------------------------------------------------------ рядок списку з довгим натиском

/**
 * opts: {onOpen(item), onSelect(item) — довгий натиск або вибір, onMore(item), selected(item) → bool,
 *        meta(item) → рядок, size — мініатюри}
 */
function fileRow(item, opts) {
  const row = h('div', { class: 'row', 'data-path': item.path });
  row.appendChild(fileIcon(item, opts.size, opts.noThumb));
  row.appendChild(h('div', { class: 'rtext' },
    h('div', { class: 'rname', text: item.name }),
    h('div', { class: 'rmeta', text: opts.meta ? opts.meta(item) : metaOf(item) })));
  if (opts.onMore) {
    row.appendChild(h('button', { class: 'rmore', 'aria-label': 'Дії', html: ic('dots'), onclick: e => { e.stopPropagation(); opts.onMore(item, row); } }));
  }
  if (opts.onSelect) row.appendChild(h('span', { class: 'rcheck', html: ic(opts.selected && opts.selected(item) ? 'selectAll' : 'box') }));
  if (opts.selected && opts.selected(item)) row.classList.add('on');
  longPress(row, () => opts.onSelect && opts.onSelect(item, row), () => opts.onOpen && opts.onOpen(item, row));
  return row;
}

function setRowSelected(row, on) {
  row.classList.toggle('on', on);
  const c = row.querySelector('.rcheck');
  if (c) c.innerHTML = ic(on ? 'selectAll' : 'box');
}

/** Довгий натиск (контекстне меню на дотик) і звичайне натискання без конфлікту між ними. */
function longPress(el, onLong, onTap) {
  let lastLong = 0, timer = 0, sx = 0, sy = 0, fired = false;
  el.addEventListener('contextmenu', e => {
    e.preventDefault();
    if (fired) return;
    lastLong = Date.now();
    onLong();
  });
  el.addEventListener('touchstart', e => {
    if (e.touches.length !== 1) return;
    fired = false;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY;
    clearTimeout(timer);
    timer = setTimeout(() => {
      fired = true;
      lastLong = Date.now();
      if (navigator.vibrate) try { navigator.vibrate(12); } catch (x) {}
      onLong();
    }, 480);
  }, { passive: true });
  el.addEventListener('touchmove', e => {
    const t = e.touches[0];
    if (Math.abs(t.clientX - sx) > 10 || Math.abs(t.clientY - sy) > 10) clearTimeout(timer);
  }, { passive: true });
  el.addEventListener('touchend', e => {
    clearTimeout(timer);
    if (fired) { e.preventDefault(); setTimeout(() => { fired = false; }, 50); }
  });
  el.addEventListener('touchcancel', () => clearTimeout(timer));
  el.addEventListener('click', e => {
    if (Date.now() - lastLong < 650) return;
    onTap(e);
  });
}

/** Поступове домальовування довгих списків. */
function renderChunked(box, items, make, step = 120) {
  let i = 0;
  const more = h('div', { class: 'more' });
  const io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) next(); }, { root: box.closest('.scroll, .pbody, .sbody'), rootMargin: '600px' });
  function next() {
    const frag = document.createDocumentFragment();
    const end = Math.min(items.length, i + step);
    for (; i < end; i++) {
      const el = make(items[i], i);
      if (el) frag.appendChild(el);
    }
    box.insertBefore(frag, more);
    if (i >= items.length) { io.disconnect(); more.remove(); }
  }
  box.appendChild(more);
  next();
  if (i < items.length) io.observe(more);
  return { stop: () => io.disconnect() };
}

// ------------------------------------------------------------ панель завдань

const Tasks = {
  titles: {
    copy: 'Копіювання', move: 'Переміщення', delete: 'Видалення', trash: 'У кошик', restore: 'Відновлення',
    purge: 'Очищення кошика', zip: 'Стиснення в ZIP', tar: 'Пакування в TAR', tgz: 'Стиснення в TAR.GZ',
    extract: 'Розпакування', convert: 'Конвертація', hash: 'Контрольні суми', size: 'Підрахунок розміру',
    apkBackup: 'Копія APK', installBundle: 'Встановлення набору APK', renameMany: 'Перейменування',
  },
  icons: {
    copy: 'copy', move: 'move', delete: 'trash', trash: 'trash', restore: 'restore', purge: 'sweep', zip: 'archive',
    tar: 'archive', tgz: 'archive', extract: 'unpack', convert: 'transform', apkBackup: 'package', installBundle: 'package', renameMany: 'pencil',
  },

  /** Запускає завдання з карткою прогресу. Повертає Promise з підсумком. */
  run(kind, args, opts = {}) {
    const t = Native.task(kind, args, d => update(d));
    const bar = h('i', { style: { width: '0%' } });
    const barBox = h('div', { class: 'tbar ind' }, bar);
    const sub = h('div', { class: 'ts', text: 'Підготовка…' });
    const card = h('div', { class: 'tcard' },
      h('div', { class: 'tic', html: ic(Tasks.icons[kind] || 'clock') }),
      h('div', { class: 'tbody' }, h('div', { class: 'tt', text: opts.title || Tasks.titles[kind] || 'Завдання' }), sub, barBox),
      h('button', { class: 'ib', 'aria-label': 'Скасувати', html: ic('x'), onclick: () => { t.cancel(); sub.textContent = 'Скасування…'; } }));
    const shown = setTimeout(() => $('#dock').appendChild(card), opts.quiet ? 700 : 0);
    function update(d) {
      const pct = d.total > 0 ? Math.min(100, Math.round(d.done * 100 / d.total)) : -1;
      barBox.classList.toggle('ind', pct < 0);
      if (pct >= 0) bar.style.width = pct + '%';
      const parts = [];
      if (d.filesTotal > 1) parts.push(Math.min(d.files + 1, d.filesTotal) + ' з ' + d.filesTotal);
      else if (d.files > 1 && !d.filesTotal) parts.push(nFiles(d.files));
      if (d.total > 0 && kind !== 'convert' && kind !== 'trash' && kind !== 'restore' && kind !== 'renameMany') parts.push(fmtSize(d.done) + ' / ' + fmtSize(d.total));
      if (pct >= 0) parts.push(pct + '%');
      if (d.cur) parts.push(d.cur);
      sub.textContent = parts.join(' · ') || 'Виконується…';
      if (opts.onProgress) opts.onProgress(d);
    }
    return t.promise.then(r => {
      clearTimeout(shown);
      card.remove();
      if (r.state === 'error') UI.toast((opts.title || Tasks.titles[kind] || 'Завдання') + ': ' + (r.err || 'помилка'), { error: true, ms: 4500 });
      else if (r.state === 'cancelled') UI.toast('Скасовано');
      if (opts.refresh !== false && kind !== 'hash' && kind !== 'size') App.refresh();
      return r;
    });
  },
};

// ------------------------------------------------------------ дрібні елементи форм

function seg(options, value, onChange) {
  const box = h('div', { class: 'seg' });
  for (const o of options) {
    const b = h('button', { class: o.value === value ? 'on' : '', text: o.label, onclick: () => {
      for (const x of box.children) x.classList.remove('on');
      b.classList.add('on');
      onChange(o.value);
    } });
    box.appendChild(b);
  }
  return box;
}

function chips(options, value, onChange) {
  const box = h('div', { class: 'chips' });
  for (const o of options) {
    const b = h('button', { class: 'chip' + (o.value === value ? ' on' : ''), text: o.label, onclick: () => {
      for (const x of box.children) x.classList.remove('on');
      b.classList.add('on');
      onChange(o.value);
    } });
    box.appendChild(b);
  }
  return box;
}

function toggle(label, sub, value, onChange) {
  const sw = h('span', { class: 'switch' + (value ? ' on' : '') });
  const b = h('button', { class: 'tog', onclick: () => { value = !value; sw.classList.toggle('on', value); onChange(value); } },
    h('span', { class: 'slabel' }, h('span', { text: label }), sub ? h('small', { text: sub }) : null), sw);
  return b;
}

function field(label, el, extra) {
  return h('label', { class: 'fl' }, h('span', null, label, extra || null), el);
}
