class AuthManager {
    tokenKey = 'auth_token';
    // True while the user is logged in. The application is started on 'auth:login' and stopped on 'auth:logout' (see script.js).
    loggedIn = false;
    
    // How often the page checks if a server that can't be reached is back
    static RECONNECT_DELAY = 5000;

    constructor() {
        this.createUnreachableMessage();
        this.createLoginForm();
        this.setupPasswordToggle();
        this.setupLogoutButton();
    }

    // The message displayed instead of the login form when the server can't be reached (the page can be displayed by the service worker without it)
    createUnreachableMessage() {
        const message = document.createElement('div');
        message.id = 'server-unreachable';
        message.className = 'server-unreachable';
        message.setAttribute('role', 'alert');
        message.style.display = 'none';
        message.textContent = i18n.t('server.unreachable', 'The server can\'t be reached. The page will reconnect as soon as it is available.');
        document.body.insertBefore(message, document.body.firstChild);
    }

    // True if a response says that the server is not available (a reverse proxy answers 502, 503 or 504 when the server behind it is stopped)
    static isUnavailable(response) {
        return [502, 503, 504].includes(response.status);
    }

    /** Displays that the server can't be reached, and loads the page again as soon as it answers. */
    showServerUnreachable() {
        const loginForm = document.getElementById('login-form');
        if (loginForm) loginForm.style.display = 'none';
        const appContent = document.getElementById('app-content');
        if (appContent) appContent.style.display = 'none';
        document.getElementById('server-unreachable').style.display = 'block';
        if (this.reconnectTimer) {
            return;
        }
        const check = async () => {
            try {
                const response = await fetch('/api/login', { credentials: 'include' });
                if (!AuthManager.isUnavailable(response)) {
                    // The server is back: everything is loaded again (the translations, the session, ...)
                    clearInterval(this.reconnectTimer);
                    globalThis.location.reload();
                }
            } catch (error) {
                // Still unreachable
            }
        };
        this.reconnectTimer = setInterval(check, AuthManager.RECONNECT_DELAY);
        globalThis.addEventListener('online', check);
    }

    createLoginForm() {
        // Creating the form container
        const loginContainer = document.createElement('div');
        loginContainer.id = 'login-form';
        loginContainer.style.display = 'none';
        loginContainer.style.maxWidth = '400px';
        loginContainer.style.margin = '100px auto';
        loginContainer.style.padding = '20px';
        loginContainer.style.background = 'var(--login-bg)';
        loginContainer.style.borderRadius = '8px';
        loginContainer.style.boxShadow = 'var(--shadow)';
        
        // Creating styles for the form
        const style = document.createElement('style');
        style.textContent = `
            /* SVG icons in base64 */
            .password-toggle::before {
                content: '';
                display: inline-block;
                width: 20px;
                height: 20px;
                background-size: contain;
                background-repeat: no-repeat;
                background-position: center;
            }
            
            .password-toggle[data-visible="false"]::before {
                background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' fill='%23666' viewBox='0 0 24 24'%3E%3Cpath d='M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z'/%3E%3C/svg%3E");
            }
            
            .password-toggle[data-visible="true"]::before {
                background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' fill='%23666' viewBox='0 0 24 24'%3E%3Cpath d='M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z'/%3E%3C/svg%3E");
            }
            
            #login-form h2 {
                text-align: center;
                margin-bottom: 20px;
                color: var(--text);
            }
            
            #login-form .form-group {
                margin-bottom: 15px;
            }
            
            #login-form .form-group label {
                display: block;
                margin-bottom: 5px;
                font-weight: bold;
            }
            
            #login-form .input-container {
                position: relative;
                width: 100%;
            }
            
            #login-form input {
                width: 100%;
                height: 38px;
                padding: 8px 35px 8px 8px;
                border: 1px solid var(--input-border);
                background-color: var(--input-bg);
                color: var(--text);
                border-radius: 4px;
                box-sizing: border-box;
                line-height: 1.15;
                margin: 0;
            }
            
            #login-form .password-toggle {
                position: absolute;
                right: 10px;
                top: 50%;
                transform: translateY(-50%);
                background: none;
                border: none;
                cursor: pointer;
                color: var(--text-muted);
                padding: 0;
                width: 20px;
                height: 20px;
                display: flex;
                align-items: center;
                justify-content: center;
                margin: 0;
            }
            
            #login-form .password-toggle:hover {
                color: var(--text);
            }
            
            #login-button {
                width: 100%;
                padding: 10px;
                background-color: #2E7D32;
                color: white;
                border: none;
                border-radius: 4px;
                cursor: pointer;
                font-size: 16px;
                margin-top: 10px;
            }
            
            #login-button:hover {
                background-color: #45a049;
            }
            
            #login-button:disabled {
                background-color: var(--disabled-bg);
                cursor: not-allowed;
            }
            
            #login-error {
                color: var(--error-color);
                margin-top: 10px;
                text-align: center;
            }
        `;
        document.head.appendChild(style);

        // HTML du formulaire
        loginContainer.innerHTML = `
            <h2 style="text-align: center; margin-bottom: 20px;">${i18n.t('login.title', 'Login')}</h2>
            <form id="auth-form">
                <div class="form-group">
                    <label for="user">${i18n.t('login.user', 'Username:')}</label>
                    <input type="text" id="user" autocomplete="username" required>
                </div>
                <div class="form-group">
                    <label for="password">${i18n.t('login.password', 'Password:')}</label>
                    <div class="input-container">
                        <input type="password" id="password" required>
                        <button type="button" class="password-toggle" id="togglePassword" aria-label="${i18n.t('login.showPassword', 'Show password')}" 
                            data-visible="false"
                            style="position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
                                   background: none; border: none; cursor: pointer; padding: 0;
                                   width: 20px; height: 20px; margin: 0;">
                        </button>
                    </div>
                </div>
                <button type="submit" id="login-button" 
                    style="width: 100%; padding: 10px; background-color: #2E7D32; color: white;
                           border: none; border-radius: 4px; cursor: pointer; font-size: 16px; margin-top: 10px;">
                    ${i18n.t('login.signIn', 'Sign In')}
                </button>
                <div id="login-error" style="margin-top: 10px; text-align: center;"></div>
            </form>
        `;

        // Ajout du formulaire au début du body
        document.body.insertBefore(loginContainer, document.body.firstChild);
    }

    setupPasswordToggle() {
        const togglePassword = document.querySelector('#togglePassword');
        const password = document.querySelector('#password');
        
        if (togglePassword && password) {
            togglePassword.addEventListener('click', () => {
                // Toggle the type attribute
                const isVisible = password.getAttribute('type') === 'password';
                password.setAttribute('type', isVisible ? 'text' : 'password');
                
                // Toggle the icon and update attributes
                togglePassword.dataset.visible = isVisible;
                togglePassword.setAttribute('aria-label', isVisible ? i18n.t('login.hidePassword', 'Hide password') : i18n.t('login.showPassword', 'Show password'));
            });
        }
    }

    // Set up event listener for logout button
    setupLogoutButton() {
        const logoutButton = document.getElementById('logout-button');
        if (logoutButton) {
            logoutButton.addEventListener('click', (e) => {
                e.preventDefault();
                this.logout();
            });
        }
    }

    async initAuth() {
        let user;
        try {
            // Is the user already logged in?
            user = await this.getToken();
        } catch (error) {
            // The server can't be reached: the login would fail
            console.error('The server can\'t be reached:', error);
            this.showServerUnreachable();
            return;
        }
        if (user) {
            this.onLoginSuccess(user);
        } else {
            this.showLoginForm();
        }
    }

    /**
     * Gets the user that is logged in.
     * @returns the user, null if nobody is logged in
     * @throws an error if the server can't be reached
     */
    async getToken() {
        const response = await fetch('/api/login', {
            method: 'GET',
            credentials: 'include' // Important to send cookies
        });
        if (AuthManager.isUnavailable(response)) {
            throw new Error('The server is not available: ' + response.status);
        }
        if (response.ok) {
            // The server answers {"user": "..."} if the token is valid (and a 401 otherwise)
            try {
                const data = await response.json();
                return data.user ?? null;
            } catch (error) {
                console.error('Token verification failed:', error);
            }
        }
        return null;
    }

    /**
     * Checks if the session is still valid.
     * @returns true if it is, false if the server says the user is not logged in, null if it can't be known (the server is not available, for instance)
     */
    async checkSession() {
        try {
            const response = await fetch('/api/login', { credentials: 'include' });
            if (response.status === 401) {
                return false;
            }
            return response.ok ? true : null;
        } catch (error) {
            return null;
        }
    }

    /** The session is not valid any more: displays the login form. */
    sessionExpired() {
        this.showLoginForm();
        this.showError(i18n.t('session.expired', 'Your session has expired, please log in again'));
    }

    async login(user, password) {
        try {
            let response;
            try {
                response = await fetch('/api/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({ user, password }),
                    credentials: 'include' // Important for cookies
                });
            } catch (error) {
                // The server can't be reached (the browser only says "Failed to fetch")
                response = null;
            }
            if (response === null || AuthManager.isUnavailable(response)) {
                // The page waits for the server, and loads again when it is back
                this.showServerUnreachable();
                throw new Error(i18n.t('server.unreachable', 'The server can\'t be reached. The page will reconnect as soon as it is available.'));
            }

            if (!response.ok) {
                let errorMessage = i18n.t('login.failed', 'Login failed');
                const errorData = await response.json();
                errorMessage = errorData.message || errorMessage;
                throw new Error(errorMessage);
            }
            this.onLoginSuccess(user);
        } catch (error) {
            this.showError(error.message);
            throw error;
        }
    }

    async logout() {
        try {
            await fetch('/api/login', {
                method: 'DELETE',
                credentials: 'include' // Important to send cookies
            });
        } catch (error) {
            console.error('Logout failed:', error);
        } finally {
            // Ensure user is logged out even if the server request fails
            document.cookie = `${this.tokenKey}=; Path=/; Expires=Thu, 01 Jan 1970 00:00:01 GMT;`;
            this.showLoginForm();
        }
    }

    showLoginForm() {
        if (this.loggedIn) {
            this.loggedIn = false;
            document.dispatchEvent(new CustomEvent('auth:logout'));
        }
        // Hide all application content
        const appContent = document.getElementById('app-content');
        if (appContent) appContent.style.display = 'none';

        // Show login form
        const loginForm = document.getElementById('login-form');
        if (loginForm) {
            loginForm.style.display = 'block';
            loginForm.onsubmit = async (e) => {
                e.preventDefault();
                const user = document.getElementById('user').value;
                const password = document.getElementById('password').value;
                const loginButton = document.getElementById('login-button');
                const errorDiv = document.getElementById('login-error');
                
                try {
                    loginButton.disabled = true;
                    errorDiv.textContent = '';
                    await this.login(user, password);
                } catch (error) {
                    errorDiv.textContent = error.message;
                } finally {
                    loginButton.disabled = false;
                }
            };
        }
    }

    showError(message) {
        const errorDiv = document.getElementById('login-error');
        if (errorDiv) {
            errorDiv.textContent = message;
        }
    }

    onLoginSuccess(user) {
        this.loggedIn = true;
        // Hide login form
        const loginForm = document.getElementById('login-form');
        if (loginForm) loginForm.style.display = 'none';

        // Show application content
        const appContent = document.getElementById('app-content');
        if (appContent) appContent.style.display = 'block';

        // The application can now load its data: it needs the user to be logged in
        document.dispatchEvent(new CustomEvent('auth:login', { detail: { user } }));
    }
}

// Initialize authentication when the page loads
document.addEventListener('DOMContentLoaded', async () => {
    // The login form is built with the texts of the language of the user
    await i18n.ready;
    const authManager = new AuthManager();
    globalThis.authManager = authManager;
    try {
        await authManager.initAuth();
    } catch (error) {
        console.error('Failed to initialize auth manager:', error);
        // Show error to user if needed
        const errorDiv = document.getElementById('login-error');
        if (errorDiv) {
            errorDiv.textContent = i18n.t('login.initFailed', 'Failed to initialize authentication. Please refresh the page.');
        }
    }
});
