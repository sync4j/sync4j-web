// The service worker of the page, that makes it installable as an application (PWA).
// The files of the page are taken from the network first (so a new version is used at once), and from the cache when the server can't be reached.
// The API and the WebSocket are never cached: the data has to be the current one. Only the title and the translations of the page are,
// so that the page can say in the language of the user that the server can't be reached.
const CACHE = 'sync4j-web';
// The parts of the API that are cached (they are public, and they do not change often)
const PUBLIC_TEXTS = ['/api/i18n', '/api/ui'];

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || (url.pathname.startsWith('/api/') && !PUBLIC_TEXTS.includes(url.pathname)) || url.pathname.startsWith('/ws/')) {
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
