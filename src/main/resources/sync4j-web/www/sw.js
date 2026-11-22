// The service worker of the page, that makes it installable as an application (PWA).
// The files of the page are taken from the network first (so a new version is used at once), and from the cache when the server can't be reached.
// The API and the WebSocket are never cached: the data has to be the current one.
const CACHE = 'sync4j-web';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) {
        return;
    }
    event.respondWith(
        fetch(event.request)
            .then(response => {
                if (response.ok) {
                    const copy = response.clone();
                    caches.open(CACHE).then(cache => cache.put(event.request, copy));
                }
                return response;
            })
            .catch(() => caches.match(event.request).then(cached => cached ?? Response.error()))
    );
});
