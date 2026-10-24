'use strict';
/* Обгортка над JS-мостом window.Android. У звичайному браузері — імітація з mock.js. */

const Native = (() => {
  const real = !!(window.Android && typeof window.Android.hello === 'function');
  const A = real ? window.Android : makeMock();
  const T = A.hello() || '';
  const j = (s, d) => { try { return s ? JSON.parse(s) : d; } catch (e) { return d; } };
  const waits = {}, tasks = {};
  let seq = 0;
  const q = encodeURIComponent;

  const N = {
    real,
    info: () => j(A.info(T), {}),
    perm: () => A.perm(T),
    requestPerm: () => A.requestPerm(T),
    needNotif: () => !!A.needNotif(T),
    requestNotif: () => A.requestNotif(T),

    volumes: () => j(A.volumes(T), []),
    list: (path, hidden) => j(A.list(T, path, !!hidden), { ok: false, err: 'bridge' }),
    stat: path => j(A.stat(T, path), {}),
    mkdir: (parent, name) => j(A.mkdir(T, parent, name), { ok: false }),
    mkfile: (parent, name) => j(A.mkfile(T, parent, name), { ok: false }),
    rename: (path, name) => j(A.rename(T, path, name), { ok: false }),
    conflicts: (srcs, dest) => j(A.conflicts(T, JSON.stringify(srcs), dest), []),
    exists: path => A.exists(T, path) === 'true',

    /** Запит у фоні. Повертає Promise з відповіддю (об'єкт з ok/err). */
    call(name, args) {
      return new Promise(res => {
        const id = 'c' + (++seq);
        waits[id] = res;
        A.call(T, id, name, JSON.stringify(args || {}));
      });
    },
    _result(id, data) {
      const f = waits[id];
      delete waits[id];
      if (f) f(data || { ok: false });
    },

    /**
     * Довге завдання. onProgress(d) отримує проміжні стани; Promise — підсумок
     * ({state: done|error|cancelled, ...}).
     */
    task(kind, args, onProgress) {
      const id = 't' + Date.now().toString(36) + (++seq);
      const p = new Promise(res => { tasks[id] = { res, onProgress }; });
      A.task(T, id, kind, JSON.stringify(args || {}));
      return { id, promise: p, cancel: () => A.cancel(T, id) };
    },
    _task(id, d) {
      const t = tasks[id];
      if (!t || !d) return;
      if (d.state === 'run') { if (t.onProgress) t.onProgress(d); return; }
      delete tasks[id];
      t.res(d);
    },
    cancel: id => A.cancel(T, id),

    open: (path, mime, chooser) => A.open(T, path, mime || '', !!chooser),
    share: paths => A.share(T, JSON.stringify(paths)),
    install: path => A.install(T, path),
    appAction: (pkg, action) => A.appAction(T, pkg, action),

    pcStart: (pin, port) => A.pcStart(T, pin || '', port || 8080),
    pcStop: () => A.pcStop(T),
    pcStatus: () => j(A.pcStatus(T), { on: false }),

    kvPut: (k, v) => A.kvPut(T, k, typeof v === 'string' ? v : JSON.stringify(v)),
    kvGet: (k, d) => j(A.kvGet(T, k), d),
    clip: text => A.clip(T, text),
    intent: () => j(A.intent(T), null),
    bars: (color, light) => A.bars(T, color, !!light),
    keepScreen: on => A.keepScreen(T, !!on),
    debug: on => A.debug(T, !!on),
    exit: () => A.exit(T),

    // адреси віртуального сайту для <img>, <video>, <audio>
    fileUrl: (path, mtime) => A.__url ? A.__url('f', path) : '/f?p=' + q(path) + (mtime ? '&v=' + mtime : ''),
    thumbUrl: (path, size, mtime) => A.__url ? A.__url('t', path, size) : '/t?p=' + q(path) + '&s=' + (size || 192) + '&v=' + (mtime || 0),
    pdfUrl: (path, n, w, mtime) => A.__url ? A.__url('pdf', path, n, w) : '/pdf?p=' + q(path) + '&n=' + n + '&w=' + w + '&v=' + (mtime || 0),
    appIcon: (pkg, size) => A.__url ? A.__url('app', pkg, size) : '/app?pkg=' + q(pkg) + '&s=' + (size || 96),
  };
  return N;
})();
