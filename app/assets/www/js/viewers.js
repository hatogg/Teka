'use strict';
/* Відкриття файлів і вбудовані переглядачі: світлини, відео, музика, текст, PDF, APK. */

const Open = {
  item(it, siblings) {
    if (it.dir) return Browser.go(it.path);
    siblings = siblings || [it];
    const k = Kind.of(it);
    switch (k) {
      case 'image': return Viewer.gallery(siblings.filter(x => Kind.of(x) === 'image'), it);
      case 'video': return Viewer.video(it);
      case 'audio': return Viewer.audio(siblings.filter(x => Kind.of(x) === 'audio'), it);
      case 'pdf': return Viewer.pdf(it);
      case 'text': return Viewer.text(it);
      case 'apk': return ApkView.open(it);
      case 'archive': return ArchiveView.open(it);
      case 'doc': return Native.open(it.path);
      default:
        UI.menu([
          { icon: 'external', label: 'Відкрити в іншому застосунку', run: () => Native.open(it.path, '', true) },
          { icon: 'type', label: 'Відкрити як текст', run: () => Viewer.text(it) },
          Kind.archive(it) ? { icon: 'archive', label: 'Відкрити як архів', run: () => ArchiveView.open(it) } : null,
          { icon: 'info', label: 'Властивості', run: () => Props.open([it]) },
        ], it.name);
    }
  },
};

const Viewer = {
  // ---------------------------------------------------------- світлини

  gallery(list, start) {
    let idx = Math.max(0, list.findIndex(x => x.path === start.path));
    const gal = h('div', { class: 'gal' });
    const strip = h('div', { class: 'strip' });
    gal.appendChild(strip);
    const counter = () => (idx + 1) + ' з ' + list.length;
    const pg = UI.page({ title: list[idx].name, sub: counter(), cls: 'dark-page', body: gal,
      actions: [{ icon: 'info', label: 'Властивості', run: () => Props.open([list[idx]]) }] });
    const bar = h('div', { class: 'galbar' },
      h('button', { class: 'ib', 'aria-label': 'Поділитися', html: ic('share'), onclick: () => Native.share([list[idx].path]) }),
      h('button', { class: 'ib', 'aria-label': 'Конвертувати', html: ic('transform'), onclick: () => Convert.sheet([list[idx]]) }),
      h('button', { class: 'ib', 'aria-label': 'Відкрити в іншому застосунку', html: ic('external'), onclick: () => Native.open(list[idx].path, '', true) }),
      h('button', { class: 'ib', 'aria-label': 'Видалити', html: ic('trash'), onclick: async () => {
        const it = list[idx];
        await Ops.remove([it]);
        if (Native.exists(it.path)) return;
        list.splice(idx, 1);
        if (!list.length) return pg.close();
        idx = Math.min(idx, list.length - 1);
        show();
      } }));
    pg.el.appendChild(bar);
    const src = it => /\.(heic|heif|tiff?|dng)$/i.test(it.name) ? Native.thumbUrl(it.path, 1024, it.mtime) : Native.fileUrl(it.path, it.mtime);
    let W = 0, z = { s: 1, x: 0, y: 0 }, cur = null;
    function slide(i) {
      const s = h('div', { class: 'slide' });
      if (i < 0 || i >= list.length) return s;
      const img = h('img', { alt: '', draggable: 'false' });
      const sp = h('span', { class: 'spin' });
      img.onload = () => sp.remove();
      img.onerror = () => { sp.remove(); s.appendChild(h('div', { class: 'empty', style: { color: '#bbb' }, text: 'Не вдається показати це зображення' })); };
      img.src = src(list[i]);
      s.append(sp, img);
      return s;
    }
    function show() {
      W = gal.clientWidth || window.innerWidth;
      strip.innerHTML = '';
      strip.append(slide(idx - 1), slide(idx), slide(idx + 1));
      strip.style.transition = 'none';
      strip.style.transform = 'translateX(' + (-W) + 'px)';
      cur = strip.children[1].querySelector('img');
      z = { s: 1, x: 0, y: 0 };
      pg.setTitle(list[idx].name, counter());
    }
    function apply() { if (cur) cur.style.transform = 'translate(' + z.x + 'px,' + z.y + 'px) scale(' + z.s + ')'; }
    function clampZ() {
      if (!cur) return;
      const r = cur.parentNode.getBoundingClientRect();
      const iw = cur.offsetWidth * z.s, ih = cur.offsetHeight * z.s;
      const ox = cur.offsetLeft, oy = cur.offsetTop;
      const minX = Math.min(-ox, r.width - ox - iw), maxX = Math.max(-ox, r.width - ox - iw);
      const minY = Math.min(-oy, r.height - oy - ih), maxY = Math.max(-oy, r.height - oy - ih);
      if (iw <= r.width) z.x = (r.width - iw) / 2 - ox; else z.x = clamp(z.x, minX, maxX);
      if (ih <= r.height) z.y = (r.height - ih) / 2 - oy; else z.y = clamp(z.y, minY, maxY);
    }
    function zoomAt(cx, cy, ns) {
      if (!cur) return;
      const r = cur.parentNode.getBoundingClientRect();
      const px = cx - r.left - cur.offsetLeft, py = cy - r.top - cur.offsetTop;
      ns = clamp(ns, 1, 8);
      z.x = px - (px - z.x) * ns / z.s;
      z.y = py - (py - z.y) * ns / z.s;
      z.s = ns;
      if (z.s <= 1.01) z = { s: 1, x: 0, y: 0 };
      else clampZ();
      apply();
    }
    let t0 = null, lastTap = 0, moved = false;
    gal.addEventListener('touchstart', e => {
      moved = false;
      if (e.touches.length === 2) {
        const [a, b] = e.touches;
        t0 = { pinch: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), s: z.s, cx: (a.clientX + b.clientX) / 2, cy: (a.clientY + b.clientY) / 2 };
      } else if (e.touches.length === 1) {
        const t = e.touches[0];
        t0 = { x: t.clientX, y: t.clientY, zx: z.x, zy: z.y, time: Date.now() };
      }
    }, { passive: true });
    gal.addEventListener('touchmove', e => {
      if (!t0) return;
      moved = true;
      if (e.touches.length === 2 && t0.pinch) {
        const [a, b] = e.touches;
        const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
        zoomAt(t0.cx, t0.cy, t0.s * d / t0.pinch);
        return;
      }
      if (e.touches.length !== 1 || t0.pinch) return;
      const t = e.touches[0], dx = t.clientX - t0.x, dy = t.clientY - t0.y;
      if (z.s > 1) { z.x = t0.zx + dx; z.y = t0.zy + dy; clampZ(); apply(); return; }
      strip.style.transition = 'none';
      strip.style.transform = 'translateX(' + (-W + dx) + 'px)';
    }, { passive: true });
    gal.addEventListener('touchend', e => {
      if (!t0 || e.touches.length) { if (e.touches.length === 1 && t0 && t0.pinch) { const t = e.touches[0]; t0 = { x: t.clientX, y: t.clientY, zx: z.x, zy: z.y, time: Date.now() }; } return; }
      const t = e.changedTouches[0];
      const dx = t.clientX - (t0.x || 0);
      if (!moved && !t0.pinch) {
        const now = Date.now();
        if (now - lastTap < 300) { zoomAt(t.clientX, t.clientY, z.s > 1 ? 1 : 2.5); lastTap = 0; }
        else { lastTap = now; setTimeout(() => { if (lastTap === now) pg.el.classList.toggle('chrome-off'); }, 300); }
      } else if (z.s <= 1 && !t0.pinch) {
        let dir = 0;
        if (dx < -W * 0.22 && idx < list.length - 1) dir = 1;
        if (dx > W * 0.22 && idx > 0) dir = -1;
        strip.style.transition = 'transform .2s ease-out';
        strip.style.transform = 'translateX(' + (-W - dir * W) + 'px)';
        if (dir) setTimeout(() => { idx += dir; show(); }, 200);
      }
      t0 = null;
    });
    gal.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.clientX, e.clientY, z.s * (e.deltaY < 0 ? 1.2 : 1 / 1.2)); }, { passive: false });
    document.addEventListener('keydown', onKey);
    function onKey(e) {
      if (!pg.el.isConnected) return document.removeEventListener('keydown', onKey);
      if (e.key === 'ArrowRight' && idx < list.length - 1) { idx++; show(); }
      if (e.key === 'ArrowLeft' && idx > 0) { idx--; show(); }
    }
    requestAnimationFrame(show);
  },

  // ---------------------------------------------------------- відео

  video(it) {
    const v = h('video', { controls: true, autoplay: true, playsinline: true, preload: 'metadata' });
    v.src = Native.fileUrl(it.path, it.mtime);
    const wrap = h('div', { class: 'vwrap' }, v);
    const pg = UI.page({ title: it.name, cls: 'dark-page', body: wrap, onclose: () => { v.pause(); v.removeAttribute('src'); v.load(); },
      actions: [
        { icon: 'music', label: 'Витягти звук', run: () => Convert.sheet([it], 'audio') },
        { icon: 'external', label: 'Відкрити в іншому застосунку', run: () => Native.open(it.path, '', true) },
      ] });
    pg.el.querySelector('.phead').style.position = 'static';
    v.addEventListener('error', () => {
      wrap.innerHTML = '';
      wrap.appendChild(h('div', { class: 'empty', style: { color: '#ccc' } }, h('b', { style: { color: '#fff' }, text: 'Формат не підтримується вбудованим програвачем' }),
        h('div', { style: { marginTop: '14px' } }, h('button', { class: 'btn primary', text: 'Відкрити в іншому застосунку', onclick: () => Native.open(it.path, '', true) }))));
    });
  },

  // ---------------------------------------------------------- музика

  audio(list, start) {
    let idx = Math.max(0, list.findIndex(x => x.path === start.path));
    const a = new Audio();
    a.preload = 'metadata';
    const cover = h('div', { class: 'cover' });
    const title = h('b'), sub = h('small');
    const range = h('input', { type: 'range', min: 0, max: 1000, value: 0 });
    const tCur = h('span', { text: '0:00' }), tDur = h('span', { text: '0:00' });
    const playBtn = h('button', { class: 'ib big', 'aria-label': 'Відтворення', html: ic('pause'), onclick: () => a.paused ? a.play() : a.pause() });
    const body = h('div', { class: 'player' }, cover, h('div', { class: 'ptrack' }, title, sub),
      h('div', { class: 'seek' }, range, h('div', { class: 'times' }, tCur, tDur)),
      h('div', { class: 'pctl' },
        h('button', { class: 'ib', 'aria-label': 'Попередній', html: ic('prev'), onclick: () => go(idx - 1) }),
        playBtn,
        h('button', { class: 'ib', 'aria-label': 'Наступний', html: ic('next'), onclick: () => go(idx + 1) })));
    const pg = UI.page({ title: 'Музика', body, onclose: () => { a.pause(); a.src = ''; Native.keepScreen(false); },
      actions: [{ icon: 'transform', label: 'Конвертувати', run: () => Convert.sheet([list[idx]]) }, { icon: 'info', label: 'Властивості', run: () => Props.open([list[idx]]) }] });
    let seeking = false;
    range.addEventListener('input', () => { seeking = true; if (a.duration) tCur.textContent = fmtDur(range.value / 1000 * a.duration * 1000); });
    range.addEventListener('change', () => { if (a.duration) a.currentTime = range.value / 1000 * a.duration; seeking = false; });
    a.addEventListener('timeupdate', () => {
      if (seeking || !a.duration) return;
      range.value = Math.round(a.currentTime / a.duration * 1000);
      tCur.textContent = fmtDur(a.currentTime * 1000);
    });
    a.addEventListener('loadedmetadata', () => { tDur.textContent = fmtDur(a.duration * 1000); });
    a.addEventListener('play', () => { playBtn.innerHTML = ic('pause'); });
    a.addEventListener('pause', () => { playBtn.innerHTML = ic('play'); });
    a.addEventListener('ended', () => { if (idx < list.length - 1) go(idx + 1); });
    a.addEventListener('error', () => { sub.textContent = 'Формат не підтримується — спробуйте конвертувати в M4A'; });
    function go(i) {
      if (i < 0 || i >= list.length) return;
      idx = i;
      const it = list[i];
      title.textContent = splitName(it.name)[0];
      sub.textContent = (i + 1) + ' з ' + list.length + ' · ' + fmtSize(it.size);
      cover.innerHTML = ic('music');
      const img = h('img', { alt: '' });
      img.onload = () => { cover.innerHTML = ''; cover.appendChild(img); };
      img.src = Native.thumbUrl(it.path, 512, it.mtime);
      a.src = Native.fileUrl(it.path, it.mtime);
      a.play().catch(() => {});
      range.value = 0;
      tCur.textContent = '0:00';
    }
    go(idx);
  },

  // ---------------------------------------------------------- текст

  async text(it, edit) {
    let enc = 'auto', wrap = true, data = null, editing = false, dirty = false;
    const box = h('div', { class: 'pbody' });
    const info = h('div', { class: 'tbarinfo' });
    const pg = UI.page({ title: it.name, body: h('div', { style: { display: 'flex', flexDirection: 'column', flex: '1', minHeight: '0' } }, info, box), actions: [
      { icon: 'pencil', label: 'Редагувати', run: () => startEdit() },
      { icon: 'dots', label: 'Ще', run: () => more() },
    ], onclose: () => {} });
    pg.el.querySelector('.pbody').style.overflow = 'hidden';
    async function load() {
      box.innerHTML = '';
      box.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Читання…'));
      data = await Native.call('readText', { path: it.path, enc, force: enc !== 'auto' });
      box.innerHTML = '';
      if (!data.ok) { box.appendChild(h('div', { class: 'empty', text: data.err || 'Не вдалося прочитати файл' })); return; }
      if (data.binary && enc === 'auto') {
        box.appendChild(h('div', { class: 'empty' }, h('b', { text: 'Схоже, це не текстовий файл' }), 'Можна все одно показати його як текст або відкрити в іншому застосунку.',
          h('div', { style: { marginTop: '14px', display: 'flex', gap: '8px', justifyContent: 'center' } },
            h('button', { class: 'btn tonal', text: 'Показати як текст', onclick: () => { enc = 'utf-8'; load(); } }),
            h('button', { class: 'btn primary', text: 'Відкрити в…', onclick: () => Native.open(it.path, '', true) }))));
        return;
      }
      renderInfo();
      if (edit) { edit = false; startEdit(); return; }
      const pre = h('pre', { class: 'textv' + (wrap ? '' : ' nowrap') });
      pre.textContent = data.text;
      box.appendChild(pre);
    }
    function encName(id) { const e = (App.info.encodings || []).find(x => x[0] === id); return e ? e[1].replace(/ \(.*/, '') : id.toUpperCase(); }
    function renderInfo() {
      info.innerHTML = '';
      info.append(
        h('button', { class: 'chip', onclick: chooseEnc }, h('span', { html: ic('enc') }), encName(data.enc) + (data.bom ? ' BOM' : '')),
        h('span', { class: 'chip' }, data.eol === 'CRLF' ? 'CRLF (Windows)' : data.eol === 'CR' ? 'CR' : 'LF'),
        h('span', { class: 'chip' }, fmtSize(data.size)),
        data.truncated ? h('span', { class: 'chip on' }, 'Показано перші 4 МБ') : null);
    }
    async function chooseEnc() {
      const v = await UI.choose('Кодування', [{ label: 'Визначити автоматично', value: 'auto', checked: enc === 'auto' }, '-']
        .concat((App.info.encodings || []).map(e => ({ label: e[1], value: e[0], checked: data && data.enc === e[0] && enc !== 'auto' }))), { search: 'Знайти кодування' });
      if (v && !dirty) { enc = v; load(); }
      else if (v && dirty) UI.toast('Спершу збережіть або скасуйте зміни');
    }
    function startEdit() {
      if (!data || !data.ok || editing) return;
      if (data.truncated) return UI.toast('Файл завеликий для редагування (понад 4 МБ)', { error: true });
      editing = true;
      box.innerHTML = '';
      const ta = h('textarea', { class: 'texted', spellcheck: 'false', autocapitalize: 'off', autocomplete: 'off' });
      ta.value = data.text;
      ta.addEventListener('input', () => { dirty = true; });
      box.appendChild(ta);
      pg.acts.innerHTML = '';
      pg.acts.appendChild(h('button', { class: 'btn primary sm', style: { marginRight: '8px' }, html: ic('save') + 'Зберегти', onclick: async () => {
        const cnt = await Native.call('unmappable', { text: ta.value, enc: data.enc });
        if (cnt.ok && cnt.count > 0) {
          const ok = await UI.confirm('Символи будуть втрачені', cnt.count + ' символ(ів) не вміщаються в кодування ' + encName(data.enc) + ' і стануть «?». Зберегти в UTF-8 натомість?', 'Зберегти в UTF-8');
          if (!ok) return;
          data.enc = 'utf-8';
        }
        const r = await Native.call('writeText', { path: it.path, text: ta.value, enc: data.enc, bom: data.bom, eol: data.eol });
        if (!r.ok) return UI.toast(r.err || 'Не вдалося зберегти', { error: true });
        dirty = false;
        data.text = ta.value;
        data.size = r.size;
        renderInfo();
        UI.toast('Збережено');
        App.refresh();
      } }));
      setTimeout(() => ta.focus(), 50);
    }
    function more() {
      UI.menu([
        { icon: 'wrap', label: 'Перенесення рядків', checked: wrap, run: () => { wrap = !wrap; const p = box.querySelector('.textv'); if (p) p.classList.toggle('nowrap', !wrap); } },
        { icon: 'enc', label: 'Кодування…', run: chooseEnc },
        { icon: 'transform', label: 'Конвертувати…', sub: 'У PDF або інше кодування', run: () => Convert.sheet([it]) },
        { icon: 'external', label: 'Відкрити в іншому застосунку', run: () => Native.open(it.path, 'text/plain', true) },
        { icon: 'share', label: 'Поділитися', run: () => Native.share([it.path]) },
      ]);
    }
    const close0 = pg.close;
    const entry = UI.stack[UI.stack.length - 1];
    entry.close = async () => {
      if (dirty && !(await UI.confirm('Вийти без збереження?', 'Зміни буде втрачено.', 'Вийти', true))) return;
      close0();
    };
    pg.head.querySelector('.ib').replaceWith(h('button', { class: 'ib', html: ic('arrowLeft'), 'aria-label': 'Назад', onclick: () => entry.close() }));
    load();
  },

  // ---------------------------------------------------------- PDF

  async pdf(it) {
    const pages = h('div', { class: 'pdfpages' });
    const num = h('div', { class: 'pdfnum', text: '' });
    const pg = UI.page({ title: it.name, body: pages, actions: [
      { icon: 'image', label: 'У зображення', run: () => Convert.sheet([it], 'pdf2img') },
      { icon: 'external', label: 'Відкрити в іншому застосунку', run: () => Native.open(it.path, 'application/pdf', true) },
    ] });
    pg.el.appendChild(num);
    pages.appendChild(h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Відкриття…'));
    const r = await Native.call('pdfInfo', { path: it.path });
    pages.innerHTML = '';
    if (!r.ok) {
      pages.appendChild(h('div', { class: 'empty' }, h('b', { text: r.err || 'Не вдалося відкрити PDF' }),
        h('div', { style: { marginTop: '14px' } }, h('button', { class: 'btn primary', text: 'Відкрити в іншому застосунку', onclick: () => Native.open(it.path, 'application/pdf', true) }))));
      return;
    }
    const ratio = r.h && r.w ? r.h / r.w : 1.414;
    const width = Math.min(1600, Math.round(Math.min(window.innerWidth, 900) * (window.devicePixelRatio || 1)));
    const io = new IntersectionObserver(es => {
      for (const e of es) {
        if (!e.isIntersecting) continue;
        const d = e.target;
        io.unobserve(d);
        const img = h('img', { alt: '', loading: 'lazy' });
        img.onload = () => { d.style.aspectRatio = img.naturalWidth + ' / ' + img.naturalHeight; };
        img.src = Native.pdfUrl(it.path, +d.dataset.n, width, it.mtime);
        d.prepend(img);
      }
    }, { root: pg.body, rootMargin: '1200px' });
    for (let n = 0; n < r.pages; n++) {
      const d = h('div', { class: 'pdfpage', 'data-n': n, style: { aspectRatio: '1 / ' + ratio } }, h('span', { class: 'pn', text: n + 1 }));
      pages.appendChild(d);
      io.observe(d);
    }
    let hide = 0;
    pg.body.addEventListener('scroll', () => {
      const ch = pages.children;
      const mid = pg.body.scrollTop + pg.body.clientHeight / 2;
      let cur = 0;
      for (let i = 0; i < ch.length; i++) { if (ch[i].offsetTop <= mid) cur = i; else break; }
      num.textContent = (cur + 1) + ' / ' + r.pages;
      num.hidden = false;
      clearTimeout(hide);
      hide = setTimeout(() => { num.hidden = true; }, 1200);
    }, { passive: true });
    num.textContent = '1 / ' + r.pages;
    hide = setTimeout(() => { num.hidden = true; }, 1500);
  },
};

// ------------------------------------------------------------ APK

const ApkView = {
  async open(it) {
    const body = h('div', null, h('div', { class: 'loading' }, h('span', { class: 'spin' }), 'Читання APK…'));
    const s = UI.sheet({ title: '', body });
    const r = await Native.call('apkInfo', { path: it.path });
    body.innerHTML = '';
    if (!r.ok) {
      body.appendChild(h('div', { class: 'empty', text: r.err || 'Не вдалося прочитати APK' }));
      return;
    }
    const icon = h('div', { class: 'appic' }, h('img', { alt: '', src: Native.thumbUrl(it.path, 192, it.mtime) }));
    const st = { new: ['new', 'Не встановлено'], newer: ['new', 'Оновлення: встановлено ' + r.installedVersion], older: ['warn', 'Встановлено новішу версію ' + r.installedVersion], same: ['ok', 'Ця версія вже встановлена'] }[r.status] || ['', ''];
    const sdk = n => n ? 'Android ' + ({ 21: '5', 22: '5.1', 23: '6', 24: '7', 25: '7.1', 26: '8', 27: '8.1', 28: '9', 29: '10', 30: '11', 31: '12', 32: '12L', 33: '13', 34: '14', 35: '15', 36: '16' }[n] || 'API ' + n) + ' (API ' + n + ')' : '';
    const kv = h('dl', { class: 'kv', style: { margin: '0 18px' } });
    const add = (k, v) => { if (v) kv.append(h('dt', { text: k }), h('dd', { text: v })); };
    add('Версія', r.version + ' (' + r.code + ')');
    add('Розмір', fmtSize(it.size));
    if (r.bundle) add('Частин у наборі', r.parts);
    add('Мінімум', sdk(r.min));
    add('Ціль', sdk(r.target));
    add('Дозволи', r.perms.length ? String(r.perms.length) : 'немає');
    const perms = h('div', { class: 'perms', hidden: true, text: r.perms.map(p => p.replace(/^android\.permission\./, '')).join('\n') });
    body.append(
      h('div', { class: 'apphero' }, icon, h('b', { text: r.label }), h('small', { text: r.pkg }), st[1] ? h('span', { class: 'status ' + st[0], text: st[1] }) : null),
      kv,
      r.perms.length ? h('div', { style: { padding: '6px 12px 0' } }, h('button', { class: 'btn sm', text: 'Показати дозволи', onclick: e => { perms.hidden = !perms.hidden; e.currentTarget.textContent = perms.hidden ? 'Показати дозволи' : 'Сховати дозволи'; } })) : null,
      perms,
      h('div', { class: 'fbtns', style: { marginTop: '12px' } },
        h('button', { class: 'btn tonal', text: 'Як архів', onclick: () => { s.close(); ArchiveView.open(it); } }),
        h('button', { class: 'btn primary', text: r.status === 'newer' ? 'Оновити' : r.status === 'same' ? 'Перевстановити' : 'Встановити', onclick: () => { s.close(); Native.install(it.path); } })));
  },
};
