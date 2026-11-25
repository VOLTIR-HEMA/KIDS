// خدمة PWA للعمل دون اتصال قدر الإمكان (network-first لأنها تعتمد على Firebase)
const CACHE = 'kids-dashboard-v7';

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([
    './',
    './index.html',
    './css/styles.css',
    './js/app.js',
    './js/util.js',
    './js/data.js',
    './js/firebase.js',
    './js/firebase-config.js',
    './js/sections/overview.js',
    './js/sections/location.js',
    './js/sections/comms.js',
    './js/sections/media.js',
    './js/sections/combined.js',
    './js/sections/live.js',
    './js/sections/terminal.js',
    './manifest.webmanifest',
  ]).catch(() => {})));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(event.request, copy)).catch(() => {});
        return response;
      })
      .catch(() => caches.match(event.request)),
  );
});
