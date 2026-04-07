/**
 * PassportStudio Service Worker
 * Version: passportstudio-v2.6.0
 *
 * Android PWA install requirements met:
 *  - Fetch handler present and functional (required by Chrome on Android)
 *  - Offline fallback page cached and served
 *  - Background sync for deferred actions
 *  - Push notification scaffolding (required for full Android PWA prompt)
 *  - Reliable cache cleanup to prevent storage quota issues on mobile
 */

const APP_VERSION   = 'passportstudio-v2.6.0';
const STATIC_CACHE  = `${APP_VERSION}-static`;
const RUNTIME_CACHE = `${APP_VERSION}-runtime`;
const IMAGE_CACHE   = `${APP_VERSION}-images`;

// Max entries & age for the runtime / image caches.
// Android devices have tighter storage quotas than desktops.
const RUNTIME_MAX_ENTRIES = 60;
const IMAGE_MAX_ENTRIES   = 30;
const IMAGE_MAX_AGE_SEC   = 7 * 24 * 60 * 60; // 7 days

/**
 * CORE_ASSETS are pre-cached at install time.
 * IMPORTANT: './offline.html' must exist in your project.
 * It is served whenever the user is offline and we have no cached
 * response for the requested navigation — Android shows this page
 * instead of the browser's "No internet" error, which is what
 * keeps Chrome happy about offline capability during the install
 * eligibility check.
 */
const CORE_ASSETS = [
  './',
  './index.html',
  './offline.html',       // ← required for Android offline eligibility
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

// ─── INSTALL ──────────────────────────────────────────────────────────────────

self.addEventListener('install', event => {
  // skipWaiting so the new SW takes over immediately without waiting for
  // existing tabs to close (important when pushing updates to Android).
  self.skipWaiting();

  event.waitUntil(
    caches.open(STATIC_CACHE).then(cache => {
      // addAll is atomic — if one asset 404s the whole install fails.
      // Use individual add() calls with error swallowing for non-critical
      // assets so the SW still installs even on flaky Android connections.
      return Promise.all(
        CORE_ASSETS.map(url =>
          cache.add(url).catch(err =>
            console.warn(`[SW] Failed to pre-cache ${url}:`, err)
          )
        )
      );
    })
  );
});

// ─── ACTIVATE ─────────────────────────────────────────────────────────────────

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    // 1. Remove all caches that don't belong to this version.
    const keys = await caches.keys();
    await Promise.all(
      keys
        .filter(key => ![STATIC_CACHE, RUNTIME_CACHE, IMAGE_CACHE].includes(key))
        .map(key => {
          console.log('[SW] Deleting old cache:', key);
          return caches.delete(key);
        })
    );

    // 2. Take control of all open clients immediately.
    //    Without this, Android tabs opened before the SW upgraded won't
    //    be controlled until the user manually refreshes.
    await self.clients.claim();

    // 3. Notify all clients that a new version is active.
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach(client =>
      client.postMessage({ type: 'SW_ACTIVATED', version: APP_VERSION })
    );
  })());
});

// ─── MESSAGES ─────────────────────────────────────────────────────────────────

self.addEventListener('message', event => {
  if (!event.data) return;

  switch (event.data.type) {
    case 'SKIP_WAITING':
      self.skipWaiting();
      break;

    // Allow the app to ask the SW to pre-cache extra URLs on demand
    // (e.g. after the user logs in and you know which assets they'll need).
    case 'CACHE_URLS':
      event.waitUntil(
        caches.open(RUNTIME_CACHE).then(cache =>
          cache.addAll(event.data.urls || [])
        )
      );
      break;

    default:
      break;
  }
});

// ─── FETCH ────────────────────────────────────────────────────────────────────

self.addEventListener('fetch', event => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // ── 1. Cross-origin requests (CDN fonts, analytics, etc.) ─────────────────
  // Don't intercept — let the browser handle them normally.
  // Exception: same-origin requests always go through our cache logic below.
  if (url.origin !== self.location.origin) {
    // Only cache cross-origin images to speed up repeated renders on Android.
    if (req.destination === 'image') {
      event.respondWith(cacheFirstWithExpiry(req, IMAGE_CACHE, IMAGE_MAX_AGE_SEC, IMAGE_MAX_ENTRIES));
    }
    return;
  }

  // ── 2. HTML / navigation requests ─────────────────────────────────────────
  // Strategy: Network first → cached page → offline fallback.
  // This ensures Android always gets fresh HTML while still working offline.
  if (req.mode === 'navigate' || (req.headers.get('accept') || '').includes('text/html')) {
    event.respondWith(networkFirstWithOfflineFallback(req));
    return;
  }

  // ── 3. Same-origin images ─────────────────────────────────────────────────
  // Strategy: Cache first with expiry, then network.
  // Saves mobile data; images rarely change between versions.
  if (req.destination === 'image') {
    event.respondWith(cacheFirstWithExpiry(req, IMAGE_CACHE, IMAGE_MAX_AGE_SEC, IMAGE_MAX_ENTRIES));
    return;
  }

  // ── 4. All other same-origin assets (JS, CSS, fonts, API calls, etc.) ─────
  // Strategy: Stale-while-revalidate.
  // The cached response is returned immediately (fast on Android),
  // while a fresh copy is fetched and stored in the background.
  event.respondWith(staleWhileRevalidate(req));
});

// ─── CACHE STRATEGIES ─────────────────────────────────────────────────────────

/**
 * Network first, falling back to cache, then to /offline.html.
 * Used for HTML navigation so pages are always fresh when online.
 */
async function networkFirstWithOfflineFallback(req) {
  const cache = await caches.open(RUNTIME_CACHE);
  try {
    const fresh = await fetch(req);
    // Only cache successful, non-opaque responses.
    if (fresh.ok) cache.put(req, fresh.clone());
    return fresh;
  } catch (_) {
    const cached =
      (await cache.match(req)) ||
      (await caches.match('./index.html', { cacheName: STATIC_CACHE })) ||
      (await caches.match('./offline.html', { cacheName: STATIC_CACHE }));

    if (cached) return cached;

    // Last resort: return a bare offline response so Android doesn't
    // show a browser-level network error (which breaks the PWA feel).
    return new Response(
      '<html><body><h2>You are offline</h2><p>Please check your connection.</p></body></html>',
      { status: 503, headers: { 'Content-Type': 'text/html' } }
    );
  }
}

/**
 * Stale-while-revalidate.
 * Returns the cached version immediately, then updates the cache in the
 * background. Falls back to network if nothing is cached.
 */
async function staleWhileRevalidate(req) {
  const cache  = await caches.open(RUNTIME_CACHE);
  const cached = await cache.match(req);

  const fetchAndStore = fetch(req)
    .then(fresh => {
      if (fresh.ok) {
        cache.put(req, fresh.clone());
        trimCache(RUNTIME_CACHE, RUNTIME_MAX_ENTRIES);
      }
      return fresh;
    })
    .catch(() => cached); // network failed → return stale copy

  return cached || fetchAndStore;
}

/**
 * Cache first with a max-age expiry check.
 * If the cached entry is older than maxAgeSec, a fresh copy is fetched.
 * Keeps a rolling window of maxEntries items to protect Android storage.
 */
async function cacheFirstWithExpiry(req, cacheName, maxAgeSec, maxEntries) {
  const cache  = await caches.open(cacheName);
  const cached = await cache.match(req);

  if (cached) {
    const cachedDate = cached.headers.get('date');
    const ageMs = cachedDate
      ? Date.now() - new Date(cachedDate).getTime()
      : 0;

    if (ageMs < maxAgeSec * 1000) return cached; // still fresh → return immediately
  }

  try {
    const fresh = await fetch(req);
    if (fresh.ok) {
      cache.put(req, fresh.clone());
      trimCache(cacheName, maxEntries);
    }
    return fresh;
  } catch (_) {
    if (cached) return cached; // network failed → serve stale image
    throw _;
  }
}

/**
 * Trim a cache to at most maxEntries items (FIFO).
 * Android devices have ~50–250 MB of Cache Storage quota — this prevents
 * the app from being evicted by the browser due to storage pressure.
 */
async function trimCache(cacheName, maxEntries) {
  const cache   = await caches.open(cacheName);
  const keys    = await cache.keys();
  const surplus = keys.length - maxEntries;
  if (surplus > 0) {
    // Delete the oldest entries (front of the keys array).
    await Promise.all(keys.slice(0, surplus).map(key => cache.delete(key)));
  }
}

// ─── BACKGROUND SYNC ──────────────────────────────────────────────────────────
// Allows the app to queue actions (e.g. form submissions) while offline
// and replay them automatically when connectivity is restored on Android.

self.addEventListener('sync', event => {
  console.log('[SW] Background sync triggered:', event.tag);

  if (event.tag === 'sync-pending-uploads') {
    event.waitUntil(syncPendingUploads());
  }
});

async function syncPendingUploads() {
  // Implementation depends on your app's IndexedDB queue.
  // Example pattern:
  //   const pending = await getPendingUploadsFromIDB();
  //   for (const item of pending) {
  //     await fetch('/api/upload', { method: 'POST', body: item.data });
  //     await removePendingUploadFromIDB(item.id);
  //   }
  console.log('[SW] syncPendingUploads() — wire up your IDB queue here.');
}

// ─── PUSH NOTIFICATIONS ───────────────────────────────────────────────────────
// Chrome on Android will only show the "Add to Home Screen" install banner
// reliably for apps that have a working push handler registered.
// Even if you don't use push yet, this stub satisfies the check.

self.addEventListener('push', event => {
  const data = event.data ? event.data.json() : {};

  const title   = data.title   || 'PassportStudio';
  const options = {
    body:    data.body    || 'You have a new notification.',
    icon:    data.icon    || './icons/icon-192.png',
    badge:   data.badge   || './icons/icon-192.png',
    data:    data.url     || '/',
    vibrate: [100, 50, 100],
    actions: data.actions || [],
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const targetUrl = event.notification.data || '/';

  event.waitUntil(
    self.clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then(clients => {
        // Focus an existing tab if one is already open.
        const match = clients.find(c => c.url === targetUrl && 'focus' in c);
        if (match) return match.focus();
        // Otherwise open a new tab.
        if (self.clients.openWindow) return self.clients.openWindow(targetUrl);
      })
  );
});
