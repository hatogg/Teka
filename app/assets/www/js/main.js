'use strict';
/* Запуск, вкладки, тема, кнопка «Назад», події від Android. */

const App = {
  cur: 'home', info: {},

  tab(name) {
    App.cur = name;
    for (const b of $$('#nav button')) b.classList.toggle('on', b.dataset.tab === name);
    for (const v of $$('.view')) v.classList.toggle('on', v.id === 'v' + name.charAt(0).toUpperCase() + name.slice(1));
    Browser.updateSel();
    if (name === 'files' && !Browser.path && Browser.vols[0]) Browser.go(Browser.vols[0].path, { push: false });
  },

  /** Оновити видимі списки після змін у файлах. */
  refresh: debounce(() => {
    Browser.vols = Native.volumes();
    if (Browser.path) Browser.refresh();
    if (App.cur === 'home') Home.render();
    Home.dirty = true;
  }, 120),

  back() {
    if (UI.back()) return true;
    if (App.cur === 'files' && Browser.back()) return true;
    if (Clip.mode && App.cur === 'files') { Clip.clear(); return true; }
    if (App.cur !== 'home') { App.tab('home'); return true; }
    return false;
  },

  theme() {
    const t = Settings.get('theme');
    const dark = t === 'dark' || (t === 'auto' && (Native.real ? App.info.night : matchMedia('(prefers-color-scheme: dark)').matches));
    document.documentElement.className = dark ? 'dark' : 'light';
    Native.bars(dark ? '#131418' : '#f7f6f2', !dark);
  },

  start() {
    Settings.load();
    App.info = Native.info();
    App.theme();
    Browser.vols = Native.volumes();
    Home.init();
    Browser.init();
    Tools.init();
    for (const b of $$('#nav button')) b.addEventListener('click', () => {
      if (b.dataset.tab === App.cur && b.dataset.tab === 'files') {
        const v = Browser.volumeOf(Browser.path);
        if (v && v.path !== Browser.path) return Browser.go(v.path);
      }
      App.tab(b.dataset.tab);
      if (b.dataset.tab === 'home' && Home.dirty) { Home.dirty = false; Home.render(); }
    });
    Home.render();
    Home.loadStats();
    const last = Settings.get('lastPath');
    const start = last && Browser.volumeOf(last) && Native.exists(last) ? last : (Browser.vols[0] && Browser.vols[0].path);
    if (start) Browser.go(start, { push: false, stay: true });
    App.tab('home');
    // старі файли з кошика прибираються самі
    setTimeout(() => { if (Native.perm() === 'full') Native.task('trashClean', { days: Settings.get('trashDays') }); }, 3000);
    App.handleIntent();
  },

  handleIntent() {
    const it = Native.intent();
    if (!it) return;
    if (it.type === 'pc') { UI.closeAll(); PcAccess.open(); return; }
    if (it.type === 'open' && it.path) {
      const item = Object.assign({ name: baseName(it.path), path: it.path, dir: false }, Native.stat(it.path));
      if (Kind.archive(item) || Kind.of(item) === 'archive') ArchiveView.open(item);
      else Open.item(item, [item]);
    }
  },
};

window.__teka = {
  onTask: (id, d) => Native._task(id, d),
  onResult: (id, d) => Native._result(id, d),
  onPc: st => { PcAccess.render(st); },
  onInstall: d => UI.toast(d.text, { error: !d.ok, ms: 3500 }),
  onPerm: p => window.__teka.resume(p),
  onNotif: () => { if (PcAccess.pendingStart) { PcAccess.pendingStart = false; Native.pcStart('', Settings.get('pcPort') || 8080); } },
  onIntent: () => App.handleIntent(),
  toast: m => UI.toast(m),
  resume(p) {
    const was = App.perm;
    App.perm = p;
    Browser.vols = Native.volumes();
    if (p !== was) {
      Home.render();
      Home.loadStats();
      if (Browser.path) Browser.load();
    } else {
      if (Browser.path && !UI.stack.length) Browser.refresh();
      if (App.cur === 'home') Home.loadRecent();
    }
    PcAccess.render(Native.pcStatus());
  },
  pause() {},
  config(night) { App.info.night = night; App.theme(); },
  back: () => (App.back() ? 'ok' : 'exit'),
};

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') App.back();
});

App.perm = Native.perm();
App.start();
