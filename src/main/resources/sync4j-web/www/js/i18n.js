/**
 * The translations of the page (a classic script, so that the login form can use it too).
 * <br>The texts are in English in the page and in the scripts: they are the fallbacks used when the server has no translation for the language of the user.
 * The server (<code>/api/i18n</code>) chooses the language from the languages of the browser, and gives the translations of the page and of the plugins.
 * <ul>
 * <li>In the HTML, <code>data-i18n="key"</code> translates the text of an element, <code>data-i18n-title</code>, <code>data-i18n-placeholder</code>
 * and <code>data-i18n-aria-label</code> translate the attributes. The English text of the element is the fallback.</li>
 * <li>In the scripts, <code>i18n.t(key, fallback, ...args)</code>: <code>{0}</code>, <code>{1}</code>... of the pattern are replaced by the arguments.</li>
 * <li><code>i18n.text(value)</code> displays what a plugin sent: a string, or a message <code>{key, args}</code> (the server completes the translations
 * with the English ones, the key is displayed if it has no translation at all).</li>
 * </ul>
 */
(function () {
    const state = { messages: {}, language: 'en' };
    const ATTRIBUTES = ['title', 'placeholder', 'aria-label'];

    function format(pattern, args) {
        return pattern.replace(/\{(\d+)\}/g, (placeholder, index) => index < args.length ? args[index] : placeholder);
    }

    function text(value) {
        if (value === null || value === undefined) {
            return '';
        }
        if (typeof value !== 'object') {
            return String(value);
        }
        const translated = value.key ? state.messages[value.key] : undefined;
        return format(translated ?? value.key ?? '', (value.args ?? []).map(text));
    }

    function t(key, fallback, ...args) {
        return format(state.messages[key] ?? fallback ?? key, args.map(text));
    }

    // The first time, the English text is saved: it is the fallback, and it lets apply be called again
    function original(element, name, current) {
        const dataName = 'i18nDefault' + name;
        if (element.dataset[dataName] === undefined) {
            element.dataset[dataName] = current;
        }
        return element.dataset[dataName];
    }

    function apply(root = document) {
        root.querySelectorAll('[data-i18n]').forEach(element => {
            element.textContent = t(element.dataset.i18n, original(element, 'Text', element.textContent.trim()));
        });
        for (const attribute of ATTRIBUTES) {
            root.querySelectorAll(`[data-i18n-${attribute}]`).forEach(element => {
                const fallback = original(element, attribute, element.getAttribute(attribute) ?? '');
                element.setAttribute(attribute, t(element.getAttribute(`data-i18n-${attribute}`), fallback));
            });
        }
    }

    const ready = fetch('/api/i18n')
        .then(response => response.ok ? response.json() : null)
        .then(data => {
            if (data) {
                state.messages = data.messages ?? {};
                state.language = data.language ?? 'en';
                document.documentElement.lang = state.language;
            }
        })
        .catch(error => console.error('Failed to load the translations:', error))
        .then(() => new Promise(resolve => {
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', resolve);
            } else {
                resolve();
            }
        }))
        .then(() => apply());

    globalThis.i18n = { ready, t, text, apply, get language() { return state.language; } };
})();
