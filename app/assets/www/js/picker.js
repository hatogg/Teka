'use strict';
/* Вибір папки (куди розпакувати, зберегти) і вибір файлів (для конвертера). */

const Picker = {
  /** Повертає Promise зі шляхом вибраної папки або null. */
  folder(start, title) {
    return new Promise(res => {
      let path = start || (Browser.vols[0] && Browser.vols[0].path) || '/';
      let done = false;
      const list = h('div', { class: 'list' });
      const crumbs = h('div', { class: 'crumbs' });
      const ok = h('button', { class: 'btn primary', text: 'Вибрати цю папку', onclick: () => finish(path) });
      const pg = UI.page({
        title: title || 'Виберіть папку',
        body: h('div', null, crumbs, list),
        actions: [{ icon: 'folderPlus', label: 'Нова папка', run: async () => {
          const n = await UI.prompt('Нова папка', 'Нова папка', { ok: 'Створити' });
          if (n == null) return;
          const r = Native.mkdir(path, n.trim());
          if (!r.ok) return UI.toast(r.err || 'Не вдалося', { error: true });
          go(r.path);
        } }],
        onclose: () => finish(null),
      });
      pg.el.appendChild(h('div', { class: 'pfoot' }, ok));
      function finish(v) {
        if (done) return;
        done = true;
        pg.close();
        res(v);
      }
      function go(p) {
        path = p;
        crumbsFor(crumbs, p, go);
        list.innerHTML = '';
        const r = Native.list(p, Settings.get('hidden'));
        ok.disabled = !r.ok || r.writable === false;
        if (!r.ok) { list.appendChild(h('div', { class: 'empty', text: 'Немає доступу до цієї папки' })); return; }
        const dirs = Browser.sorted(r.items.filter(i => i.dir));
        if (!dirs.length) list.appendChild(h('div', { class: 'empty', text: 'Вкладених папок немає' }));
        for (const d of dirs) list.appendChild(fileRow(d, { onOpen: () => go(d.path) }));
      }
      go(path);
    });
  },

  /**
   * Вибір файлів. kinds — масив типів (Kind.of), які можна вибрати; multi — кілька.
   * Повертає Promise з масивом вибраних елементів (порожній — скасовано).
   */
  files(kinds, title, multi = true) {
    return new Promise(res => {
      let done = false, path = Settings.get('lastPath') || (Browser.vols[0] && Browser.vols[0].path);
      const sel = new Map();
      const list = h('div', { class: 'list' + (multi ? ' picks' : '') });
      const crumbs = h('div', { class: 'crumbs' });
      const ok = h('button', { class: 'btn primary', onclick: () => finish(Array.from(sel.values())) });
      const cat = { image: 'images', video: 'video', audio: 'audio' };
      const cats = kinds.map(k => cat[k]).filter(Boolean);
      const top = h('div', { class: 'btools' });
      const pg = UI.page({ title: title || 'Виберіть файли', body: h('div', null, top, crumbs, list), onclose: () => finish([]) });
      pg.el.appendChild(h('div', { class: 'pfoot' }, ok));
      const okFor = it => kinds.includes(Kind.of(it));
      function upd() {
        ok.disabled = !sel.size;
        ok.textContent = sel.size ? 'Вибрати (' + sel.size + ')' : 'Нічого не вибрано';
      }
      function finish(v) {
        if (done) return;
        done = true;
        pg.close();
        res(v);
      }
      function rowFor(it, openDir) {
        const row = fileRow(it, {
          onOpen: (x, r) => {
            if (x.dir) return openDir(x.path);
            if (!multi) { sel.clear(); sel.set(x.path, x); return finish([x]); }
            if (sel.has(x.path)) sel.delete(x.path); else sel.set(x.path, x);
            setRowSelected(r, sel.has(x.path));
            upd();
          },
          onSelect: x => { if (!x.dir) { if (sel.has(x.path)) sel.delete(x.path); else sel.set(x.path, x); setRowSelected(row, sel.has(x.path)); upd(); } },
          selected: x => sel.has(x.path),
        });
        if (it.dir) { const c = row.querySelector('.rcheck'); if (c) c.remove(); }
        return row;
      }
      function go(p) {
        path = p;
        crumbs.hidden = false;
        crumbsFor(crumbs, p, go);
        list.innerHTML = '';
        const r = Native.list(p, Settings.get('hidden'));
        if (!r.ok) { list.appendChild(h('div', { class: 'empty', text: 'Немає доступу до цієї папки' })); return; }
        const items = Browser.sorted(r.items.filter(i => i.dir || okFor(i)));
        if (!items.length) list.appendChild(h('div', { class: 'empty', text: 'Тут немає потрібних файлів' }));
        renderChunked(list, items, it => rowFor(it, go));
      }
      async function showCat(kind) {
        crumbs.hidden = true;
        list.innerHTML = '';
        list.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Пошук…'));
        const r = await Native.call('category', { kind, limit: 2000 });
        list.innerHTML = '';
        if (!r.ok || !r.items.length) { list.appendChild(h('div', { class: 'empty', text: 'Нічого не знайдено' })); return; }
        renderChunked(list, r.items, it => rowFor(it, p => { setChip(null); go(p); }));
      }
      const chipEls = [];
      function setChip(el) { for (const c of chipEls) c.classList.toggle('on', c === el); }
      const folderChip = h('button', { class: 'chip on', onclick: () => { setChip(folderChip); go(path); } }, h('span', { html: ic('folder') }), 'Папки');
      chipEls.push(folderChip);
      top.appendChild(folderChip);
      for (const c of cats) {
        const el = h('button', { class: 'chip', onclick: () => { setChip(el); showCat(c); } }, h('span', { html: ic({ images: 'image', video: 'video', audio: 'music' }[c]) }), { images: 'Усі зображення', video: 'Усі відео', audio: 'Уся музика' }[c]);
        chipEls.push(el);
        top.appendChild(el);
      }
      upd();
      go(path);
    });
  },
};

/** Хлібні крихти для сторінок вибору. */
function crumbsFor(box, path, go) {
  box.innerHTML = '';
  const vol = Browser.volumeOf(path);
  const add = (label, p, icon) => {
    if (box.children.length) box.appendChild(h('span', { class: 'csep', html: ic('right') }));
    box.appendChild(h('button', { class: 'crumb', onclick: () => go(p) }, icon ? h('span', { html: ic(icon) }) : null, h('span', { text: label })));
  };
  if (vol) {
    // перший елемент: сховище; якщо їх кілька — вибір сховища
    add(vol.primary ? 'Пам’ять' : vol.name, vol.path, vol.removable ? 'sd' : 'phone');
    if (Browser.vols.length > 1) {
      const first = box.firstChild, clone = first.cloneNode(true);
      first.replaceWith(clone);
      clone.onclick = async () => {
        const p = await UI.choose('Сховище', Browser.vols.map(v => ({ label: v.name, value: v.path, icon: v.removable ? 'sd' : 'phone', checked: v.path === vol.path, hint: fmtSize(v.free) + ' вільно' })));
        if (p) go(p);
      };
    }
    let p = vol.path;
    for (const part of path.slice(vol.path.length).split('/').filter(Boolean)) { p += '/' + part; add(part, p); }
  } else add(path, path);
  requestAnimationFrame(() => { box.scrollLeft = box.scrollWidth; });
}
