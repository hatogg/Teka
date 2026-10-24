'use strict';
/* Перегляд папок: хлібні крихти, сортування, список або сітка, вибір, буфер обміну. */

const Browser = {
  path: '', items: [], shown: [], sel: new Map(), hist: [], scrollMem: {}, vols: [], chunk: null, writable: true,

  init() {
    const v = $('#vFiles');
    const E = Browser.E = {};
    E.title = h('b', { text: 'Файли' });
    E.sub = h('small');
    E.selCount = h('b', { text: '' });
    E.crumbs = h('div', { class: 'crumbs' });
    E.tools = h('div', { class: 'btools' });
    E.list = h('div', { class: 'list' });
    E.scroll = h('div', { class: 'scroll' }, E.list);
    E.viewBtn = h('button', { class: 'ib', 'aria-label': 'Вигляд', onclick: () => Browser.toggleView() });
    v.appendChild(h('div', { class: 'bhead' },
      h('div', { class: 'btop' },
        h('div', { class: 'btitle' }, E.title, E.sub),
        h('button', { class: 'ib', 'aria-label': 'Пошук', html: ic('search'), onclick: () => Search.open(Browser.path) }),
        E.viewBtn,
        h('button', { class: 'ib', 'aria-label': 'Меню', html: ic('dots'), onclick: () => Browser.menu() })),
      h('div', { class: 'selhead' },
        h('button', { class: 'ib', 'aria-label': 'Скасувати вибір', html: ic('x'), onclick: () => Browser.clearSel() }),
        E.selCount,
        h('button', { class: 'ib', 'aria-label': 'Вибрати все', html: ic('selectAll'), onclick: () => Browser.selectAll() }),
        h('button', { class: 'ib', 'aria-label': 'Ще', html: ic('dots'), onclick: () => Browser.selMenu() })),
      E.crumbs, E.tools));
    v.appendChild(E.scroll);
    v.appendChild(h('button', { class: 'fab', 'aria-label': 'Створити', html: ic('plus'), onclick: () => Browser.createMenu() }));
    E.selbar = h('div', { class: 'selbar' },
      h('button', { onclick: () => Clip.set('copy', Browser.selItems()) }, h('span', { html: ic('copy') }), 'Копіювати'),
      h('button', { onclick: () => Clip.set('move', Browser.selItems()) }, h('span', { html: ic('move') }), 'Перемістити'),
      h('button', { onclick: () => Ops.compress(Browser.selItems(), Browser.path) }, h('span', { html: ic('archive') }), 'Стиснути'),
      h('button', { onclick: () => Ops.share(Browser.selItems()) }, h('span', { html: ic('share') }), 'Поділитися'),
      h('button', { class: 'danger', onclick: () => Ops.remove(Browser.selItems()) }, h('span', { html: ic('trash') }), 'Видалити'));
    $('#app').insertBefore(E.selbar, $('#nav'));
    E.scroll.addEventListener('scroll', () => { if (Browser.path) Browser.scrollMem[Browser.path] = E.scroll.scrollTop; }, { passive: true });
    Browser.renderTools();
  },

  volumeOf(path) {
    let best = null;
    for (const v of Browser.vols) if ((path === v.path || path.startsWith(v.path + '/')) && (!best || v.path.length > best.path.length)) best = v;
    return best;
  },

  /** Відкрити папку. opts.push=false — без запису в історію (кнопка «Назад»). */
  go(path, opts = {}) {
    if (!path) return;
    if (opts.push !== false && Browser.path && Browser.path !== path) Browser.hist.push(Browser.path);
    if (Browser.hist.length > 60) Browser.hist.shift();
    Browser.path = path;
    Browser.sel.clear();
    Browser.updateSel();
    Settings.set('lastPath', path);
    if (!opts.stay) App.tab('files');
    Browser.load(opts.focus);
  },

  load(focus) {
    const r = Native.list(Browser.path, Settings.get('hidden'));
    Browser.renderCrumbs();
    const E = Browser.E;
    E.list.innerHTML = '';
    if (Browser.chunk) Browser.chunk.stop();
    if (!r.ok) {
      Browser.items = [];
      E.title.textContent = baseName(Browser.path) || '/';
      E.sub.textContent = '';
      const msg = r.err === 'missing' ? ['Папки немає', 'Можливо, її видалено або перейменовано.']
        : r.err === 'notdir' ? ['Це не папка', '']
        : Native.perm() !== 'full' ? ['Потрібен доступ до файлів', 'Дозвольте «Теці» керувати файлами, щоб бачити вміст пам’яті.']
        : ['Немає доступу', 'Android не дозволяє застосункам відкривати цю папку.'];
      E.list.appendChild(h('div', { class: 'empty' }, h('span', { html: ic(r.err === 'missing' ? 'folder' : 'lock') }), h('b', { text: msg[0] }), msg[1],
        Native.perm() !== 'full' && r.err === 'access' ? h('div', { style: { marginTop: '14px' } }, h('button', { class: 'btn primary', text: 'Надати доступ', onclick: () => Native.requestPerm() })) : null));
      return;
    }
    Browser.writable = r.writable;
    Browser.items = r.items;
    Browser.render();
    const mem = Browser.scrollMem[Browser.path];
    E.scroll.scrollTop = mem || 0;
    if (focus) {
      const row = E.list.querySelector('.row[data-path="' + CSS.escape(focus) + '"]');
      if (row) { row.scrollIntoView({ block: 'center' }); row.classList.add('press'); setTimeout(() => row.classList.remove('press'), 900); }
    }
  },

  refresh() {
    if (!Browser.path) return;
    const keep = Browser.E.scroll.scrollTop;
    const sel = new Set(Browser.sel.keys());
    const r = Native.list(Browser.path, Settings.get('hidden'));
    if (!r.ok) return Browser.load();
    Browser.items = r.items;
    Browser.sel.clear();
    for (const it of r.items) if (sel.has(it.path)) Browser.sel.set(it.path, it);
    Browser.render();
    Browser.updateSel();
    Browser.E.scroll.scrollTop = keep;
  },

  sorted(items) {
    const by = Settings.get('sortBy'), desc = Settings.get('sortDesc'), ff = Settings.get('foldersFirst');
    const coll = new Intl.Collator('uk', { numeric: true, sensitivity: 'base' });
    const cmp = {
      name: (a, b) => coll.compare(a.name, b.name),
      date: (a, b) => a.mtime - b.mtime,
      size: (a, b) => (a.dir ? (a.n || 0) : a.size) - (b.dir ? (b.n || 0) : b.size),
      type: (a, b) => coll.compare(extOf(a.name), extOf(b.name)) || coll.compare(a.name, b.name),
    }[by] || ((a, b) => 0);
    return items.slice().sort((a, b) => {
      if (ff && a.dir !== b.dir) return a.dir ? -1 : 1;
      const c = cmp(a, b) || coll.compare(a.name, b.name);
      return desc ? -c : c;
    });
  },

  render() {
    const E = Browser.E;
    const items = Browser.shown = Browser.sorted(Browser.items);
    const vol = Browser.volumeOf(Browser.path);
    const isRoot = vol && vol.path === Browser.path;
    E.title.textContent = isRoot ? vol.name : (baseName(Browser.path) || '/');
    const dirs = items.filter(i => i.dir).length;
    E.sub.textContent = (items.length ? (dirs ? dirs + ' ' + plural(dirs, 'папка', 'папки', 'папок') : '') + (dirs && items.length - dirs ? ', ' : '') + (items.length - dirs ? nFiles(items.length - dirs) : '') : 'Порожньо')
      + (vol ? ' · вільно ' + fmtSize(vol.free) : '');
    const grid = Browser.viewMode() === 'grid';
    E.list.className = 'list' + (grid ? ' grid' : '');
    E.viewBtn.innerHTML = ic(grid ? 'rows' : 'grid');
    E.list.innerHTML = '';
    if (Browser.chunk) Browser.chunk.stop();
    if (!items.length) {
      E.list.appendChild(h('div', { class: 'empty' }, h('span', { html: ic('folderOpen') }), h('b', { text: 'Тут порожньо' }), Browser.writable ? 'Створіть папку або вставте файли.' : ''));
      return;
    }
    const cut = Clip.mode === 'move' ? new Set(Clip.items.map(i => i.path)) : null;
    const opts = {
      size: grid ? 256 : 128,
      onOpen: (it, row) => { if (Browser.sel.size) Browser.toggle(it, row); else Open.item(it, Browser.shown); },
      onSelect: (it, row) => Browser.toggle(it, row),
      onMore: (it, row) => Browser.itemMenu(it),
      selected: it => Browser.sel.has(it.path),
    };
    Browser.chunk = renderChunked(E.list, items, it => {
      const row = fileRow(it, opts);
      if (cut && cut.has(it.path)) row.classList.add('cut');
      // натискання на значок — вибір, як у більшості файлових менеджерів
      row.firstChild.addEventListener('click', e => { e.stopPropagation(); Browser.toggle(it, row); });
      return row;
    });
  },

  renderCrumbs() {
    const E = Browser.E;
    E.crumbs.innerHTML = '';
    const vol = Browser.volumeOf(Browser.path);
    let base = vol ? vol.path : '';
    const rest = vol ? Browser.path.slice(base.length).split('/').filter(Boolean) : Browser.path.split('/').filter(Boolean);
    const add = (label, path, icon) => {
      if (E.crumbs.children.length) E.crumbs.appendChild(h('span', { class: 'csep', html: ic('right') }));
      E.crumbs.appendChild(h('button', { class: 'crumb', onclick: () => Browser.go(path) }, icon ? h('span', { html: ic(icon) }) : null, h('span', { text: label })));
    };
    if (vol) add(vol.primary ? 'Пам’ять' : vol.name, vol.path, vol.removable ? 'sd' : 'phone');
    else add('/', '/', null);
    let p = base;
    for (const part of rest) { p = p + '/' + part; add(part, p); }
    requestAnimationFrame(() => { E.crumbs.scrollLeft = E.crumbs.scrollWidth; });
  },

  sortLabel() {
    const by = { name: 'Назва', date: 'Дата', size: 'Розмір', type: 'Тип' }[Settings.get('sortBy')] || 'Назва';
    return by + (Settings.get('sortDesc') ? ' ↓' : ' ↑');
  },

  renderTools() {
    const E = Browser.E;
    E.tools.innerHTML = '';
    E.tools.append(
      h('button', { class: 'chip', onclick: () => Browser.sortMenu() }, h('span', { html: ic('sort') }), Browser.sortLabel()),
      h('button', { class: 'chip' + (Settings.get('hidden') ? ' on' : ''), onclick: () => { Settings.set('hidden', !Settings.get('hidden')); Browser.renderTools(); Browser.load(); } },
        h('span', { html: ic(Settings.get('hidden') ? 'eye' : 'eyeOff') }), 'Приховані'),
      h('button', { class: 'chip', onclick: () => Ops.newFolder(Browser.path) }, h('span', { html: ic('folderPlus') }), 'Нова папка'),
      h('button', { class: 'chip', onclick: () => Analyze.open(Browser.path) }, h('span', { html: ic('pie') }), 'Що займає місце'));
  },

  sortMenu() {
    const by = Settings.get('sortBy'), desc = Settings.get('sortDesc');
    const pick = (k, d) => { Settings.set('sortBy', k); Settings.set('sortDesc', d); Browser.renderTools(); Browser.render(); };
    UI.menu([
      { header: 'Сортувати' },
      { icon: 'type', label: 'За назвою', checked: by === 'name', run: () => pick('name', by === 'name' ? !desc : false) },
      { icon: 'clock', label: 'За датою', sub: 'Нові спершу', checked: by === 'date', run: () => pick('date', by === 'date' ? !desc : true) },
      { icon: 'hdd', label: 'За розміром', sub: 'Великі спершу', checked: by === 'size', run: () => pick('size', by === 'size' ? !desc : true) },
      { icon: 'tag', label: 'За типом', checked: by === 'type', run: () => pick('type', by === 'type' ? !desc : false) },
      '-',
      { icon: desc ? 'arrowDown' : 'arrowUp', label: desc ? 'За спаданням' : 'За зростанням', run: () => pick(by, !desc) },
      { icon: 'folder', label: 'Папки спершу', checked: Settings.get('foldersFirst'), run: () => { Settings.set('foldersFirst', !Settings.get('foldersFirst')); Browser.render(); } },
    ]);
  },

  viewMode() {
    const per = Settings.get('viewByPath') || {};
    return per[Browser.path] || Settings.get('view');
  },

  toggleView() {
    const next = Browser.viewMode() === 'grid' ? 'list' : 'grid';
    const per = Object.assign({}, Settings.get('viewByPath'));
    per[Browser.path] = next;
    const keys = Object.keys(per);
    if (keys.length > 200) delete per[keys[0]];
    Settings.set('viewByPath', per);
    Browser.render();
  },

  // ---------------------------------------------------------- вибір

  toggle(it, row) {
    if (Browser.sel.has(it.path)) Browser.sel.delete(it.path); else Browser.sel.set(it.path, it);
    if (row) setRowSelected(row, Browser.sel.has(it.path));
    Browser.updateSel();
  },

  selItems: () => Array.from(Browser.sel.values()),

  selectAll() {
    const all = Browser.sel.size === Browser.shown.length;
    Browser.sel.clear();
    if (!all) for (const it of Browser.shown) Browser.sel.set(it.path, it);
    for (const row of $$('.row', Browser.E.list)) setRowSelected(row, Browser.sel.has(row.dataset.path));
    Browser.updateSel();
  },

  clearSel() {
    Browser.sel.clear();
    for (const row of $$('.row.on', Browser.E.list)) setRowSelected(row, false);
    Browser.updateSel();
  },

  updateSel() {
    const n = Browser.sel.size;
    document.body.classList.toggle('selecting', n > 0 && App.cur === 'files');
    if (Browser.E) Browser.E.selCount.textContent = n ? 'Вибрано: ' + n : '';
  },

  // ---------------------------------------------------------- меню

  menu() {
    const p = Browser.path;
    const marked = (Settings.get('bookmarks') || []).includes(p);
    UI.menu([
      { icon: 'folderPlus', label: 'Нова папка', run: () => Ops.newFolder(p) },
      { icon: 'filePlus', label: 'Новий текстовий файл', run: () => Ops.newFile(p) },
      '-',
      { icon: 'selectAll', label: 'Вибрати все', run: () => Browser.selectAll() },
      { icon: 'refresh', label: 'Оновити', run: () => Browser.load() },
      { icon: marked ? 'x' : 'bookmark', label: marked ? 'Прибрати із закладок' : 'Додати в закладки', run: () => Bookmarks.toggle(p) },
      { icon: 'info', label: 'Властивості папки', run: () => Props.open([{ name: baseName(p), path: p, dir: true }]) },
      { icon: 'link', label: 'Копіювати шлях', run: () => { Native.clip(p); UI.toast('Шлях скопійовано'); } },
    ], baseName(p) || '/');
  },

  createMenu() {
    UI.menu([
      { icon: 'folderPlus', label: 'Папка', run: () => Ops.newFolder(Browser.path) },
      { icon: 'filePlus', label: 'Текстовий файл', run: () => Ops.newFile(Browser.path) },
    ], 'Створити');
  },

  itemMenu(it) {
    const k = Kind.of(it);
    const items = [it];
    UI.menu([
      !it.dir ? { icon: 'external', label: 'Відкрити в іншому застосунку', run: () => Native.open(it.path, '', true) } : null,
      !it.dir ? { icon: 'share', label: 'Поділитися', run: () => Ops.share(items) } : null,
      Kind.archive(it) ? { icon: 'unpack', label: 'Розпакувати…', run: () => Ops.extractDialog(it) } : null,
      k === 'apk' || /\.(apks|xapk|apkm)$/i.test(it.name) ? { icon: 'package', label: 'Встановити', run: () => Native.install(it.path) } : null,
      Convert.can(items) ? { icon: 'transform', label: 'Конвертувати…', run: () => Convert.sheet(items) } : null,
      '-',
      { icon: 'copy', label: 'Копіювати', run: () => Clip.set('copy', items) },
      { icon: 'move', label: 'Перемістити', run: () => Clip.set('move', items) },
      { icon: 'pencil', label: 'Перейменувати', run: () => Ops.rename(it) },
      !it.dir ? { icon: 'tag', label: 'Змінити розширення', run: () => Ops.changeExt(items) } : null,
      { icon: 'archive', label: 'Стиснути', run: () => Ops.compress(items, dirName(it.path)) },
      it.dir ? { icon: 'bookmark', label: (Settings.get('bookmarks') || []).includes(it.path) ? 'Прибрати із закладок' : 'Додати в закладки', run: () => Bookmarks.toggle(it.path) } : null,
      { icon: 'info', label: 'Властивості', run: () => Props.open(items) },
      '-',
      { icon: 'trash', label: Settings.get('useTrash') ? 'У кошик' : 'Видалити', danger: true, run: () => Ops.remove(items) },
    ], it.name);
  },

  selMenu() {
    const items = Browser.selItems();
    if (!items.length) return;
    const files = items.filter(i => !i.dir);
    UI.menu([
      items.length === 1 ? { icon: 'pencil', label: 'Перейменувати', run: () => Ops.rename(items[0]) }
        : { icon: 'pencil', label: 'Перейменувати групою', sub: 'Шаблон, нумерація, заміна', run: () => Rename.open(items) },
      files.length ? { icon: 'tag', label: 'Змінити розширення', run: () => Ops.changeExt(files) } : null,
      Convert.can(items) ? { icon: 'transform', label: 'Конвертувати…', run: () => Convert.sheet(items) } : null,
      items.length === 1 && Kind.archive(items[0]) ? { icon: 'unpack', label: 'Розпакувати…', run: () => Ops.extractDialog(items[0]) } : null,
      { icon: 'info', label: 'Властивості', run: () => Props.open(items) },
      { icon: 'link', label: 'Копіювати шляхи', run: () => { Native.clip(items.map(i => i.path).join('\n')); UI.toast('Скопійовано'); } },
      '-',
      { icon: 'swap', label: 'Інвертувати вибір', run: () => {
        const was = new Set(Browser.sel.keys());
        Browser.sel.clear();
        for (const it of Browser.shown) if (!was.has(it.path)) Browser.sel.set(it.path, it);
        for (const row of $$('.row', Browser.E.list)) setRowSelected(row, Browser.sel.has(row.dataset.path));
        Browser.updateSel();
      } },
      Settings.get('useTrash') ? { icon: 'trash', label: 'Видалити назавжди', danger: true, run: () => Ops.remove(items, true) } : null,
    ], 'Вибрано: ' + items.length);
  },

  // ---------------------------------------------------------- назад

  back() {
    if (Browser.sel.size) { Browser.clearSel(); return true; }
    while (Browser.hist.length) {
      const p = Browser.hist.pop();
      if (p !== Browser.path) { Browser.go(p, { push: false, focus: Browser.path }); return true; }
    }
    const vol = Browser.volumeOf(Browser.path);
    if (vol && Browser.path !== vol.path) { const from = Browser.path; Browser.go(dirName(Browser.path), { push: false, focus: from }); return true; }
    return false;
  },
};

// ------------------------------------------------------------ буфер обміну

const Clip = {
  mode: null, items: [], bar: null,

  set(mode, items) {
    if (!items.length) return;
    Clip.mode = mode;
    Clip.items = items.slice();
    Browser.clearSel();
    Clip.show();
    if (mode === 'move') Browser.render();
  },

  clear() {
    const was = Clip.mode;
    Clip.mode = null;
    Clip.items = [];
    if (Clip.bar) Clip.bar.remove();
    Clip.bar = null;
    document.body.classList.remove('clip');
    if (was === 'move') Browser.render();
  },

  show() {
    if (Clip.bar) Clip.bar.remove();
    const n = Clip.items.length;
    const what = n === 1 ? '«' + Clip.items[0].name + '»' : nItems(n);
    Clip.bar = h('div', { class: 'tcard clipbar' },
      h('div', { class: 'tic', html: ic(Clip.mode === 'move' ? 'move' : 'copy') }),
      h('div', { class: 'tbody' },
        h('div', { class: 'tt', text: (Clip.mode === 'move' ? 'Перемістити ' : 'Копіювати ') + what }),
        h('div', { class: 'ts', text: 'Відкрийте потрібну папку і натисніть «Вставити»' })),
      h('button', { class: 'btn primary sm', text: 'Вставити', onclick: () => Clip.paste(Browser.path) }),
      h('button', { class: 'ib', 'aria-label': 'Скасувати', html: ic('x'), onclick: () => Clip.clear() }));
    $('#dock').appendChild(Clip.bar);
    document.body.classList.add('clip');
  },

  async paste(dest) {
    if (!Clip.mode || !dest) return;
    if (App.cur !== 'files') { UI.toast('Відкрийте папку, куди вставити'); return; }
    const items = Clip.items, mode = Clip.mode;
    const srcs = items.map(i => i.path);
    for (const s of srcs) {
      if (dest === s || dest.startsWith(s + '/')) { UI.toast('Не можна вставити папку саму в себе', { error: true }); return; }
    }
    if (mode === 'move' && srcs.every(s => dirName(s) === dest)) { UI.toast('Файли вже в цій папці'); return; }
    let conflict = 'rename';
    const names = Native.conflicts(srcs, dest);
    if (names.length) {
      conflict = await UI.dialog({
        title: names.length === 1 ? '«' + names[0] + '» уже є' : 'Збіг назв: ' + names.length,
        text: names.length === 1 ? 'У цій папці вже є об’єкт з такою назвою.' : 'Деякі назви вже є в цій папці: ' + names.slice(0, 4).join(', ') + (names.length > 4 ? '…' : ''),
        buttons: [
          { label: 'Скасувати', value: null },
          { label: 'Пропустити', value: 'skip' },
          { label: 'Замінити', value: 'replace', danger: true },
          { label: 'Зберегти обидва', value: 'rename', primary: true },
        ],
      });
      if (!conflict) return;
    }
    Clip.clear();
    const r = await Tasks.run(mode, { srcs, dest, conflict });
    if (r.state === 'done') {
      const n = r.files || srcs.length;
      UI.toast((mode === 'move' ? 'Переміщено: ' : 'Скопійовано: ') + nItems(srcs.length - (r.skipped || 0)) + (r.skipped ? ', пропущено ' + r.skipped : '') + (n > srcs.length ? ' (' + nFiles(n) + ')' : ''));
    }
  },
};

// ------------------------------------------------------------ закладки

const Bookmarks = {
  list: () => (Settings.get('bookmarks') || []).filter(Boolean),
  toggle(p) {
    const l = Bookmarks.list();
    const i = l.indexOf(p);
    if (i >= 0) { l.splice(i, 1); UI.toast('Прибрано із закладок'); } else { l.push(p); UI.toast('Додано в закладки'); }
    Settings.set('bookmarks', l);
    Home.render();
  },
};
