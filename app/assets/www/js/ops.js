'use strict';
/* Дії з файлами: створення, перейменування, зміна розширення, стиснення, розпакування, видалення. */

const Ops = {
  async newFolder(parent) {
    const name = await UI.prompt('Нова папка', 'Нова папка', { ok: 'Створити' });
    if (name == null) return;
    const r = Native.mkdir(parent, name.trim());
    if (!r.ok) return UI.toast(r.err || 'Не вдалося створити папку', { error: true });
    App.refresh();
    Browser.load(r.path);
  },

  async newFile(parent) {
    const name = await UI.prompt('Новий файл', 'Нотатка.txt', { ok: 'Створити', select: 'name' });
    if (name == null) return;
    const r = Native.mkfile(parent, name.trim());
    if (!r.ok) return UI.toast(r.err || 'Не вдалося створити файл', { error: true });
    Browser.load(r.path);
    if (Kind.of({ name: name.trim() }) === 'text' || !extOf(name)) Viewer.text({ name: name.trim(), path: r.path, size: 0 }, true);
  },

  async rename(it) {
    const name = await UI.prompt('Перейменувати', it.name, { ok: 'Перейменувати', select: it.dir ? true : 'name' });
    if (name == null || name.trim() === it.name) return;
    const nn = name.trim();
    if (!it.dir && extOf(nn) !== extOf(it.name) && extOf(it.name)) {
      const ok = await UI.confirm('Змінити розширення?', 'Розширення «.' + extOf(it.name) + '» зміниться на «' + (extOf(nn) ? '.' + extOf(nn) : 'без розширення') + '». Файл може перестати відкриватися.', 'Змінити');
      if (!ok) return;
    }
    const r = Native.rename(it.path, nn);
    if (!r.ok) return UI.toast(r.err || 'Не вдалося перейменувати', { error: true });
    Browser.clearSel();
    App.refresh();
  },

  /** Зміна розширення одного чи кількох файлів, з підказкою про справжню конвертацію. */
  async changeExt(items) {
    items = items.filter(i => !i.dir);
    if (!items.length) return;
    const exts = [...new Set(items.map(i => extOf(i.name)))];
    const cur = exts.length === 1 ? exts[0] : '';
    const input = h('input', { class: 'field mono', value: cur, placeholder: 'наприклад, jpg', autocapitalize: 'off', spellcheck: 'false' });
    const quick = h('div', { class: 'chips' });
    const kinds = new Set(items.map(Kind.of));
    const sugg = kinds.has('image') ? ['jpg', 'png', 'webp'] : kinds.has('audio') ? ['mp3', 'm4a', 'ogg'] : kinds.has('video') ? ['mp4', 'mkv', '3gp'] : kinds.has('text') || kinds.has('other') ? ['txt', 'md', 'json', 'html', 'csv'] : ['zip', 'txt', 'bin'];
    for (const e of sugg) quick.appendChild(h('button', { class: 'chip', text: '.' + e, onclick: () => { input.value = e; } }));
    const body = h('div', { class: 'form' },
      field(items.length === 1 ? items[0].name : 'Файлів: ' + items.length, input),
      quick,
      h('div', { class: 'note', text: 'Змінюється лише назва — вміст файлу лишається тим самим. Щоб справді перетворити формат (PNG → JPG, WAV → M4A, TXT → PDF), скористайтеся конвертером.' }));
    const r = await UI.dialog({
      title: 'Змінити розширення', html: body,
      buttons: [{ label: 'Скасувати', value: null }, { label: 'Змінити', value: 'go', primary: true }],
    });
    if (r !== 'go') return;
    let ext = input.value.trim().replace(/^\.+/, '');
    if (/[\\/:*?"<>|]/.test(ext)) return UI.toast('Недопустимі символи в розширенні', { error: true });
    // якщо це перетворення між форматами, яке вміє конвертер — запропонувати його
    const conv = Convert.suggestFor(items, ext.toLowerCase());
    if (conv) {
      const how = await UI.dialog({
        title: 'Конвертувати в .' + ext.toLowerCase() + '?',
        text: 'Перейменований файл залишиться у старому форматі, і деякі програми не відкриють його. «Тека» може справді перетворити ' + (items.length === 1 ? 'файл' : 'файли') + ' — оригінал залишиться.',
        buttons: [{ label: 'Лише перейменувати', value: 'rename' }, { label: 'Конвертувати', value: 'convert', primary: true }],
      });
      if (how == null) return;
      if (how === 'convert') return Convert.sheet(items, conv);
    }
    const pairs = items.map(i => [i.path, splitName(i.name)[0] + (ext ? '.' + ext : '')]).filter(p => p[1] !== baseName(p[0]));
    if (!pairs.length) return;
    const res = await Tasks.run('renameMany', { pairs }, { quiet: true });
    if (res.state === 'done') { UI.toast('Змінено: ' + nFiles(res.renamed)); Browser.clearSel(); }
  },

  share(items) {
    const files = items.filter(i => !i.dir);
    if (!files.length) return UI.toast('Папками поділитися не можна — спершу стисніть їх в архів');
    Native.share(files.map(i => i.path));
  },

  /** Видалення: у кошик (якщо ввімкнено) або назавжди. */
  async remove(items, forever) {
    if (!items.length) return;
    const trash = Settings.get('useTrash') && !forever && !items.some(i => i.path.includes('/.teka_trash/'));
    const what = items.length === 1 ? '«' + items[0].name + '»' : nItems(items.length);
    const ok = await UI.confirm(trash ? 'Перемістити в кошик?' : 'Видалити назавжди?',
      trash ? what + ' можна буде відновити з кошика протягом ' + Settings.get('trashDays') + ' днів.' : what + ' буде видалено без можливості відновлення.',
      trash ? 'У кошик' : 'Видалити', !trash);
    if (!ok) return;
    Browser.clearSel();
    const r = await Tasks.run(trash ? 'trash' : 'delete', { paths: items.map(i => i.path) }, { quiet: true });
    if (r.state === 'done') {
      if (trash) UI.toast('Переміщено в кошик: ' + nItems(r.moved), { action: { label: 'Кошик', run: () => Trash.open() } });
      else UI.toast(r.failed ? 'Не вдалося видалити: ' + r.failed : 'Видалено');
    }
  },

  // ---------------------------------------------------------- стиснення

  compress(items, dest) {
    if (!items.length) return;
    const base = items.length === 1 ? splitName(items[0].name)[0] : (baseName(dest) || 'Архів');
    let fmt = 'zip', level = Settings.get('zipLevel');
    const name = h('input', { class: 'field', value: base, autocapitalize: 'off', spellcheck: 'false' });
    const ext = h('span', { class: 'mono dim', text: '.zip' });
    const levelBox = field('Стиснення', seg([
      { label: 'Без', value: 0 }, { label: 'Швидко', value: 1 }, { label: 'Звичайно', value: 6 }, { label: 'Макс.', value: 9 },
    ], level, v => { level = v; }));
    const total = items.reduce((a, i) => a + (i.dir ? 0 : i.size || 0), 0);
    const body = h('div', null,
      h('div', { class: 'form' },
        field('Назва архіву', name, ext),
        field('Формат', seg([
          { label: 'ZIP', value: 'zip' }, { label: 'TAR.GZ', value: 'tgz' }, { label: 'TAR', value: 'tar' },
        ], fmt, v => { fmt = v; ext.textContent = { zip: '.zip', tgz: '.tar.gz', tar: '.tar' }[v]; levelBox.hidden = v !== 'zip'; })),
        levelBox,
        h('div', { class: 'note', text: (items.length === 1 ? '«' + items[0].name + '»' : nItems(items.length)) + (total ? ' · ' + fmtSize(total) + (items.some(i => i.dir) ? ' + папки' : '') : '') + ' → ' + (baseName(dest) || dest) + '. ZIP відкривається будь-де, зокрема у Windows; TAR.GZ зручний для Linux і резервних копій.' })),
      h('div', { class: 'fbtns' }, h('button', { class: 'btn primary', text: 'Стиснути', onclick: go })));
    const s = UI.sheet({ title: 'Створити архів', body });
    setTimeout(() => { name.focus(); name.select(); }, 150);
    async function go() {
      const n = name.value.trim();
      if (!n) return name.focus();
      s.close();
      Settings.set('zipLevel', level);
      Browser.clearSel();
      const r = await Tasks.run(fmt, { srcs: items.map(i => i.path), dest, name: n, level });
      if (r.state === 'done') UI.toast('Архів створено: ' + baseName(r.path) + ' · ' + fmtSize(r.size), { action: { label: 'Показати', run: () => Browser.go(dirName(r.path), { focus: r.path }) } });
    }
  },

  // ---------------------------------------------------------- розпакування

  extractDialog(it, only, charset) {
    const folder = dirName(it.path);
    const base = splitName(it.name)[0];
    let where = 'folder', conflict = 'rename', dest = folder;
    const destLabel = h('span', { class: 'sval', text: baseName(folder) });
    const body = h('div', null,
      h('div', { class: 'form' },
        only && only.length ? h('div', { class: 'note', text: 'Вибрано в архіві: ' + only.length }) : null,
        field('Куди', seg([
          { label: 'У папку «' + (base.length > 16 ? base.slice(0, 15) + '…' : base) + '»', value: 'folder' }, { label: 'Сюди', value: 'here' },
        ], where, v => { where = v; })),
        h('button', { class: 'srow', style: { padding: '8px 2px', minHeight: '48px' }, onclick: async () => {
          const p = await Picker.folder(dest, 'Розпакувати в…');
          if (p) { dest = p; destLabel.textContent = baseName(p) || p; }
        } }, h('span', { class: 'sic', html: ic('folder') }), h('span', { class: 'slabel' }, h('span', { text: 'Папка призначення' })), destLabel, h('span', { class: 'chev', html: ic('right') })),
        field('Якщо файл уже є', seg([
          { label: 'Зберегти обидва', value: 'rename' }, { label: 'Замінити', value: 'replace' }, { label: 'Пропустити', value: 'skip' },
        ], conflict, v => { conflict = v; }))),
      h('div', { class: 'fbtns' }, h('button', { class: 'btn primary', text: 'Розпакувати', onclick: go })));
    const s = UI.sheet({ title: 'Розпакувати «' + it.name + '»', body });
    async function go() {
      s.close();
      const r = await Tasks.run('extract', { archive: it.path, dest, folder: where === 'folder', only: only || [], conflict, charset: charset || '' });
      if (r.state === 'done') UI.toast('Розпаковано: ' + nFiles(r.count), { action: { label: 'Відкрити', run: () => Browser.go(r.path) } });
    }
  },
};

// ------------------------------------------------------------ властивості

const Props = {
  async open(items) {
    const one = items.length === 1 ? items[0] : null;
    const body = h('div', { class: 'props' });
    const s = UI.sheet({ title: 'Властивості', body });
    if (one) {
      const st = Native.stat(one.path);
      const it = Object.assign({}, one, st.exists ? st : {});
      body.appendChild(h('div', { class: 'phero' }, fileIcon(it, 160), h('div', null, h('b', { text: it.name }), h('small', { text: Kind.label[Kind.of(it)] + (it.dir ? '' : ' · ' + (st.mime || '')) }))));
      const kv = h('dl', { class: 'kv' });
      const add = (k, v, mono) => { if (v != null && v !== '') kv.append(h('dt', { text: k }), h('dd', { class: mono ? 'mono' : '', text: v })); return kv.lastChild; };
      add('Розташування', dirName(it.path), true);
      const sizeDd = add('Розмір', it.dir ? 'підрахунок…' : fmtSize(it.size) + (it.size >= 1024 ? ' (' + fmtNum(it.size) + ' Б)' : ''));
      if (it.dir) add('Вміст', it.n >= 0 ? nItems(it.n) : '—');
      add('Змінено', fmtDate(it.mtime, true));
      add('Доступ', it.canWrite === false ? 'лише читання' : 'читання і запис');
      if (it.hidden) add('Прихований', 'так');
      body.appendChild(kv);
      if (it.dir) {
        Native.call('folderSize', { path: it.path }).then(r => { if (r.ok) sizeDd.textContent = fmtSize(r.size) + ' · ' + nFiles(r.files); });
      }
      const k = Kind.of(it);
      if (k === 'image') Native.call('imageInfo', { path: it.path }).then(r => {
        if (!r.ok) return;
        if (r.width) add('Роздільність', r.width + ' × ' + r.height + ' (' + (r.width * r.height / 1e6).toFixed(1).replace('.', ',') + ' Мп)');
        if (r.date) add('Знято', r.date.replace(/^(\d{4}):(\d\d):(\d\d)/, '$3.$2.$1'));
        if (r.camera) add('Камера', r.camera);
        const ex = [r.fnumber ? 'f/' + r.fnumber : '', r.exposure ? (r.exposure < 1 ? '1/' + Math.round(1 / r.exposure) : r.exposure) + ' с' : '', r.iso ? 'ISO ' + r.iso : '', r.focal ? r.focal.replace(/\/1$/, '') + ' мм' : ''].filter(Boolean).join(' · ');
        if (ex) add('Зйомка', ex);
        if (r.lat != null) add('Місце', r.lat.toFixed(5) + ', ' + r.lon.toFixed(5), true);
      });
      if (k === 'video' || k === 'audio') Native.call('mediaInfo', { path: it.path }).then(r => {
        if (!r.ok) return;
        if (r.duration) add('Тривалість', fmtDur(+r.duration));
        if (r.width) add('Кадр', r.width + ' × ' + r.height + (r.rotation && r.rotation !== '0' ? ', поворот ' + r.rotation + '°' : ''));
        if (r.bitrate) add('Бітрейт', Math.round(r.bitrate / 1000) + ' кбіт/с');
        if (r.title) add('Назва', r.title);
        if (r.artist) add('Виконавець', r.artist);
        if (r.album) add('Альбом', r.album);
        for (const t of r.tracks || []) add(t.mime.startsWith('video') ? 'Відео' : t.mime.startsWith('audio') ? 'Звук' : 'Доріжка',
          t.mime.replace(/^(audio|video)\//, '') + (t.rate ? ', ' + t.rate + ' Гц' : '') + (t.channels ? ', ' + t.channels + ' кан.' : '') + (t.fps ? ', ' + Math.round(t.fps) + ' к/с' : ''));
      });
      if (k === 'pdf') Native.call('pdfInfo', { path: it.path }).then(r => { if (r.ok) add('Сторінок', r.pages); else add('PDF', r.err); });
      if (!it.dir) {
        const hb = h('div', { class: 'hashes' });
        body.appendChild(hb);
        hb.appendChild(h('button', { class: 'btn tonal block', onclick: async e => {
          e.currentTarget.remove();
          hb.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Обчислення…'));
          const r = await Tasks.run('hash', { path: it.path }, { quiet: true, refresh: false });
          hb.innerHTML = '';
          if (r.state !== 'done') return;
          for (const [n, v] of [['MD5', r.md5], ['SHA-1', r.sha1], ['SHA-256', r.sha256]]) {
            hb.appendChild(h('button', { class: 'hash', onclick: () => { Native.clip(v); UI.toast(n + ' скопійовано'); } }, h('small', { text: n }), h('div', { text: v })));
          }
        } }, h('span', { html: ic('hash') }), 'Контрольні суми'));
      }
      return;
    }
    const files = items.filter(i => !i.dir), dirs = items.filter(i => i.dir);
    body.appendChild(h('div', { class: 'phero' }, h('div', { class: 'ric', style: { '--c': 'var(--k-dir)' }, html: ic('files') }), h('div', null, h('b', { text: nItems(items.length) }), h('small', { text: (dirs.length ? dirs.length + ' ' + plural(dirs.length, 'папка', 'папки', 'папок') : '') + (dirs.length && files.length ? ', ' : '') + (files.length ? nFiles(files.length) : '') }))));
    const kv = h('dl', { class: 'kv' });
    const sz = h('dd', { text: 'підрахунок…' });
    kv.append(h('dt', { text: 'Розмір' }), sz, h('dt', { text: 'Розташування' }), h('dd', { class: 'mono', text: dirName(items[0].path) }));
    body.appendChild(kv);
    const r = await Tasks.run('size', { paths: items.map(i => i.path) }, { quiet: true, refresh: false });
    if (r.state === 'done') sz.textContent = fmtSize(r.size) + ' · ' + nFiles(r.fileCount);
  },
};

// ------------------------------------------------------------ групове перейменування

const Rename = {
  open(items) {
    const st = { mode: 'number', find: '', repl: '', regex: false, base: '', start: 1, digits: 2, prefix: '', suffix: '', kase: 'keep', keepExt: true };
    const prev = h('div', { class: 'mlist' });
    const btn = h('button', { class: 'btn primary', text: 'Перейменувати' });
    const opts = h('div', { class: 'form' });
    const pg = UI.page({ title: 'Перейменувати', sub: nItems(items.length), body: h('div', null, h('div', { class: 'form', style: { paddingTop: '6px' } },
      seg([{ label: 'Нумерація', value: 'number' }, { label: 'Заміна', value: 'replace' }, { label: 'Додати', value: 'affix' }, { label: 'Регістр', value: 'case' }], st.mode, v => { st.mode = v; build(); upd(); })),
    opts, h('div', { class: 'sgroup', text: 'Попередній перегляд' }), prev) });
    pg.el.appendChild(h('div', { class: 'pfoot' }, btn));
    const inp = (key, ph, type) => {
      const i = h('input', { class: 'field', value: st[key], placeholder: ph || '', type: type || 'text', autocapitalize: 'off', spellcheck: 'false' });
      i.addEventListener('input', () => { st[key] = type === 'number' ? (+i.value || 0) : i.value; upd(); });
      return i;
    };
    function build() {
      opts.innerHTML = '';
      if (st.mode === 'number') {
        opts.append(field('Нова назва (# — номер)', inp('base', 'Фото #')), h('div', { style: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px' } },
          field('Почати з', inp('start', '1', 'number')), field('Цифр у номері', inp('digits', '2', 'number'))));
      } else if (st.mode === 'replace') {
        opts.append(field('Знайти', inp('find', 'текст')), field('Замінити на', inp('repl', '')),
          toggle('Регулярний вираз', 'Наприклад, \\d+ — будь-яке число', st.regex, v => { st.regex = v; upd(); }));
      } else if (st.mode === 'affix') {
        opts.append(field('На початок', inp('prefix', '2026-')), field('У кінець (перед розширенням)', inp('suffix', '_копія')));
      } else {
        opts.append(seg([{ label: 'малі', value: 'lower' }, { label: 'ВЕЛИКІ', value: 'upper' }, { label: 'Як Речення', value: 'title' }], st.kase, v => { st.kase = v; upd(); }));
      }
      opts.append(toggle('Не змінювати розширення', null, st.keepExt, v => { st.keepExt = v; upd(); }));
    }
    function newName(it, i) {
      const [b, e] = st.keepExt && !it.dir ? splitName(it.name) : [it.name, ''];
      let n = b;
      if (st.mode === 'number') {
        const num = String(st.start + i).padStart(Math.max(1, Math.min(8, st.digits)), '0');
        const tpl = st.base || '#';
        n = tpl.includes('#') ? tpl.replace(/#+/, num) : tpl + ' ' + num;
      } else if (st.mode === 'replace') {
        if (st.find) {
          if (st.regex) { try { n = b.replace(new RegExp(st.find, 'g'), st.repl); } catch (x) { n = b; } }
          else n = b.split(st.find).join(st.repl);
        }
      } else if (st.mode === 'affix') {
        n = st.prefix + b + st.suffix;
      } else {
        n = st.kase === 'lower' ? b.toLowerCase() : st.kase === 'upper' ? b.toUpperCase() : b.charAt(0).toUpperCase() + b.slice(1).toLowerCase();
      }
      return n + e;
    }
    let pairs = [];
    function upd() {
      prev.innerHTML = '';
      pairs = [];
      const seen = new Set();
      let bad = 0;
      items.forEach((it, i) => {
        const nn = newName(it, i);
        const dup = seen.has(nn.toLowerCase()) || !nn.trim() || /[\\/:*?"<>|]/.test(nn);
        seen.add(nn.toLowerCase());
        if (dup) bad++;
        if (nn !== it.name) pairs.push([it.path, nn]);
        if (i < 300) prev.appendChild(h('div', { class: 'mitem' }, h('span', { class: 'mlabel' }, h('span', { text: nn, style: dup ? { color: 'var(--danger)' } : null }), h('small', { text: it.name }))));
      });
      btn.disabled = !pairs.length || bad > 0;
      btn.textContent = bad ? 'Повтори або недопустимі назви: ' + bad : 'Перейменувати' + (pairs.length ? ' (' + pairs.length + ')' : '');
    }
    btn.onclick = async () => {
      pg.close();
      Browser.clearSel();
      const r = await Tasks.run('renameMany', { pairs });
      if (r.state === 'done') UI.toast('Перейменовано: ' + r.renamed);
    };
    build();
    upd();
  },
};
