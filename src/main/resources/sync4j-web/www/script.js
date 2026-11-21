class SynchronizationUI {
    currentLibrary = null;
    websocket = null;
    statusTimer = null;
    reconnectTimeout = null;
    reconnectAttempts = 0;
    RECONNECT_INTERVAL = 1000;
    MAX_RECONNECT_INTERVAL = 10000;
        
    // Initialize form and progress components
    syncForm = new SyncForm(this);
    syncProgress = new SyncProgress();

    // True once the user is logged in and the application loaded its data
    started = false;

    // The last status of the library sent by the server (null if none was received since the last request to start a synchronization)
    lastStatus = null;

    // Starts the application: it needs the user to be logged in, because the server only answers to logged in users
    async start() {
        if (this.started) {
            return this;
        }
        this.started = true;
        return this.init();
    }

    // Stops the application (the user logged out or the session expired): nothing is left that could use the old session
    stop() {
        this.started = false;
        this.disconnectWebSocket();
        this.resetReconnectState();
        this.currentLibrary = null;
        this.syncProgress.hideProgressSection();
        this.syncForm.clearError();
        this.syncForm.clearWarnings();
        this.syncProgress.clearPluginData();
    }

    // Initialize the component asynchronously
    async init() {
        try {
            const selectedLibrary = await this.syncForm.loadLibraries();
            if (selectedLibrary) {
                await this.selectLibrary(selectedLibrary);
            }
        } catch (error) {
            console.error('Failed to initialize the synchronization UI:', error);
        }
        return this; // Allow method chaining
    }

    // These methods are now handled by the respective components
    
    // Handle library selection changes
    async selectLibrary(libraryId) {
        if (this.currentLibrary === libraryId) {
            return; // Already selected
        }
        
        // Disconnect from current library
        this.disconnectWebSocket();
        
        // Update to new library
        this.currentLibrary = libraryId;
        this.resetUI();
        
        if (libraryId) {
            this.reconnectAttempts = 0;
            this.connectWebSocket();
            this.syncForm.saveState();
            this.syncForm.checkLibrary(libraryId);
        }
    }

    // Event listeners are now handled by the SyncForm component
    
    // State management is now handled by the SyncForm component
    
    // Library loading is now handled by the SyncForm component

    async startSync() {
        if (!this.currentLibrary) {
            this.syncForm.showError('Please select a library first');
            return;
        }

        this.syncProgress.showProgressSection();
        this.syncProgress.showStatus('Starting sync...', 'info');
        this.syncProgress.addLog('Connecting to sync service...');
        this.syncProgress.addLog(`Checksum verification: ${this.syncForm.useChecksum.checked ? 'enabled' : 'disabled'}`);

        // Then start the sync
        this.lastStatus = null;
        const response = await authenticatedFetch(`/api/libraries/sync/${encodeURIComponent(this.currentLibrary)}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                ...(this.syncForm.suffixPath.value.trim() && { suffixPath: this.syncForm.suffixPath.value }),
                useChecksum: this.syncForm.useChecksum.checked
            })
        });

        if (!response.ok) {
            const error = await response.json();
            throw new Error(error.error || 'Failed to start sync');
        }

        // A short synchronization can be over before the answer to the request is received: its final status should not be replaced
        if (!this.isFinished(this.lastStatus)) {
            this.syncProgress.showStatus('Sync started successfully', 'success');
        }
    }

    async stopSync() {
        if (!this.currentLibrary) {
            this.syncProgress.showStatus('No active sync session', 'error');
            return;
        }

        try {
            const response = await authenticatedFetch(`/api/libraries/sync/${encodeURIComponent(this.currentLibrary)}`, {
                method: 'DELETE',
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.error || 'Failed to stop sync');
            }

            // The WebSocket will notify us when the sync is actually cancelled
            this.syncProgress.showStatus('Stopping sync...', 'warning');
            this.syncProgress.addLog('Requesting sync cancellation...');

        } catch (error) {
            console.error('Failed to stop sync:', error);
            this.syncProgress.showStatus('Failed to stop sync: ' + error.message, 'error');
        }
    }

    // WebSocket Management
    connectWebSocket() {
        if (!this.currentLibrary) {
            console.error('Cannot connect WebSocket: No library selected');
            return;
        }

        this.disconnectWebSocket();

        const protocol = globalThis.location.protocol === 'https:' ? 'wss://' : 'ws://';
        const wsUrl = `${protocol}${globalThis.location.host}/ws/sync/${encodeURIComponent(this.currentLibrary)}`;
        
        try {
            console.log(`Attempting to connect WebSocket for library: ${this.currentLibrary} at ${wsUrl}`);
            this.websocket = new WebSocket(wsUrl);
            
            this.websocket.onopen = () => this.onWebSocketOpen();
            this.websocket.onmessage = (event) => this.onWebSocketMessage(event);
            this.websocket.onclose = (event) => this.onWebSocketClose(event);
            this.websocket.onerror = (error) => this.onWebSocketError(error);
        } catch (error) {
            console.error('WebSocket connection error:', error);
            this.attemptReconnect();
        }
    }

    onWebSocketOpen() {
        console.log(`WebSocket connected for library: ${this.currentLibrary}`);
        this.resetReconnectState();
        this.syncProgress.showStatus('Connected to sync service', 'success');
    }

    onWebSocketMessage(event) {
        try {
            const message = JSON.parse(event.data);
            console.log('Received WebSocket message:', message);
            this.handleWebSocketMessage(message);
        } catch (error) {
            console.error('Error parsing WebSocket message:', error, event.data);
        }
    }

    async onWebSocketClose(event) {
        if (!this.currentLibrary) {
            return;
        }
        console.log(`WebSocket closed for library: ${this.currentLibrary}`, event);
        // The server refuses the connection if the user is not logged in: no need to try again, the user has to log in
        // Whatever the page does, it has to know how the library evolves (a synchronization can be started by another user or another tab)
        if (!await this.isSessionValid()) {
            return;
        }
        this.syncForm.reset()
        this.attemptReconnect();
    }

    // Tests if the user is still logged in. If it can't be known (the server is not available), the session is considered as valid.
    async isSessionValid() {
        const manager = globalThis.authManager;
        if (!manager || await manager.checkSession() !== false) {
            return true;
        }
        manager.sessionExpired();
        return false;
    }

    onWebSocketError(error) {
        console.error('WebSocket error:', error);
    }

    attemptReconnect() {
        const delay = this.getReconnectDelay();
        const delaySeconds = delay / 1000;
        const message = `Connection lost. Attempting to reconnect in ${delaySeconds}s...`;
        this.syncForm.showError(message);
        
        this.reconnectTimeout = setTimeout(() => {
            this.reconnectAttempts++;
            this.connectWebSocket();
        }, delay);
    }

    getReconnectDelay() {
        const baseDelay = Math.min(
            this.RECONNECT_INTERVAL * Math.pow(2, this.reconnectAttempts - 1),
            this.MAX_RECONNECT_INTERVAL
        );
        return baseDelay;
    }

    resetReconnectState() {
        this.reconnectAttempts = 0;
        if (this.reconnectTimeout) {
            clearTimeout(this.reconnectTimeout);
            this.reconnectTimeout = null;
        }
    }

    disconnectWebSocket() {
        if (this.reconnectTimeout) {
            clearTimeout(this.reconnectTimeout);
            this.reconnectTimeout = null;
        }
        
        if (this.websocket) {
            this.websocket.onclose = null;
            if (this.websocket.readyState === WebSocket.OPEN) {
                this.websocket.close();
            }
            this.websocket = null;
        }
    }

    handleWebSocketMessage(message) {
        if (message.type === 'PROGRESS') {
            this.syncProgress.updateProgress(message.data);
        } else if (message.type === 'STATUS') {
            this.handleStatusUpdate(message.data);
        } else if (message.type === 'PLUGIN') {
            this.syncProgress.handlePluginMessage(message.plugin, message.data);
        } else {
            console.log('Unknown message type:', message);
        }
    }

    isFinished(status) {
        return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status?.status);
    }

    handleStatusUpdate(status) {
        this.lastStatus = status;
        this.syncForm.updateLibraryStatus(status);
        this.syncProgress.updateLibraryStatus(status);
    }

    showProgressSection() {
        this.syncProgress.showProgressSection();
    }

    resetProgress() {
        this.syncProgress.resetProgress();
    }

    showStatus(message, type = 'info') {
        this.syncProgress.showStatus(message, type);
    }

    addLog(message) {
        this.syncProgress.addLog(message);
    }

    updateStatusIndicator(status) {
        this.syncProgress.updateStatusIndicator(status);
    }
    
    resetUI() {
        this.syncForm.reset();
        // The card of the synchronization is the one of the previous library (it is displayed again if the new library has a synchronization to show)
        this.syncProgress.hideProgressSection();
        this.syncForm.clearWarnings();
        // What the plugins displayed is about the previous library
        this.syncProgress.clearPluginData();
        this.syncProgress.resetStatusIndicators();
    }
    
    resetStatusIndicators() {
        this.syncProgress.resetStatusIndicators();
    }
    
    // WebSocket connection management methods are now defined above
}

// Import the modular components
import { SyncForm } from './js/sync-form.js';
import { SyncProgress } from './js/sync-progress.js';
import { authenticatedFetch } from './js/http.js';

// The title of the page is set by the server (it does not need the user to be logged in: the login form is displayed with it)
fetch('/api/ui').then(response => response.ok ? response.json() : null).then(ui => {
    if (ui?.title) {
        document.title = ui.title;
        document.getElementById('appTitle').textContent = ui.title;
    }
}).catch(error => console.error('Failed to load the title of the page:', error));

// The application is created once the page is loaded, but it only loads its data when the user is logged in
const app = new SynchronizationUI();
document.addEventListener('auth:login', () => app.start());
document.addEventListener('auth:logout', () => app.stop());
if (globalThis.authManager?.loggedIn) {
    app.start();
}
