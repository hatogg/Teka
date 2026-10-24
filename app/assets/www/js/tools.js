'use strict';
/* Інструменти: кошик, аналіз сховища, застосунки, доступ з ПК, пошук, налаштування. */

const Tools = {
  init() {
    const v = $('#vTools');
    v.appendChild(h('div', { class: 'vhead' }, h('div', { class: 'vtitle', text: 'Інструменти' })));
    const grid = h('div', { class: 'tools' });
    Tools.pcCard = h('button', { class: 'tool wide', onclick: () => PcAccess.open() });
    const t = (icon, c, title, sub, run) => h('button', { class: 'tool', onclick: run },
      h('span', { class: 'tic2', style: { '--c': c }, html: ic(icon) }), h('div', null, h('b', { text: title }), h('small', { text: sub })));
    grid.append(
      Tools.pcCard,
      t('transform', 'var(--k-image)', 'Конвертер', 'Фото, PDF, текст, звук', () => Convert.hub()),
      t('archive', 'var(--k-archive)', 'Архіви', 'ZIP, TAR, GZ на пристрої', () => Home.category('archives')),
      t('pie', 'var(--k-video)', 'Аналіз сховища', 'Що займає місце', () => Analyze.open()),
      t('trash', 'var(--k-pdf)', 'Кошик', 'Відновлення видаленого', () => Trash.open()),
      t('apps', 'var(--k-apk)', 'Застосунки', 'Копії APK, видалення', () => AppsView.open()),
      t('search', 'var(--k-audio)', 'Пошук', 'За назвою і вмістом', () => Search.open()),
      t('gear', 'var(--k-other)', 'Налаштування', 'Вигляд, кошик, доступ', () => SettingsPage.open()));
    v.appendChild(h('div', { class: 'scroll' }, grid));
    Tools.renderPc(Native.pcStatus());
  },

  renderPc(st) {
    const c = Tools.pcCard;
    if (!c) return;
    c.innerHTML = '';
    c.append(h('span', { class: 'tic2', style: { '--c': 'var(--accent-text)' }, html: ic('monitor') }),
      h('div', null, h('b', { text: 'Доступ з ПК' }), h('small', { text: st.on ? st.url + ' · код ' + st.pin : 'Файли телефону в браузері комп’ютера через Wi-Fi' })),
      st.on ? h('span', { class: 'live' }) : h('span', { class: 'chev', html: ic('right') }));
  },
};

// ------------------------------------------------------------ кошик

const Trash = {
  async open() {
    const list = h('div', { class: 'list picks' });
    const sel = new Map();
    const pg = UI.page({ title: 'Кошик', body: list, actions: [{ icon: 'sweep', label: 'Очистити кошик', run: () => purge(all) }] });
    const restoreBtn = h('button', { class: 'btn tonal', onclick: () => restore(sel.size ? Array.from(sel.values()) : all) });
    const delBtn = h('button', { class: 'btn danger', onclick: () => purge(sel.size ? Array.from(sel.values()) : all) });
    pg.el.appendChild(h('div', { class: 'pfoot' }, restoreBtn, delBtn));
    let all = [];
    async function load() {
      list.innerHTML = '';
      list.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Завантаження…'));
      const r = await Native.task('trashList', {}).promise;
      list.innerHTML = '';
      all = (r.items || []).sort((a, b) => b.time - a.time);
      sel.clear();
      upd();
      if (!all.length) {
        list.appendChild(h('div', { class: 'empty' }, h('span', { html: ic('trash') }), h('b', { text: 'Кошик порожній' }),
          'Видалені файли зберігаються тут ' + Settings.get('trashDays') + ' днів, потім зникають самі.'));
        return;
      }
      const days = Settings.get('trashDays');
      renderChunked(list, all, it => {
        const left = Math.max(0, Math.ceil((it.time + days * 86400000 - Date.now()) / 86400000));
        const row = fileRow({ name: it.name, path: it.path, dir: it.dir, size: it.size, mtime: it.time }, {
          meta: () => (it.dir ? 'Папка' : fmtSize(it.size)) + ' · з ' + dirName(it.orig).replace(/^\/storage\/emulated\/0/, 'Пам’ять') + ' · ще ' + left + ' дн.',
          onOpen: (x, r) => toggle(it, r),
          onSelect: (x, r) => toggle(it, r),
          selected: () => sel.has(it.path),
        });
        return row;
      });
    }
    function toggle(it, row) {
      if (sel.has(it.path)) sel.delete(it.path); else sel.set(it.path, it);
      setRowSelected(row, sel.has(it.path));
      upd();
    }
    function upd() {
      restoreBtn.textContent = sel.size ? 'Відновити (' + sel.size + ')' : 'Відновити все';
      delBtn.textContent = sel.size ? 'Видалити (' + sel.size + ')' : 'Очистити';
      restoreBtn.disabled = delBtn.disabled = !all.length;
    }
    async function restore(items) {
      if (!items.length) return;
      const r = await Tasks.run('restore', { entries: items.map(i => ({ trash: i.trash, entry: i.entry })) });
      if (r.state === 'done') UI.toast('Відновлено: ' + nItems((r.paths || []).length), (r.paths || []).length ? { action: { label: 'Показати', run: () => Browser.go(dirName(r.paths[0]), { focus: r.paths[0] }) } } : {});
      load();
    }
    async function purge(items) {
      if (!items.length) return;
      if (!(await UI.confirm('Видалити назавжди?', nItems(items.length) + ' буде видалено без можливості відновлення.', 'Видалити', true))) return;
      await Tasks.run('purge', { entries: items.map(i => ({ trash: i.trash, entry: i.entry })) });
      load();
    }
    load();
  },
};

// ------------------------------------------------------------ аналіз сховища

const Analyze = {
  COLORS: { images: 'var(--k-image)', video: 'var(--k-video)', audio: 'var(--k-audio)', docs: 'var(--k-doc)', archives: 'var(--k-archive)', apk: 'var(--k-apk)', other: 'var(--k-other)', trash: 'var(--danger)' },
  NAMES: { images: 'Зображення', video: 'Відео', audio: 'Аудіо', docs: 'Документи', archives: 'Архіви', apk: 'APK', other: 'Інше', trash: 'Кошик' },

  async open(root) {
    root = root || (Browser.vols[0] && Browser.vols[0].path);
    const body = h('div', { class: 'ana' });
    const pg = UI.page({ title: 'Аналіз', sub: Browser.volumeOf(root) && Browser.volumeOf(root).path === root ? Browser.volumeOf(root).name : baseName(root), body });
    const prog = h('div', { class: 'ts', text: 'Підрахунок…' });
    body.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), prog));
    const t = Native.task('analyze', { root }, d => { prog.textContent = nFiles(d.files || 0) + ' · ' + fmtSize(d.done || 0) + (d.cur ? ' · ' + d.cur : ''); });
    const close0 = pg.close;
    UI.stack[UI.stack.length - 1].close = () => { t.cancel(); close0(); };
    const r = await t.promise;
    body.innerHTML = '';
    if (r.state !== 'done') { body.appendChild(h('div', { class: 'empty', text: r.err || 'Скасовано' })); return; }
    // кільце за типами
    const types = Object.entries(r.types || {}).sort((a, b) => b[1].size - a[1].size);
    const sum = types.reduce((a, t2) => a + t2[1].size, 0) || 1;
    const C = 2 * Math.PI * 52;
    let off = 0;
    const svg = ['<svg viewBox="0 0 128 128"><circle cx="64" cy="64" r="52" fill="none" stroke="var(--raised)" stroke-width="16"/>'];
    for (const [k, v] of types) {
      const len = v.size / sum * C;
      if (len < 0.5) continue;
      svg.push('<circle cx="64" cy="64" r="52" fill="none" stroke="' + (Analyze.COLORS[k] || 'var(--k-other)') + '" stroke-width="16" stroke-dasharray="' + len.toFixed(2) + ' ' + (C - len).toFixed(2) + '" stroke-dashoffset="' + (-off).toFixed(2) + '"/>');
      off += len;
    }
    svg.push('</svg>');
    const legend = h('div', { class: 'legend' });
    for (const [k, v] of types) legend.appendChild(h('div', null, h('i', { style: { background: Analyze.COLORS[k] || 'var(--k-other)' } }), h('span', { text: Analyze.NAMES[k] || k }), h('em', { text: fmtSize(v.size) })));
    const isVol = r.capacity && Browser.volumeOf(root) && Browser.volumeOf(root).path === root;
    body.append(h('div', { class: 'donut' }, h('div', { class: 'dc', html: svg.join('') }, h('div', { class: 'dl' }, h('b', { text: fmtSize(r.size) }), h('small', { text: isVol ? 'з ' + fmtSize(r.capacity) : nFiles(types.reduce((a, t2) => a + t2[1].count, 0)) }))), legend));
    if (isVol) body.appendChild(h('div', { class: 'note', text: 'Вільно ' + fmtSize(r.free) + '. Решту займають системні файли й дані застосунків, до яких Android не дає доступу.' }));
    if (r.types && r.types.trash && r.types.trash.size > 0) body.appendChild(h('button', { class: 'btn tonal block', style: { marginTop: '10px' }, onclick: () => Trash.open() }, h('span', { html: ic('trash') }), 'У кошику ' + fmtSize(r.types.trash.size) + ' — переглянути'));
    // папки
    body.appendChild(h('div', { class: 'sgroup', style: { padding: '20px 4px 6px' }, text: 'Папки' }));
    const kids = (r.children || []).sort((a, b) => b.size - a.size);
    const max = kids.length ? kids[0].size || 1 : 1;
    for (const c of kids.slice(0, 40)) {
      body.appendChild(h('button', { class: 'brow', onclick: () => c.dir ? Analyze.open(c.path) : Open.item({ name: c.name, path: c.path, dir: false, size: c.size }) },
        fileIcon({ name: c.name, path: c.path, dir: c.dir }, 96, true),
        h('div', { class: 'rtext' }, h('div', { class: 'rname', text: c.name }), h('div', { class: 'bar' }, h('i', { style: { width: Math.max(1, c.size / max * 100) + '%' } }))),
        h('span', { class: 'bsz', text: fmtSize(c.size) })));
    }
    // найбільші файли
    body.appendChild(h('div', { class: 'sgroup', style: { padding: '20px 4px 6px' }, text: 'Найбільші файли' }));
    const big = h('div');
    body.appendChild(big);
    for (const f of (r.largest || []).slice(0, 30)) {
      const it = { name: f.name, path: f.path, size: f.size, dir: false };
      big.appendChild(fileRow(it, {
        meta: () => fmtSize(f.size) + ' · ' + dirName(f.path).replace(root, '…'),
        onOpen: () => Open.item(it, [it]),
        onMore: x => UI.menu([
          { icon: 'folderOpen', label: 'Показати в папці', run: () => { UI.closeAll(); Browser.go(dirName(x.path), { focus: x.path }); } },
          { icon: 'trash', label: 'Видалити', danger: true, run: async () => { await Ops.remove([x]); if (!Native.exists(x.path)) { const el = big.querySelector('[data-path="' + CSS.escape(x.path) + '"]'); if (el) el.remove(); } } },
        ], x.name),
      }));
    }
  },
};

// ------------------------------------------------------------ застосунки

const AppsView = {
  async open() {
    let all = [], filter = 'user', sort = 'name', q = '';
    const list = h('div', { class: 'list applist' });
    const search = h('input', { class: 'field', type: 'search', placeholder: 'Пошук застосунків', autocomplete: 'off', spellcheck: 'false' });
    const tools = h('div', { class: 'btools', style: { padding: '8px 0 6px' } });
    const pg = UI.page({ title: 'Застосунки', body: h('div', null, h('div', { style: { padding: '0 16px' } }, search, tools), list) });
    search.addEventListener('input', debounce(() => { q = search.value.trim().toLowerCase(); render(); }, 150));
    function drawTools() {
      tools.innerHTML = '';
      const c = (label, on, run) => tools.appendChild(h('button', { class: 'chip' + (on ? ' on' : ''), text: label, onclick: () => { run(); drawTools(); render(); } }));
      c('Встановлені', filter === 'user', () => { filter = 'user'; });
      c('Системні', filter === 'system', () => { filter = 'system'; });
      c('Усі', filter === 'all', () => { filter = 'all'; });
      tools.appendChild(h('span', { style: { width: '8px', flex: 'none' } }));
      c('За назвою', sort === 'name', () => { sort = 'name'; });
      c('За розміром', sort === 'size', () => { sort = 'size'; });
      c('Нещодавні', sort === 'date', () => { sort = 'date'; });
    }
    function render() {
      list.innerHTML = '';
      const coll = new Intl.Collator('uk', { sensitivity: 'base' });
      const items = all.filter(a => (filter === 'all' || (filter === 'system' ? a.system : !a.system)) && (!q || a.label.toLowerCase().includes(q) || a.pkg.includes(q)));
      items.sort(sort === 'size' ? (a, b) => b.size - a.size : sort === 'date' ? (a, b) => b.modified - a.modified : (a, b) => coll.compare(a.label, b.label));
      pg.setTitle('Застосунки', items.length + ' · ' + fmtSize(items.reduce((s, a) => s + a.size, 0)));
      if (!items.length) { list.appendChild(h('div', { class: 'empty', text: 'Нічого не знайдено' })); return; }
      renderChunked(list, items, a => {
        const row = h('div', { class: 'row' },
          h('div', { class: 'appic' }, h('img', { loading: 'lazy', alt: '', src: Native.appIcon(a.pkg, 96) })),
          h('div', { class: 'rtext' }, h('div', { class: 'rname', text: a.label }), h('div', { class: 'rmeta', text: a.version + ' · ' + fmtSize(a.size) + (a.splits ? ' · ' + (a.splits + 1) + ' частин' : '') })));
        row.addEventListener('click', () => AppsView.sheet(a));
        return row;
      }, 60);
    }
    drawTools();
    list.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Завантаження списку…'));
    const r = await Native.task('apps', {}).promise;
    all = r.apps || [];
    render();
  },

  sheet(a) {
    const kv = h('dl', { class: 'kv', style: { margin: '0 18px' } });
    const add = (k, v) => { if (v) kv.append(h('dt', { text: k }), h('dd', { text: v })); };
    add('Версія', a.version + ' (' + a.code + ')');
    add('Розмір APK', fmtSize(a.size) + (a.splits ? ' · ' + (a.splits + 1) + ' частин' : ''));
    add('Встановлено', fmtDate(a.installed, true));
    add('Оновлено', fmtDate(a.modified, true));
    add('Ціль', 'API ' + a.target);
    const s = UI.sheet({ body: h('div', null,
      h('div', { class: 'apphero' }, h('div', { class: 'appic' }, h('img', { alt: '', src: Native.appIcon(a.pkg, 192) })), h('b', { text: a.label }), h('small', { class: 'sel', text: a.pkg }),
        a.system ? h('span', { class: 'status', text: a.updated ? 'Системний (оновлений)' : 'Системний' }) : null),
      kv,
      h('div', { class: 'mlist', style: { marginTop: '8px' } },
        a.launch ? h('button', { class: 'mitem', onclick: () => { s.close(); Native.appAction(a.pkg, 'launch'); } }, h('span', { class: 'mic', html: ic('play') }), h('span', { class: 'mlabel' }, h('span', { text: 'Відкрити' }))) : null,
        h('button', { class: 'mitem', onclick: () => { s.close(); AppsView.backup([a]); } }, h('span', { class: 'mic', html: ic('download') }), h('span', { class: 'mlabel' }, h('span', { text: 'Зберегти APK' }), h('small', { text: a.splits ? 'Набір .apks у Download/Тека/APK' : 'У Download/Тека/APK' }))),
        h('button', { class: 'mitem', onclick: async () => { s.close(); const p = await AppsView.backup([a], true); if (p) Native.share([p]); } }, h('span', { class: 'mic', html: ic('share') }), h('span', { class: 'mlabel' }, h('span', { text: 'Поділитися APK' }))),
        h('button', { class: 'mitem', onclick: () => { s.close(); Native.appAction(a.pkg, 'details'); } }, h('span', { class: 'mic', html: ic('info') }), h('span', { class: 'mlabel' }, h('span', { text: 'Відомості в налаштуваннях' }))),
        h('button', { class: 'mitem', onclick: () => { s.close(); Native.appAction(a.pkg, 'market'); } }, h('span', { class: 'mic', html: ic('external') }), h('span', { class: 'mlabel' }, h('span', { text: 'Сторінка в магазині' }))),
        !a.system || a.updated ? h('button', { class: 'mitem danger', onclick: () => { s.close(); Native.appAction(a.pkg, 'uninstall'); } }, h('span', { class: 'mic', html: ic('trash') }), h('span', { class: 'mlabel' }, h('span', { text: a.updated ? 'Видалити оновлення' : 'Видалити застосунок' }))) : null)) });
  },

  async backup(apps, quietToast) {
    const r = await Tasks.run('apkBackup', { pkgs: apps.map(a => a.pkg) });
    if (r.state !== 'done') return null;
    const p = (r.paths || [])[0];
    if (!quietToast) UI.toast('Збережено: ' + baseName(p), { action: { label: 'Показати', run: () => { UI.closeAll(); Browser.go(dirName(p), { focus: p }); } } });
    return p;
  },
};

// ------------------------------------------------------------ доступ з ПК

const PcAccess = {
  pg: null,
  open() {
    const body = h('div', { class: 'pc' });
    PcAccess.pg = UI.page({ title: 'Доступ з ПК', body, onclose: () => { PcAccess.pg = null; } });
    PcAccess.body = body;
    PcAccess.render(Native.pcStatus());
  },

  render(st) {
    Tools.renderPc(st);
    const b = PcAccess.body;
    if (!PcAccess.pg || !b) return;
    b.innerHTML = '';
    if (st.on) {
      b.append(
        h('div', { class: 'pcstate on' },
          h('div', { class: 'big', html: ic('wifi') }),
          h('div', { class: 'url', text: st.url || 'немає Wi-Fi' }),
          h('div', { class: 'pin' }, ...String(st.pin).split('').map(d => h('span', { text: d }))),
          h('p', { text: 'Відкрийте адресу в браузері на комп’ютері в тій самій мережі Wi-Fi і введіть код.' })),
        h('div', { class: 'fbtns', style: { padding: 0 } },
          h('button', { class: 'btn tonal', onclick: () => { Native.clip(st.url); UI.toast('Адресу скопійовано'); } }, h('span', { html: ic('copy') }), 'Копіювати адресу'),
          h('button', { class: 'btn danger primary', onclick: () => Native.pcStop() }, h('span', { html: ic('power') }), 'Вимкнути')),
        st.event ? h('div', { class: 'note pclog', text: 'Остання подія: ' + st.event }) : null);
    } else {
      b.append(
        h('div', { class: 'pcstate' },
          h('div', { class: 'big', html: ic('monitor') }),
          h('b', { style: { fontSize: '18px' }, text: 'Файли телефону — у браузері комп’ютера' }),
          h('p', { text: 'Без кабелю і програм на ПК: перегляд папок, завантаження файлів і цілих папок у ZIP, вивантаження на телефон.' })),
        h('button', { class: 'btn primary block', onclick: () => PcAccess.start() }, h('span', { html: ic('power') }), 'Увімкнути'),
        h('ol', { class: 'steps' },
          h('li', { text: 'Підключіть телефон і комп’ютер до одного Wi-Fi (або комп’ютер — до точки доступу телефону).' }),
          h('li', { text: 'Натисніть «Увімкнути» — з’явиться адреса і шестизначний код.' }),
          h('li', { text: 'Відкрийте адресу в браузері ПК і введіть код. Доступ працює, поки ви його не вимкнете.' })),
        h('div', { class: 'note', text: 'Безпека: без коду файли не видно; після 5 невдалих спроб адреса блокується на хвилину. Код щоразу новий.' + (st.ip ? ' Адреса телефону в мережі: ' + st.ip : ' Зараз телефон не в мережі Wi-Fi.') }));
    }
  },

  start() {
    if (Native.needNotif()) {
      PcAccess.pendingStart = true;
      Native.requestNotif();
      return;
    }
    Native.pcStart('', Settings.get('pcPort') || 8080);
  },
};

// ------------------------------------------------------------ пошук

const Search = {
  open(root) {
    root = root || Browser.path || (Browser.vols[0] && Browser.vols[0].path);
    let type = '', content = false, t = null;
    const input = h('input', { class: 'field', type: 'search', placeholder: 'Назва файлу, напр. звіт або *.pdf', autocomplete: 'off', spellcheck: 'false', enterkeyhint: 'search' });
    const where = h('div', { class: 'rmeta', style: { padding: '6px 2px 0' }, text: 'Шукати в: ' + (Browser.volumeOf(root) && Browser.volumeOf(root).path === root ? Browser.volumeOf(root).name : baseName(root)) });
    const types = h('div', { class: 'btools', style: { padding: '10px 0 4px' } });
    const status = h('div', { class: 'rmeta', style: { padding: '4px 16px 6px' } });
    const list = h('div', { class: 'list' });
    const pg = UI.page({ title: 'Пошук', body: h('div', null, h('div', { style: { padding: '0 16px' } }, input, where, types), status, list), onclose: () => { if (t) t.cancel(); } });
    const kinds = [['', 'Усе'], ['dir', 'Папки'], ['images', 'Фото'], ['video', 'Відео'], ['audio', 'Аудіо'], ['docs', 'Документи'], ['archives', 'Архіви'], ['apk', 'APK']];
    for (const [k, l] of kinds) types.appendChild(h('button', { class: 'chip' + (k === type ? ' on' : ''), text: l, onclick: e => { type = k; for (const c of types.children) c.classList.remove('on'); e.currentTarget.classList.add('on'); run(); } }));
    types.appendChild(h('button', { class: 'chip', onclick: e => { content = !content; e.currentTarget.classList.toggle('on', content); run(); } }, h('span', { html: ic('type') }), 'У тексті файлів'));
    const results = [];
    const opts = { onOpen: it => Open.item(it, results), onMore: it => Browser.itemMenu(it), meta: it => metaOf(it) + ' · ' + dirName(it.path).replace(root, '…') };
    function run() {
      const q = input.value.trim();
      if (t) t.cancel();
      list.innerHTML = '';
      results.length = 0;
      if (q.length < 2) { status.textContent = q ? 'Введіть щонайменше 2 символи' : ''; return; }
      status.innerHTML = '';
      status.append(h('span', { class: 'spin', style: { display: 'inline-block', verticalAlign: 'middle', width: '14px', height: '14px', marginRight: '8px' } }), 'Пошук…');
      const add = items => { for (const it of items || []) { results.push(it); list.appendChild(fileRow(it, opts)); } };
      t = Native.task('search', { root, q, type, content, hidden: Settings.get('hidden') }, d => { add(d.items); status.textContent = 'Пошук… знайдено ' + results.length + ' · переглянуто ' + nFiles(d.files || 0); });
      const my = t;
      t.promise.then(r => {
        if (my !== t) return;
        add(r.items);
        status.textContent = r.state === 'done' ? 'Знайдено: ' + results.length + (r.limit ? ' (показано перші 1000)' : '') : '';
        if (r.state === 'done' && !results.length) list.appendChild(h('div', { class: 'empty', text: 'Нічого не знайдено' }));
      });
    }
    input.addEventListener('input', debounce(run, 350));
    input.addEventListener('keydown', e => { if (e.key === 'Enter') { input.blur(); run(); } });
    setTimeout(() => input.focus(), 150);
  },
};

// ------------------------------------------------------------ налаштування

const SettingsPage = {
  open() {
    const body = h('div', { style: { paddingBottom: '30px' } });
    UI.page({ title: 'Налаштування', body });
    const row = (icon, label, sub, right, run) => h(run ? 'button' : 'div', { class: 'srow', onclick: run }, h('span', { class: 'sic', html: ic(icon) }), h('span', { class: 'slabel' }, h('span', { text: label }), sub ? h('small', { text: sub }) : null), right);
    const sw = (key, after) => {
      const el = h('span', { class: 'switch' + (Settings.get(key) ? ' on' : '') });
      return [el, () => { Settings.set(key, !Settings.get(key)); el.classList.toggle('on', Settings.get(key)); if (after) after(); }];
    };
    const draw = () => {
      body.innerHTML = '';
      const themeName = { auto: 'Як у системі', light: 'Світла', dark: 'Темна' }[Settings.get('theme')];
      const [hid, hidT] = sw('hidden', () => { Browser.renderTools(); Browser.load(); });
      const [ff, ffT] = sw('foldersFirst', () => Browser.render());
      const [th, thT] = sw('thumbs', () => Browser.render());
      const [tr, trT] = sw('useTrash');
      body.append(
        h('div', { class: 'sgroup', text: 'Вигляд' }),
        h('div', { class: 'scard' },
          row('sun', 'Тема', null, h('span', { class: 'sval', text: themeName }), async () => {
            const v = await UI.choose('Тема', [{ label: 'Як у системі', value: 'auto' }, { label: 'Світла', value: 'light' }, { label: 'Темна', value: 'dark' }].map(o => Object.assign(o, { checked: o.value === Settings.get('theme') })));
            if (v) { Settings.set('theme', v); App.theme(); draw(); }
          }),
          row('grid', 'Вигляд папок за замовчуванням', null, h('span', { class: 'sval', text: Settings.get('view') === 'grid' ? 'Сітка' : 'Список' }), () => { Settings.set('view', Settings.get('view') === 'grid' ? 'list' : 'grid'); Settings.set('viewByPath', {}); Browser.render(); draw(); }),
          row('image', 'Мініатюри', 'Фото, відео, обкладинки, значки APK, PDF', th, thT),
          row('folder', 'Папки спершу', null, ff, ffT),
          row('eyeOff', 'Показувати приховані файли', 'Назви, що починаються з крапки', hid, hidT)),
        h('div', { class: 'sgroup', text: 'Видалення' }),
        h('div', { class: 'scard' },
          row('trash', 'Кошик', 'Видалене спершу потрапляє в кошик', tr, trT),
          row('clock', 'Зберігати в кошику', null, h('span', { class: 'sval', text: Settings.get('trashDays') + ' дн.' }), async () => {
            const v = await UI.choose('Скільки зберігати', [7, 14, 30, 60, 90].map(d => ({ label: d + ' днів', value: d, checked: d === Settings.get('trashDays') })));
            if (v) { Settings.set('trashDays', v); draw(); }
          })),
        h('div', { class: 'sgroup', text: 'Доступ з ПК' }),
        h('div', { class: 'scard' },
          row('wifi', 'Порт', 'Якщо 8080 зайнятий, буде вибрано інший', h('span', { class: 'sval', text: String(Settings.get('pcPort')) }), async () => {
            const v = await UI.prompt('Порт', String(Settings.get('pcPort')), { type: 'number', inputmode: 'numeric' });
            const n = parseInt(v, 10);
            if (n >= 1024 && n <= 65535) { Settings.set('pcPort', n); draw(); } else if (v != null) UI.toast('Порт має бути від 1024 до 65535');
          })),
        h('div', { class: 'sgroup', text: 'Дозволи' }),
        h('div', { class: 'scard' },
          row('lock', 'Доступ до всіх файлів', Native.perm() === 'full' ? 'Надано' : 'Не надано — натисніть, щоб надати', h('span', { class: 'chev', html: ic('right') }), () => Native.requestPerm())),
        h('div', { class: 'sgroup', text: 'Про застосунок' }),
        h('div', { class: 'scard' },
          h('div', { class: 'srow' }, h('img', { src: 'icon.png', alt: '', style: { width: '44px', height: '44px', borderRadius: '14px' } }),
            h('span', { class: 'slabel' }, h('span', { text: 'Тека ' + (App.info.version || '') }), h('small', { text: 'Файловий менеджер з архіватором і конвертером. Без реклами, без інтернету, без збору даних.' })))));
    };
    draw();
  },
};
