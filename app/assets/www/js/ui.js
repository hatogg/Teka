'use strict';
/* «Тека». Загальні елементи інтерфейсу: DOM-помічники, сповіщення, аркуші, діалоги, сторінки. */

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const k in attrs) {
    const v = attrs[k];
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'style' && typeof v === 'object') { for (const p in v) { if (p.startsWith('--')) el.style.setProperty(p, v[p]); else el.style[p] = v[p]; } }
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.appendChild(typeof kid === 'string' ? document.createTextNode(kid) : kid);
  }
  return el;
}

// append() без «null» і «false» у вигляді тексту — зручно для необов'язкових елементів
const _append = Element.prototype.append, _prepend = Element.prototype.prepend;
Element.prototype.append = function (...a) { return _append.apply(this, a.filter(x => x != null && x !== false)); };
Element.prototype.prepend = function (...a) { return _prepend.apply(this, a.filter(x => x != null && x !== false)); };

function debounce(fn, ms) {
  let t = 0;
  const f = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  f.flush = (...a) => { clearTimeout(t); fn(...a); };
  f.cancel = () => clearTimeout(t);
  return f;
}

const fmtNum = n => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

const UI = {
  stack: [],

  /** Шар поверх застосунку з кнопкою «Назад». */
  push(el, close) {
    const entry = { el, close };
    UI.stack.push(entry);
    $('#layers').appendChild(el);
    document.body.classList.add('layered');
    return entry;
  },

  pop(entry) {
    const i = UI.stack.indexOf(entry);
    if (i >= 0) UI.stack.splice(i, 1);
    document.body.classList.toggle('layered', UI.stack.length > 0);
  },

  /** Закрити верхній шар. Повертає true, якщо було що закривати. */
  back() {
    const top = UI.stack[UI.stack.length - 1];
    if (!top) return false;
    top.close();
    return true;
  },

  closeAll() {
    while (UI.stack.length) UI.back();
  },

  // ------------------------------------------------------------ сповіщення

  toast(msg, opts = {}) {
    const t = $('#toast');
    clearTimeout(UI._tt);
    t.innerHTML = '';
    t.appendChild(h('span', { class: 'msg', text: msg }));
    if (opts.action) {
      t.appendChild(h('button', {
        class: 'act', text: opts.action.label,
        onclick: () => { t.classList.remove('on'); opts.action.run(); },
      }));
    }
    t.classList.toggle('err', !!opts.error);
    t.classList.add('on');
    UI._tt = setTimeout(() => t.classList.remove('on'), opts.ms || (opts.action ? 5000 : 2200));
  },

  // ------------------------------------------------------------ аркуш знизу

  sheet({ title, body, cls, full, onclose, head }) {
    const box = h('div', { class: 'sheet' + (full ? ' full' : '') + (cls ? ' ' + cls : '') });
    const wrap = h('div', { class: 'layer sheetwrap' }, h('div', { class: 'scrim' }), box);
    if (title || head) {
      box.appendChild(h('div', { class: 'shead' },
        h('div', { class: 'grab' }),
        title ? h('div', { class: 'stitle', text: title }) : null,
        head || null));
    }
    const content = h('div', { class: 'sbody' });
    if (typeof body === 'string') content.innerHTML = body; else if (body) content.appendChild(body);
    box.appendChild(content);
    let entry;
    const close = () => {
      if (!wrap.isConnected) return;
      UI.pop(entry);
      wrap.classList.remove('on');
      setTimeout(() => wrap.remove(), 180);
      if (onclose) onclose();
    };
    entry = UI.push(wrap, close);
    wrap.querySelector('.scrim').addEventListener('click', close);
    UI.swipeToClose(box, close);
    requestAnimationFrame(() => wrap.classList.add('on'));
    return { el: box, body: content, close };
  },

  /** Потягнути аркуш за верхню частину вниз, щоб закрити. */
  swipeToClose(box, close) {
    let y0 = null, dy = 0;
    box.addEventListener('touchstart', e => {
      const head = e.target.closest('.shead');
      if (!head || e.touches.length > 1) { y0 = null; return; }
      y0 = e.touches[0].clientY; dy = 0;
    }, { passive: true });
    box.addEventListener('touchmove', e => {
      if (y0 == null) return;
      dy = Math.max(0, e.touches[0].clientY - y0);
      box.style.transform = 'translateY(' + dy + 'px)';
    }, { passive: true });
    box.addEventListener('touchend', () => {
      if (y0 == null) return;
      box.style.transform = '';
      if (dy > 80) close();
      y0 = null;
    });
  },

  /** Список дій у вигляді аркуша. items: [{icon,label,hint,run,danger,checked,disabled}] або '-' */
  menu(items, title) {
    const list = h('div', { class: 'mlist' });
    let s;
    for (const it of items) {
      if (!it) continue;
      if (it === '-') { list.appendChild(h('div', { class: 'msep' })); continue; }
      if (it.header) { list.appendChild(h('div', { class: 'mhead', text: it.header })); continue; }
      const row = h('button', {
        class: 'mitem' + (it.danger ? ' danger' : '') + (it.checked ? ' checked' : ''),
        disabled: it.disabled,
        onclick: () => { s.close(); setTimeout(() => it.run && it.run(), 10); },
      },
      h('span', { class: 'mic', html: it.icon ? ic(it.icon) : (it.glyph ? '<b class="glyph">' + esc(it.glyph) + '</b>' : '') }),
      h('span', { class: 'mlabel' }, h('span', { text: it.label }), it.sub ? h('small', { text: it.sub }) : null),
      it.hint ? h('span', { class: 'mhint', text: it.hint }) : null,
      it.checked != null ? h('span', { class: 'mcheck', html: it.checked ? ic('check') : '' }) : null);
      list.appendChild(row);
    }
    s = UI.sheet({ title, body: list });
    return s;
  },

  /** Вибір одного з варіантів. Повертає Promise зі значенням або null. */
  choose(title, options, opts = {}) {
    return new Promise(res => {
      let done = false;
      const finish = v => { if (!done) { done = true; res(v); } };
      const list = h('div', { class: 'mlist' });
      let filter = null;
      if (opts.search) {
        filter = h('input', { class: 'field', type: 'search', placeholder: opts.search, autocomplete: 'off', spellcheck: 'false' });
        filter.addEventListener('input', () => {
          const q = filter.value.trim().toLowerCase();
          for (const row of list.children) row.hidden = q && !row.dataset.k.includes(q);
        });
      }
      for (const o of options) {
        if (o === '-') { list.appendChild(h('div', { class: 'msep' })); continue; }
        if (o.header) { list.appendChild(h('div', { class: 'mhead', text: o.header })); continue; }
        const row = h('button', {
          class: 'mitem' + (o.checked ? ' checked' : ''),
          'data-k': (o.label + ' ' + (o.hint || '') + ' ' + (o.keys || '')).toLowerCase(),
          onclick: () => { finish(o.value); s.close(); },
        },
        h('span', { class: 'mic', html: o.icon ? ic(o.icon) : (o.glyph ? '<b class="glyph">' + esc(o.glyph) + '</b>' : '') }),
        h('span', { class: 'mlabel' }, h('span', { text: o.label }), o.sub ? h('small', { text: o.sub }) : null),
        o.hint ? h('span', { class: 'mhint', text: o.hint }) : null,
        h('span', { class: 'mcheck', html: o.checked ? ic('check') : '' }));
        list.appendChild(row);
      }
      const body = h('div', null, filter, list);
      const s = UI.sheet({ title, body, full: opts.full, onclose: () => finish(null) });
      if (filter && opts.focus !== false) setTimeout(() => filter.focus(), 120);
      const cur = list.querySelector('.checked');
      if (cur) setTimeout(() => cur.scrollIntoView({ block: 'center' }), 60);
    });
  },

  // ------------------------------------------------------------ діалоги

  dialog({ title, text, html, input, buttons, cls }) {
    return new Promise(res => {
      let done = false;
      const box = h('div', { class: 'dialog' + (cls ? ' ' + cls : '') });
      const wrap = h('div', { class: 'layer dialogwrap' }, h('div', { class: 'scrim' }), box);
      if (title) box.appendChild(h('div', { class: 'dtitle', text: title }));
      if (text) box.appendChild(h('div', { class: 'dtext', text }));
      if (html) box.appendChild(typeof html === 'string' ? h('div', { class: 'dtext', html }) : html);
      let field = null;
      if (input) {
        field = h(input.multiline ? 'textarea' : 'input', {
          class: 'field', type: input.type || 'text', value: input.value || '', placeholder: input.placeholder || '',
          autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', inputmode: input.inputmode || null,
        });
        if (input.multiline) field.value = input.value || '';
        box.appendChild(field);
        if (input.note) box.appendChild(h('div', { class: 'dnote', text: input.note }));
      }
      const row = h('div', { class: 'dbtns' });
      let entry;
      const close = v => {
        if (done) return;
        done = true;
        UI.pop(entry);
        wrap.classList.remove('on');
        setTimeout(() => wrap.remove(), 160);
        res(v);
      };
      for (const b of buttons || [{ label: 'Гаразд', value: true, primary: true }]) {
        row.appendChild(h('button', {
          class: 'btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : ''),
          text: b.label,
          onclick: () => close(b.value === '$input' ? (field ? field.value : '') : b.value),
        }));
      }
      box.appendChild(row);
      entry = UI.push(wrap, () => close(null));
      wrap.querySelector('.scrim').addEventListener('click', () => close(null));
      if (field) {
        field.addEventListener('keydown', e => {
          if (e.key === 'Enter' && !input.multiline) {
            e.preventDefault();
            const p = (buttons || []).find(b => b.primary);
            close(p ? (p.value === '$input' ? field.value : p.value) : field.value);
          }
        });
        setTimeout(() => {
          field.focus();
          if (input.select === 'name') {
            const v = field.value, dot = v.lastIndexOf('.');
            field.setSelectionRange(0, dot > 0 ? dot : v.length);
          } else if (input.select !== false) field.select();
        }, 80);
      }
      requestAnimationFrame(() => wrap.classList.add('on'));
    });
  },

  confirm(title, text, ok = 'Гаразд', danger = false) {
    return UI.dialog({
      title, text,
      buttons: [{ label: 'Скасувати', value: false }, { label: ok, value: true, primary: !danger, danger }],
    }).then(v => v === true);
  },

  prompt(title, value, opts = {}) {
    return UI.dialog({
      title, text: opts.text,
      input: { value, placeholder: opts.placeholder, select: opts.select, type: opts.type, inputmode: opts.inputmode, note: opts.note, multiline: opts.multiline },
      buttons: [{ label: 'Скасувати', value: null }, { label: opts.ok || 'Гаразд', value: '$input', primary: true }],
    });
  },

  alert(title, text) {
    return UI.dialog({ title, text, buttons: [{ label: 'Гаразд', value: true, primary: true }] });
  },

  // ------------------------------------------------------------ повноекранна сторінка

  page({ title, sub, body, actions, cls, onclose }) {
    const tt = h('div', { class: 'ptitle' });
    const setTitle = (t, s) => { tt.textContent = t; if (s) tt.appendChild(h('small', { text: s })); };
    setTitle(title, sub);
    const head = h('div', { class: 'phead' },
      h('button', { class: 'ib', html: ic('arrowLeft'), 'aria-label': 'Назад', onclick: () => close() }), tt);
    const acts = h('div', { class: 'pacts' });
    for (const a of actions || []) {
      acts.appendChild(h('button', { class: 'ib' + (a.cls ? ' ' + a.cls : ''), html: ic(a.icon), 'aria-label': a.label, title: a.label, onclick: a.run }));
    }
    head.appendChild(acts);
    const content = h('div', { class: 'pbody' });
    if (typeof body === 'string') content.innerHTML = body; else if (body) content.appendChild(body);
    const el = h('div', { class: 'layer page' + (cls ? ' ' + cls : '') }, head, content);
    let entry;
    const close = () => {
      if (!el.isConnected) return;
      UI.pop(entry);
      el.classList.remove('on');
      setTimeout(() => el.remove(), 180);
      if (onclose) onclose();
    };
    entry = UI.push(el, close);
    requestAnimationFrame(() => el.classList.add('on'));
    return { el, head, acts, body: content, close, setTitle };
  },
};
