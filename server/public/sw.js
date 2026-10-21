// Lets Gallery install as an app (Windows, Mac, Chromebook, Android). Pages always come from the
// network; the last copy of the app itself is kept so it still opens when the server is down.
const SHELL = 'gallery-shell-v3';
const FILES = ['./', 'index.html', 'app.js', 'app.css', 'icon.svg', 'icon-192.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  const shell = e.request.method === 'GET' && url.origin === location.origin && FILES.some(f => url.pathname.endsWith(f === './' ? '/' : '/' + f));
  if (!shell) return; // photos, API calls and share links go straight to the network
  e.respondWith(fetch(e.request).then(r => {
    if (r.ok) { const copy = r.clone(); caches.open(SHELL).then(c => c.put(e.request, copy)); }
    return r;
  }).catch(() => caches.match(e.request)));
});
