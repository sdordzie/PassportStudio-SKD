/**
 * PassportStudio Pro — Service Worker
 * Cache strategy: Cache-First for app shell, Network-First for dynamic requests
 */

const CACHE_NAME    = 'pps-pro-v1';
const FONT_CACHE    = 'pps-fonts-v1';

// App shell — the single HTML file (update this path to match your deployment)
const APP_SHELL = [
  './',
  './index.html',
];

// Google Fonts to pre-cache so the app looks great offline
const FONT_URLS = [
  'https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Syne:wght@400;500;600;700;800&family=DM+Sans:wght@300;400;500&display=swap',
];

// ── INSTALL ───────────────────────────────────────────────────────────────────
self.addEventListener('install', event => {
  self.skipWaiting(); // activate immediately
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(APP_SHELL))
  );
});

// ── ACTIVATE ─────────────────────────────────────────────────────────────────
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k !== CACHE_NAME && k !== FONT_CACHE)
          .map(k => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ── FETCH ─────────────────────────────────────────────────────────────────────
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Skip non-GET and browser-extension requests
  if (request.method !== 'GET') return;
  if (url.protocol === 'chrome-extension:') return;

  // ── Fonts: cache-first, long TTL ──────────────────────────────────────────
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    event.respondWith(
      caches.open(FONT_CACHE).then(cache =>
        cache.match(request).then(cached => {
          if (cached) return cached;
          return fetch(request).then(response => {
            if (response.ok) cache.put(request, response.clone());
            return response;
          }).catch(() => cached); // serve stale if network fails
        })
      )
    );
    return;
  }

  // ── App shell: cache-first ─────────────────────────────────────────────────
  if (url.origin === self.location.origin) {
    event.respondWith(
      caches.match(request).then(cached => {
        const networkFetch = fetch(request)
          .then(response => {
            if (response.ok) {
              caches.open(CACHE_NAME).then(cache => cache.put(request, response.clone()));
            }
            return response;
          })
          .catch(() => cached); // offline fallback to cache

        // Return cached immediately; update cache in background
        return cached || networkFetch;
      })
    );
    return;
  }
});

// ── MESSAGE HANDLER (page → SW: skip waiting on update) ──────────────────────
self.addEventListener('message', event => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

// ── BACKGROUND SYNC (future-proof stub) ───────────────────────────────────────
self.addEventListener('sync', event => {
  if (event.tag === 'pps-sync') {
    // No remote sync needed for a local-first tool
    event.waitUntil(Promise.resolve());
  }
});

// ── PUSH NOTIFICATIONS (future-proof stub) ────────────────────────────────────
self.addEventListener('push', event => {
  // Not used currently; stub prevents unhandled-event warnings
});
