'use strict';
/* Огляд: сховища, категорії, швидкі папки, нещодавні файли. */

const Home = {
  CATS: [
    ['images', 'image', 'Фото', 'var(--k-image)'],
    ['video', 'video', 'Відео', 'var(--k-video)'],
    ['audio', 'music', 'Музика', 'var(--k-audio)'],
    ['docs', 'doc', 'Документи', 'var(--k-doc)'],
    ['archives', 'archive', 'Архіви', 'var(--k-archive)'],
    ['apk', 'package', 'APK', 'var(--k-apk)'],
    ['downloads', 'download', 'Завантаження', 'var(--k-dir)'],
    ['large', 'hdd', 'Великі', 'var(--k-pdf)'],
  ],
  stats: null,

  init() {
    const v = $('#vHome');
    v.appendChild(h('div', { class: 'vhead' },
      h('div', { class: 'vtitle', text: 'Тека' }),
      h('button', { class: 'ib', 'aria-label': 'Пошук', html: ic('search'), onclick: () => Search.open(Browser.vols[0] && Browser.vols[0].path) }),
      h('button', { class: 'ib', 'aria-label': 'Налаштування', html: ic('gear'), onclick: () => SettingsPage.open() })));
    Home.box = h('div', { class: 'home' });
    v.appendChild(h('div', { class: 'scroll' }, Home.box));
  },

  render() {
    const b = Home.box;
    if (!b) return;
    b.innerHTML = '';
    if (Native.perm() !== 'full') {
      b.appendChild(h('div', { class: 'permcard' }, h('b', { text: 'Дозвольте доступ до файлів' }),
        h('p', { text: 'Щоб показувати, копіювати, стискати й конвертувати файли в пам’яті телефону, «Теці» потрібен дозвіл «Доступ до всіх файлів». Файли нікуди не надсилаються.' }),
        h('button', { class: 'btn', text: 'Надати доступ', onclick: () => Native.requestPerm() })));
    }
    const vols = h('div', { class: 'vols' });
    for (const v of Browser.vols) {
      const used = v.total - v.free, pct = v.total ? used / v.total * 100 : 0;
      vols.appendChild(h('button', { class: 'vol', onclick: () => Browser.go(v.path) },
        h('span', { class: 'vic', html: ic(v.removable ? 'sd' : 'phone') }),
        h('div', { class: 'vinfo' }, h('div', { class: 'vname', text: v.name }),
          h('div', { class: 'vnums', text: 'Вільно ' + fmtSize(v.free) + ' з ' + fmtSize(v.total) }),
          h('div', { class: 'bar' + (pct > 90 ? ' hot' : '') }, h('i', { style: { width: pct.toFixed(1) + '%' } }))),
        h('span', { class: 'chev', html: ic('right') })));
    }
    b.appendChild(vols);

    b.appendChild(h('div', { class: 'sech' }, h('b', { text: 'Категорії' }), h('button', { text: 'Аналіз', onclick: () => Analyze.open() })));
    const cats = h('div', { class: 'cats' });
    for (const [k, icon, label, c] of Home.CATS) {
      const st = Home.stats && Home.stats[k];
      cats.appendChild(h('button', { class: 'cat', style: { '--c': c }, onclick: () => Home.category(k) },
        h('span', { class: 'cic', html: ic(icon) }), h('b', { text: label }), h('small', { text: st ? (st.count ? (st.size >= 0 ? fmtSize(st.size) : st.count + ' шт.') : '—') : '' })));
    }
    b.appendChild(cats);

    const quick = h('div', { class: 'quick' });
    const info = App.info;
    const folders = [];
    for (const p of Bookmarks.list()) folders.push([p, 'bookmark', baseName(p)]);
    for (const [key, label] of [['dcim', 'Камера'], ['downloads', 'Download'], ['documents', 'Documents'], ['pictures', 'Pictures'], ['music', 'Music'], ['movies', 'Movies']]) {
      if (!info[key]) continue;
      const p = key === 'dcim' ? info.dcim + '/Camera' : info[key];
      if (!folders.some(f => f[0] === p)) folders.push([p, 'folder', label]);
    }
    for (const [p, icon, label] of folders) {
      quick.appendChild(h('button', { class: 'qf', onclick: () => Browser.go(Native.exists(p) ? p : dirName(p)) }, h('span', { html: ic(icon) }), label));
    }
    b.appendChild(h('div', { class: 'sech' }, h('b', { text: 'Папки' })));
    b.appendChild(quick);

    Home.recentBox = h('div', { class: 'recent' });
    b.appendChild(h('div', { class: 'sech' }, h('b', { text: 'Нещодавні' }), h('button', { text: 'Усі', onclick: () => Home.category('recent') })));
    b.appendChild(Home.recentBox);
    Home.loadRecent();
  },

  async loadRecent() {
    if (Native.perm() !== 'full') return;
    const r = await Native.call('category', { kind: 'recent', limit: 12 });
    const box = Home.recentBox;
    if (!box) return;
    box.innerHTML = '';
    if (!r.ok || !r.items.length) { box.appendChild(h('div', { class: 'rmeta', style: { gridColumn: '1/-1' }, text: 'Поки що нічого' })); return; }
    for (const it of r.items.slice(0, 9)) {
      const k = Kind.of(it);
      const th = h('div', { class: 'rth', style: { '--c': 'var(--k-' + k + ')' }, html: ic(Kind.icon[k]) });
      if (Kind.thumb(it)) {
        const img = h('img', { loading: 'lazy', alt: '' });
        img.onload = () => { th.innerHTML = ''; th.appendChild(img); };
        img.src = Native.thumbUrl(it.path, 256, it.mtime);
      }
      box.appendChild(h('button', { class: 'rc', onclick: () => Open.item(it, r.items), oncontextmenu: e => { e.preventDefault(); Browser.itemMenu(it); } }, th, h('span', { text: it.name })));
    }
  },

  loadStats() {
    if (Native.perm() !== 'full') return;
    Native.task('stats', {}).promise.then(r => {
      if (r.state !== 'done') return;
      Home.stats = r.stats || {};
      // «Завантаження» і «Великі» рахуємо окремо
      Native.call('category', { kind: 'large', limit: 1 }).then(l => {
        if (l.ok) Home.stats.large = { count: l.total, size: -1 };
        Home.render();
      });
    });
  },

  /** Сторінка категорії з медіатеки. */
  async category(kind) {
    if (kind === 'downloads') return Browser.go(App.info.downloads);
    const names = { images: 'Фото', video: 'Відео', audio: 'Музика', docs: 'Документи', archives: 'Архіви', apk: 'APK', large: 'Великі файли (понад 50 МБ)', recent: 'Нещодавні (30 днів)' };
    const grid = kind === 'images' || kind === 'video';
    const list = h('div', { class: 'list' + (grid ? ' grid' : '') });
    const pg = UI.page({ title: names[kind] || kind, body: list });
    list.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Завантаження…'));
    const r = await Native.call('category', { kind, limit: 5000 });
    list.innerHTML = '';
    if (!r.ok || !r.items.length) {
      list.appendChild(h('div', { class: 'empty' }, h('span', { html: ic('search') }), h('b', { text: 'Нічого не знайдено' }), 'Android ще не проіндексував такі файли або їх немає.'));
      return;
    }
    const total = r.items.reduce((a, i) => a + (i.size || 0), 0);
    pg.setTitle(names[kind] || kind, nFiles(r.total) + ' · ' + fmtSize(total));
    const opts = {
      size: grid ? 256 : 128,
      onOpen: it => Open.item(it, r.items),
      onSelect: it => Browser.itemMenu(it),
      onMore: it => UI.menu([
        { icon: 'folderOpen', label: 'Показати в папці', run: () => { UI.closeAll(); Browser.go(dirName(it.path), { focus: it.path }); } },
        Convert.can([it]) ? { icon: 'transform', label: 'Конвертувати…', run: () => Convert.sheet([it]) } : null,
        Kind.archive(it) ? { icon: 'unpack', label: 'Розпакувати…', run: () => Ops.extractDialog(it) } : null,
        { icon: 'share', label: 'Поділитися', run: () => Native.share([it.path]) },
        { icon: 'info', label: 'Властивості', run: () => Props.open([it]) },
        { icon: 'trash', label: 'Видалити', danger: true, run: () => Ops.remove([it]) },
      ], it.name),
      meta: it => kind === 'large' ? fmtSize(it.size) + ' · ' + dirName(it.path).replace(/^\/storage\/emulated\/0/, 'Пам’ять') : metaOf(it),
    };
    renderChunked(list, r.items, it => {
      const row = fileRow(it, opts);
      const c = row.querySelector('.rcheck');
      if (c) c.remove();
      return row;
    });
  },
};
