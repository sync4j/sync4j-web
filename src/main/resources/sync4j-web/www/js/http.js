/**
 * Sends a request to the server, as the logged in user.
 * <br>If the server answers that the user is not logged in (the session expired, for instance), the login form is displayed and an error is thrown.
 */
export async function authenticatedFetch(url, options = {}) {
    const response = await fetch(url, { credentials: 'same-origin', ...options });
    if (response.status === 401) {
        globalThis.authManager?.sessionExpired();
        throw new Error(i18n.t('session.expired', 'Your session has expired, please log in again'));
    }
    return response;
}
