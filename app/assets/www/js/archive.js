'use strict';
/* Перегляд архіву без розпакування: папки всередині, вибір, розпакування вибраного, відкриття файлу. */

const ArchiveView = {
  async open(it, charset) {
    let cur = '', entries = [], tree = null, autoRoot = null;
    const sel = new Set();
    const crumbs = h('div', { class: 'crumbs' });
    const list = h('div', { class: 'list' });
    const sum = h('div', { class: 'arcsum' });
    const pg = UI.page({ title: it.name, sub: 'Архів', body: h('div', null, sum, crumbs, list), actions: [
      { icon: 'enc', label: 'Кодування назв', run: () => chooseCharset() },
      { icon: 'info', label: 'Властивості', run: () => Props.open([it]) },
    ] });
    const extractBtn = h('button', { class: 'btn primary', text: 'Розпакувати все', onclick: () => Ops.extractDialog(it, sel.size ? Array.from(sel) : null, usedCharset) });
    const foot = h('div', { class: 'pfoot' }, extractBtn);
    if (/\.(apks|xapk|apkm)$/i.test(it.name)) foot.prepend(h('button', { class: 'btn tonal', text: 'Встановити', onclick: () => Native.install(it.path) }));
    pg.el.appendChild(foot);
    list.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Читання архіву…'));
    let usedCharset = charset || '';
    const r = await Native.call('archiveList', { path: it.path, charset: charset || '' });
    list.innerHTML = '';
    if (!r.ok) {
      const enc = /encrypted/.test(r.err || '');
      list.appendChild(h('div', { class: 'empty' }, h('span', { html: ic(enc ? 'lock' : 'alert') }),
        h('b', { text: enc ? 'Архів захищено паролем' : 'Не вдалося відкрити архів' }),
        enc ? 'Такі архіви «Тека» поки не відкриває.' : (r.err || 'Можливо, файл пошкоджено або це непідтримуваний формат (RAR, 7z).')));
      foot.remove();
      return;
    }
    usedCharset = r.charset || '';
    entries = r.entries;
    // дерево: шлях папки → {dirs: Map, files: []}
    tree = new Map();
    const node = p => { let n = tree.get(p); if (!n) { n = { dirs: new Set(), files: [] }; tree.set(p, n); } return n; };
    node('');
    let total = 0, ctotal = 0, files = 0;
    for (const e of entries) {
      const name = e.name.replace(/\/+$/, '');
      if (!name) continue;
      const parts = name.split('/');
      let p = '';
      for (let i = 0; i < parts.length - 1; i++) {
        const np = p + parts[i] + '/';
        node(p).dirs.add(np);
        node(np);
        p = np;
      }
      if (e.dir) { node(p).dirs.add(p + parts[parts.length - 1] + '/'); node(p + parts[parts.length - 1] + '/'); }
      else { node(p).files.push(e); total += Math.max(0, e.size); ctotal += Math.max(0, e.csize); files++; }
    }
    const ratio = total > 0 && ctotal > 0 ? Math.round((1 - ctotal / total) * 100) : null;
    sum.append(h('span', { class: 'tic2', html: ic('archive') }), h('div', null,
      h('b', { text: nFiles(files) + ' · ' + fmtSize(total) }), h('br'),
      fmtSize(it.size) + ' на диску' + (ratio != null && ratio > 0 ? ' · стиснуто на ' + ratio + '%' : '') + (usedCharset && usedCharset !== 'UTF-8' ? ' · назви: ' + usedCharset.replace('IBM866', 'CP866') : '')));

    function dirSize(p) {
      let s = 0, n = 0;
      for (const [k, v] of tree) if (k.startsWith(p)) for (const f of v.files) { s += Math.max(0, f.size); n++; }
      return [s, n];
    }
    function render() {
      const n = tree.get(cur) || { dirs: new Set(), files: [] };
      crumbs.innerHTML = '';
      const addC = (label, p) => {
        if (crumbs.children.length) crumbs.appendChild(h('span', { class: 'csep', html: ic('right') }));
        crumbs.appendChild(h('button', { class: 'crumb', onclick: () => { cur = p; render(); } }, h('span', { text: label })));
      };
      addC(it.name, '');
      let p = '';
      for (const part of cur.split('/').filter(Boolean)) { p += part + '/'; addC(part, p); }
      requestAnimationFrame(() => { crumbs.scrollLeft = crumbs.scrollWidth; });
      list.innerHTML = '';
      const coll = new Intl.Collator('uk', { numeric: true, sensitivity: 'base' });
      const dirs = Array.from(n.dirs).sort(coll.compare);
      const fl = n.files.slice().sort((a, b) => coll.compare(a.name, b.name));
      const rows = dirs.map(d => {
        const [s, c] = dirSize(d);
        return { name: d.slice(cur.length).replace(/\/$/, ''), path: d, dir: true, n: c, size: s, entry: d };
      }).concat(fl.map(f => ({ name: f.name.slice(f.name.lastIndexOf('/') + 1), path: f.name, dir: false, size: f.size, mtime: f.mtime, entry: f.name, csize: f.csize })));
      if (!rows.length) list.appendChild(h('div', { class: 'empty', text: 'Порожньо' }));
      renderChunked(list, rows, x => {
        const row = fileRow(Object.assign({}, x, { path: '\u0000' + x.entry }), {
          meta: y => y.dir ? nFiles(x.n) + ' · ' + fmtSize(x.size) : fmtSize(x.size) + (x.mtime > 0 ? ' · ' + fmtDate(x.mtime) : ''),
          onOpen: () => {
            if (sel.size) return toggle(x, row);
            if (x.dir) { cur = x.entry; render(); } else openEntry(x);
          },
          onSelect: () => toggle(x, row),
          selected: () => sel.has(x.entry),
          noThumb: true,
        });
        return row;
      });
      upd();
    }
    function toggle(x, row) {
      if (sel.has(x.entry)) sel.delete(x.entry); else sel.add(x.entry);
      setRowSelected(row, sel.has(x.entry));
      upd();
    }
    function upd() {
      extractBtn.textContent = sel.size ? 'Розпакувати вибране (' + sel.size + ')' : 'Розпакувати все';
      document.body.classList.toggle('arcsel', sel.size > 0);
    }
    async function openEntry(x) {
      UI.toast('Видобування «' + x.name + '»…');
      const o = await Native.call('archiveOpen', { path: it.path, entry: x.entry, charset: usedCharset });
      if (!o.ok) return UI.toast(o.err || 'Не вдалося видобути файл', { error: true });
      const item = { name: x.name, path: o.path, size: x.size, mtime: x.mtime, dir: false };
      const k = Kind.of(item);
      if (k === 'image' || k === 'video' || k === 'audio' || k === 'pdf' || k === 'text' || k === 'apk' || k === 'archive') Open.item(item, [item]);
      else Native.open(o.path, '', true);
    }
    async function chooseCharset() {
      if (Archive_kind(it.name) !== 'zip') return UI.toast('Кодування назв має значення лише для ZIP');
      const v = await UI.choose('Кодування назв файлів', [
        { label: 'Автоматично', value: '', checked: !charset },
        { label: 'UTF-8', value: 'UTF-8', checked: charset === 'UTF-8' },
        { label: 'CP866 (архіви з Windows)', value: 'IBM866', checked: charset === 'IBM866' },
        { label: 'Windows-1251', value: 'windows-1251', checked: charset === 'windows-1251' },
        { label: 'CP437 (латиниця DOS)', value: 'IBM437', checked: charset === 'IBM437' },
      ]);
      if (v == null) return;
      pg.close();
      ArchiveView.open(it, v);
    }
    const close0 = pg.close;
    const entry = UI.stack[UI.stack.length - 1];
    entry.close = () => {
      if (sel.size) { sel.clear(); render(); return; }
      if (cur && cur !== autoRoot) { cur = cur.replace(/[^/]+\/$/, ''); render(); return; }
      document.body.classList.remove('arcsel');
      close0();
    };
    pg.head.querySelector('.ib').replaceWith(h('button', { class: 'ib', html: ic('arrowLeft'), 'aria-label': 'Назад', onclick: () => entry.close() }));
    // архів з однією кореневою папкою — одразу всередину
    const root = tree.get('');
    if (root && root.dirs.size === 1 && !root.files.length) cur = autoRoot = Array.from(root.dirs)[0];
    render();
  },
};

function Archive_kind(name) {
  const l = name.toLowerCase();
  if (/\.(tar\.gz|tgz)$/.test(l)) return 'tgz';
  if (l.endsWith('.tar')) return 'tar';
  if (l.endsWith('.gz')) return 'gz';
  return 'zip';
}
