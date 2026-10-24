'use strict';
/* Конвертер: вибір перетворення, параметри, запуск завдання. */

const Convert = {
  MODES: {
    image: { icon: 'image', c: 'image', title: 'В інший формат зображення', sub: 'JPG, PNG, WEBP, BMP; зменшення розміру' },
    img2pdf: { icon: 'doc', c: 'pdf', title: 'Зображення → PDF', sub: 'Один документ, сторінка на кожне фото' },
    pdf2img: { icon: 'image', c: 'image', title: 'PDF → зображення', sub: 'Кожна сторінка — окремий JPG або PNG' },
    txt2pdf: { icon: 'doc', c: 'pdf', title: 'Текст → PDF', sub: 'Сторінки A4 з нумерацією' },
    text: { icon: 'enc', c: 'text', title: 'Змінити кодування тексту', sub: 'UTF-8, Windows-1251, CP866…; кінці рядків' },
    audio: { icon: 'music', c: 'audio', title: 'Аудіо → M4A або WAV', sub: 'AAC до 320 кбіт/с або WAV без втрат' },
    mute: { icon: 'video', c: 'video', title: 'Відео без звуку', sub: 'Без перекодування, швидко' },
  },

  modesFor(items) {
    if (!items.length || items.some(i => i.dir)) return [];
    const kinds = new Set(items.map(Kind.of));
    if (kinds.size !== 1) return [];
    const k = [...kinds][0];
    return {
      image: ['image', 'img2pdf'], pdf: ['pdf2img'], text: ['txt2pdf', 'text'],
      audio: ['audio'], video: ['audio', 'mute'],
    }[k] || [];
  },

  can: items => Convert.modesFor(items).length > 0,

  /** Для «Змінити розширення»: якщо це насправді конвертація — повертає налаштування для неї. */
  suggestFor(items, ext) {
    const modes = Convert.modesFor(items);
    if (modes.includes('image') && /^(jpe?g|png|webp|bmp)$/.test(ext) && items.some(i => extOf(i.name) !== ext)) return { mode: 'image', format: ext === 'jpeg' ? 'jpg' : ext };
    if (modes.includes('img2pdf') && ext === 'pdf') return { mode: 'img2pdf' };
    if (modes.includes('txt2pdf') && ext === 'pdf') return { mode: 'txt2pdf' };
    if (modes.includes('pdf2img') && /^(jpe?g|png)$/.test(ext)) return { mode: 'pdf2img', format: ext === 'png' ? 'png' : 'jpg' };
    if (modes.includes('audio') && /^(m4a|wav|aac)$/.test(ext)) return { mode: 'audio', format: ext === 'wav' ? 'wav' : 'm4a' };
    return null;
  },

  sheet(items, preset) {
    const modes = Convert.modesFor(items);
    if (!modes.length) return UI.toast('Для цих файлів немає перетворень. Виберіть файли одного типу: фото, PDF, текст, аудіо або відео.', { ms: 4000 });
    if (typeof preset === 'string') preset = { mode: preset };
    preset = preset || {};
    let mode = modes.includes(preset.mode) ? preset.mode : modes[0];
    const coll = new Intl.Collator('uk', { numeric: true, sensitivity: 'base' });
    items = items.slice().sort((a, b) => coll.compare(a.name, b.name));
    let dest = dirName(items[0].path);
    const o = {
      format: preset.format || 'jpg', quality: 90, size: 'orig', keepExif: true,
      page: 'a4', margin: 0, pdfq: 'original', name: items.length === 1 ? splitName(items[0].name)[0] : splitName(items[0].name)[0] + ' та ін.',
      pformat: preset.format === 'png' ? 'png' : 'jpg', dpi: 150, pages: '',
      fontSize: 11, mono: false,
      enc: 'utf-8', eol: 'keep', bom: false, inPlace: false,
      aformat: preset.format === 'wav' ? 'wav' : 'm4a', bitrate: 192,
    };
    if (mode === 'image' && !preset.format) {
      const e = extOf(items[0].name);
      o.format = e === 'jpg' || e === 'jpeg' ? 'png' : 'jpg';
    }
    const cards = h('div', { class: 'cvcards' });
    const form = h('div', { class: 'form' });
    const destVal = h('span', { class: 'sval', text: baseName(dest) });
    const destRow = h('button', { class: 'srow', style: { padding: '8px 2px', minHeight: '48px' }, onclick: async () => {
      const p = await Picker.folder(dest, 'Зберегти в…');
      if (p) { dest = p; destVal.textContent = baseName(p) || p; }
    } }, h('span', { class: 'sic', html: ic('folder') }), h('span', { class: 'slabel' }, h('span', { text: 'Зберегти в' })), destVal, h('span', { class: 'chev', html: ic('right') }));
    const go = h('button', { class: 'btn primary', text: 'Конвертувати', onclick: () => run() });
    const what = items.length === 1 ? '«' + items[0].name + '»' : nFiles(items.length);
    const s = UI.sheet({ title: 'Конвертувати ' + what, body: h('div', null, cards, form, h('div', { class: 'form', style: { paddingTop: '0' } }, destRow), h('div', { class: 'fbtns' }, go)) });

    function drawCards() {
      cards.innerHTML = '';
      if (modes.length < 2) return;
      for (const m of modes) {
        const M = Convert.MODES[m];
        cards.appendChild(h('button', { class: 'cvcard' + (m === mode ? ' on' : ''), onclick: () => { mode = m; drawCards(); drawForm(); } },
          h('span', { class: 'tic2', style: { '--c': 'var(--k-' + M.c + ')' }, html: ic(M.icon) }),
          h('span', null, h('b', { text: m === 'audio' && Kind.of(items[0]) === 'video' ? 'Витягти звук' : M.title }), h('small', { text: m === 'audio' && Kind.of(items[0]) === 'video' ? 'Звукова доріжка в M4A або WAV' : M.sub }))));
      }
    }
    function input(key, ph, type) {
      const i = h('input', { class: 'field', value: o[key], placeholder: ph || '', type: type || 'text', autocapitalize: 'off', spellcheck: 'false' });
      i.addEventListener('input', () => { o[key] = i.value; });
      return i;
    }
    function drawForm() {
      form.innerHTML = '';
      destRow.hidden = mode === 'text' && o.inPlace;
      if (mode === 'image') {
        const q = h('em', { text: o.quality + '%' });
        const qr = h('input', { type: 'range', min: 40, max: 100, value: o.quality });
        qr.addEventListener('input', () => { o.quality = +qr.value; q.textContent = qr.value + '%'; });
        const qf = field('Якість', qr, q);
        const ex = toggle('Зберегти EXIF', 'Дата зйомки, камера, геопозиція', o.keepExif, v => { o.keepExif = v; });
        const updQ = () => { qf.hidden = o.format === 'png' || o.format === 'bmp'; ex.hidden = o.format !== 'jpg'; };
        form.append(
          field('Формат', chips([{ label: 'JPG', value: 'jpg' }, { label: 'PNG', value: 'png' }, { label: 'WEBP', value: 'webp' }, { label: 'BMP', value: 'bmp' }], o.format, v => { o.format = v; updQ(); })),
          qf,
          field('Розмір', chips([{ label: 'Оригінал', value: 'orig' }, { label: '75%', value: 'p75' }, { label: '50%', value: 'p50' }, { label: '25%', value: 'p25' }, { label: 'до 1920 px', value: 'm1920' }, { label: 'до 1280 px', value: 'm1280' }], o.size, v => { o.size = v; })),
          ex);
        updQ();
      } else if (mode === 'img2pdf') {
        const names = items.slice(0, 5).map((x, i) => (i + 1) + '. ' + x.name).join('\n') + (items.length > 5 ? '\n…ще ' + (items.length - 5) : '');
        form.append(
          field('Назва PDF', input('name')),
          field('Сторінка', seg([{ label: 'A4', value: 'a4' }, { label: 'Letter', value: 'letter' }, { label: 'За фото', value: 'fit' }], o.page, v => { o.page = v; })),
          field('Поля', seg([{ label: 'Без', value: 0 }, { label: 'Вузькі', value: 18 }, { label: 'Звичайні', value: 36 }], o.margin, v => { o.margin = v; })),
          field('Якість', seg([{ label: 'Оригінал', value: 'original' }, { label: 'Висока', value: 'high' }, { label: 'Середня', value: 'medium' }, { label: 'Мала', value: 'low' }], o.pdfq, v => { o.pdfq = v; })),
          h('div', { class: 'note', style: { whiteSpace: 'pre-line' }, text: 'Порядок сторінок — за назвою:\n' + names }));
      } else if (mode === 'pdf2img') {
        form.append(
          field('Формат', seg([{ label: 'JPG', value: 'jpg' }, { label: 'PNG', value: 'png' }], o.pformat, v => { o.pformat = v; })),
          field('Якість (точок на дюйм)', seg([{ label: '72 · екран', value: 72 }, { label: '150 · звичайна', value: 150 }, { label: '300 · друк', value: 300 }], o.dpi, v => { o.dpi = v; })),
          field('Сторінки', input('pages', 'усі, або напр. 1-3, 5', 'text')));
      } else if (mode === 'txt2pdf') {
        form.append(
          field('Розмір шрифту', seg([{ label: '9 pt', value: 9 }, { label: '11 pt', value: 11 }, { label: '13 pt', value: 13 }], o.fontSize, v => { o.fontSize = v; })),
          toggle('Моноширинний шрифт', 'Для коду, таблиць і логів', o.mono, v => { o.mono = v; }));
      } else if (mode === 'text') {
        const sel = h('select', { class: 'field' });
        for (const e of App.info.encodings || [['utf-8', 'UTF-8']]) sel.appendChild(h('option', { value: e[0], text: e[1], selected: e[0] === o.enc }));
        sel.addEventListener('change', () => { o.enc = sel.value; });
        form.append(
          field('Нове кодування', sel),
          field('Кінці рядків', seg([{ label: 'Як є', value: 'keep' }, { label: 'LF (Linux)', value: 'LF' }, { label: 'CRLF (Windows)', value: 'CRLF' }], o.eol, v => { o.eol = v; })),
          toggle('Додати BOM', 'Мітка UTF на початку файлу — потрібна деяким програмам Windows', o.bom, v => { o.bom = v; }),
          toggle('Замінити оригінал', 'Інакше буде створено копію з назвою кодування', o.inPlace, v => { o.inPlace = v; destRow.hidden = v; }));
      } else if (mode === 'audio') {
        const br = field('Бітрейт', seg([{ label: '128', value: 128 }, { label: '192', value: 192 }, { label: '256', value: 256 }, { label: '320', value: 320 }], o.bitrate, v => { o.bitrate = v; }), h('em', { text: 'кбіт/с' }));
        form.append(
          field('Формат', seg([{ label: 'M4A (AAC)', value: 'm4a' }, { label: 'WAV', value: 'wav' }], o.aformat, v => { o.aformat = v; br.hidden = v === 'wav'; })),
          br,
          h('div', { class: 'note', text: 'M4A відтворюється на всіх телефонах і комп’ютерах. Якщо звук уже в AAC — його буде скопійовано без втрат. WAV — без стиснення, файл великий.' }));
        br.hidden = o.aformat === 'wav';
      } else if (mode === 'mute') {
        form.append(h('div', { class: 'note', text: 'Звукову доріжку буде прибрано, відео скопійовано без перекодування — якість не зміниться.' }));
      }
    }
    async function run() {
      const args = { mode, srcs: items.map(i => i.path), dest };
      if (mode === 'image') {
        Object.assign(args, { format: o.format, quality: o.quality, keepExif: o.keepExif });
        if (o.size.startsWith('p')) args.percent = +o.size.slice(1);
        if (o.size.startsWith('m')) args.maxSide = +o.size.slice(1);
      } else if (mode === 'img2pdf') {
        if (!o.name.trim()) return UI.toast('Вкажіть назву PDF');
        Object.assign(args, { name: o.name.trim(), page: o.page, margin: o.margin, quality: o.pdfq });
      } else if (mode === 'pdf2img') Object.assign(args, { format: o.pformat, dpi: o.dpi, pages: o.pages.trim() });
      else if (mode === 'txt2pdf') Object.assign(args, { fontSize: o.fontSize, mono: o.mono });
      else if (mode === 'text') Object.assign(args, { enc: o.enc, eol: o.eol, bom: o.bom, inPlace: o.inPlace });
      else if (mode === 'audio') Object.assign(args, { format: o.aformat, bitrate: o.bitrate });
      s.close();
      Browser.clearSel();
      const r = await Tasks.run('convert', args, { title: Convert.MODES[mode].title.replace(/^В інший формат зображення$/, 'Конвертація зображень') });
      if (r.state !== 'done') return;
      const errs = r.errors || [];
      const n = (r.paths || []).length;
      if (errs.length) {
        UI.dialog({ title: 'Готово з помилками', text: 'Створено: ' + n + '. Не вдалося: ' + errs.length + '\n\n' + errs.slice(0, 6).map(e => e.name + ' — ' + e.err).join('\n') });
      } else if (n) {
        const first = r.paths[0];
        UI.toast(n === 1 ? 'Створено «' + baseName(first) + '»' : 'Створено файлів: ' + n, { action: { label: 'Показати', run: () => Browser.go(dirName(first), { focus: first }) } });
      }
    }
    drawCards();
    drawForm();
  },

  /** Сторінка-конвертер з інструментів: вибір перетворення, потім файлів. */
  hub() {
    const list = h('div', { class: 'cvcards', style: { paddingTop: '6px' } });
    const pg = UI.page({ title: 'Конвертер', sub: 'Оригінали залишаються без змін', body: list });
    const entries = [
      ['image', ['image'], 'Фото → JPG / PNG / WEBP / BMP'],
      ['img2pdf', ['image'], null],
      ['pdf2img', ['pdf'], null],
      ['txt2pdf', ['text'], null],
      ['text', ['text'], null],
      ['audio', ['audio'], null],
      ['audio', ['video'], 'Звук із відео → M4A / WAV'],
      ['mute', ['video'], null],
    ];
    for (const [m, kinds, title] of entries) {
      const M = Convert.MODES[m];
      list.appendChild(h('button', { class: 'cvcard', onclick: async () => {
        const items = await Picker.files(kinds, title || M.title, m !== 'pdf2img' || true);
        if (items.length) Convert.sheet(items, m);
      } }, h('span', { class: 'tic2', style: { '--c': 'var(--k-' + (kinds[0] === 'video' ? 'video' : M.c) + ')' }, html: ic(kinds[0] === 'video' && m === 'audio' ? 'music' : M.icon) }),
      h('span', null, h('b', { text: title || M.title }), h('small', { text: m === 'audio' && kinds[0] === 'video' ? 'MP4, MKV, WEBM, 3GP' : M.sub }))));
    }
    list.appendChild(h('div', { class: 'note', style: { marginTop: '6px' }, text: 'Порада: щоб змінити лише розширення файлу, виберіть файл і натисніть «Змінити розширення». Якщо це справжня зміна формату (наприклад, PNG → JPG), «Тека» запропонує конвертувати.' }));
  },
};
