import { authenticatedFetch } from './http.js';

export class SyncForm {
    constructor(app) {
        this.app = app;
        this.librarySelect = document.getElementById('librarySelect');
        this.suffixPath = document.getElementById('suffixPath');
        this.useChecksum = document.getElementById('useChecksum');
        this.startBtn = document.getElementById('startBtn');
        this.stopBtn = document.getElementById('stopBtn');
        this.errorMessage = document.getElementById('errorMessage');
        this.statusIndicator = document.getElementById('statusIndicator');
        this.lastSyncTime = document.getElementById('lastSyncTime');
        this.libraryWarnings = document.getElementById('libraryWarnings');
        this.libraryWarningsList = document.getElementById('libraryWarningsList');
        this.recheckBtn = document.getElementById('recheckBtn');

        this.setupEventListeners();
    }

    setupEventListeners() {
        this.startBtn.addEventListener('click', () => this._startSync());
        this.stopBtn.addEventListener('click', () => this.app.stopSync());
        this.librarySelect.addEventListener('change', () => this.app.selectLibrary(this.librarySelect.value));
        this.recheckBtn.addEventListener('click', () => this.checkLibrary(this.app.currentLibrary));
        this.suffixPath.addEventListener('change', () => this.saveState());
        this.useChecksum.addEventListener('change', () => this.saveState());
    }

    saveState() {
        if (!this.app.currentLibrary) return;
        
        const state = {
            library: this.app.currentLibrary,
            suffixPath: this.suffixPath.value,
            useChecksum: this.useChecksum.checked
        };
        localStorage.setItem('sync4jWebState', JSON.stringify(state));
    }

    loadState() {
        const savedState = localStorage.getItem('sync4jWebState');
        if (!savedState) return null;
        
        try {
            return JSON.parse(savedState);
        } catch (e) {
            console.error('Failed to parse saved state:', e);
            return null;
        }
    }

    async loadLibraries() {
        try {
            const [librariesRes, statusesRes] = await Promise.all([
                authenticatedFetch('/api/libraries'),
                authenticatedFetch('/api/libraries/sync')
            ]);
            
            if (!librariesRes.ok || !statusesRes.ok) {
                throw new Error('Failed to load library data');
            }
            
            const libraries = await librariesRes.json();
            const statuses = await statusesRes.json();
            
            this.app.libraryStatuses = statuses;
            this.librarySelect.innerHTML = '';
            this.librarySelect.disabled = false;
            this.clearError();
            
            if (libraries.length === 0) {
                this.librarySelect.innerHTML = '<option value="">No libraries configured</option>';
                this.startBtn.disabled = true;
                return;
            }

            const defaultOption = document.createElement('option');
            defaultOption.value = '';
            defaultOption.textContent = 'Select a library...';
            this.librarySelect.appendChild(defaultOption);

            for (const library of libraries) {
                const option = document.createElement('option');
                option.value = library.id;
                option.textContent = `${library.label}`;
                this.librarySelect.appendChild(option);
            }
            
            const savedState = this.loadState();
            if (savedState?.library) {
                const libraryExists = Array.from(this.librarySelect.options).some(
                    option => option.value === savedState.library
                );
                
                if (libraryExists) {
                    this.librarySelect.value = savedState.library;
                    this.suffixPath.value = savedState.suffixPath || '';
                    this.useChecksum.checked = Boolean(savedState.useChecksum);
                    return savedState.library;
                }
            }
            
            return null;
        } catch (error) {
            console.error('Failed to load libraries:', error);
            this.showError('Failed to load libraries: ' + error.message);
            this.librarySelect.innerHTML = '<option value="">Failed to load libraries</option>';
            this.startBtn.disabled = true;
            return null;
        }
    }

    showError(message) {
        this.errorMessage.textContent = message;
        this.errorMessage.style.display = 'block';
    }

    clearError() {
        this.errorMessage.style.display = 'none';
    }

    // Asks the server if the plugins found a problem in the library, and displays them (the user can ask again, for instance after fixing the problem)
    async checkLibrary(libraryId) {
        if (!libraryId) {
            this.clearWarnings();
            return;
        }
        this.recheckBtn.disabled = true;
        try {
            const response = await authenticatedFetch(`/api/libraries/checks/${encodeURIComponent(libraryId)}`);
            if (!response.ok) {
                throw new Error('The server answered ' + response.status);
            }
            const warnings = await response.json();
            // The user may have selected another library while the server was checking
            if (this.app.currentLibrary === libraryId) {
                this.showWarnings(warnings);
            }
        } catch (error) {
            console.error('Failed to check the library:', error);
        } finally {
            this.recheckBtn.disabled = false;
        }
    }

    showWarnings(warnings) {
        this.libraryWarningsList.innerHTML = '';
        for (const warning of warnings) {
            const item = document.createElement('li');
            item.textContent = warning.message;
            this.libraryWarningsList.appendChild(item);
        }
        this.libraryWarnings.style.display = warnings.length > 0 ? 'block' : 'none';
    }

    clearWarnings() {
        this.showWarnings([]);
    }

    getFormData() {
        return {
            libraryName: this.librarySelect.value,
            suffixPath: this.suffixPath.value.trim(),
            useChecksum: this.useChecksum.checked
        };
    }

    // Waits for the server to say what the status of the library is (the buttons are enabled when it is received)
    reset() {
        this.resetLibraryStatus();
        // After resetLibraryStatus, that enables the start button
        this.startBtn.disabled = true;
        this.stopBtn.disabled = true;
        // Without a library, there is no status to wait for (nothing would enable the list again)
        this.librarySelect.disabled = this.librarySelect.value !== '';
    }

    _setLoading(loading) {
        this.startBtn.disabled = loading;
        this.stopBtn.disabled = !loading;
    }

    async _startSync() {
        this._setLoading(true);
        try {
            await this.app.startSync();
        } catch (error) {
            console.error('Failed to start sync:', error);
            this.showError('Failed to start sync: ' + error.message);
            this._setLoading(false);
        }
    }

    _formatLastSyncTime(isoString) {
        if (!isoString) return 'Never';
        const date = new Date(isoString);
        return date.toLocaleString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: false
        });
    }

    updateLibraryStatus(status) {
        if (!status) return;

        let lastSyncTimeText;
        let loading = false;
        let statusClass;
        
        if (status.status === 'SYNCING') {
            lastSyncTimeText = 'Syncing';
            loading = true;
            statusClass = 'syncing';
            this.librarySelect.disabled = true;
        } else {
            this.librarySelect.disabled = false;
            lastSyncTimeText = this._formatLastSyncTime(status.lastSyncTime);
            // Only allow specific status values, default to 'unknown' for any other value
            const validStatuses = ['completed', 'failed', 'cancelled'];
            const statusLower = status.status?.toLowerCase();
            statusClass = validStatuses.includes(statusLower) ? statusLower : 'unknown';
        }

        if (status.errorMessage) {
            this.showError(status.errorMessage);
        } else {
            this.clearError();
        }

        // Update the last sync time element
        this.lastSyncTime.textContent = lastSyncTimeText;
        
        // Remove all status classes and add the current one
        this.statusIndicator.classList.remove('syncing', 'completed', 'failed', 'cancelled', 'unknown');
        this.statusIndicator.classList.add(statusClass);
        
        this._setLoading(loading);
    }

    resetLibraryStatus() {
        this.statusIndicator.className = 'status-indicator';
        this.lastSyncTime.textContent = '';
        this.clearError();
        this._setLoading(false);
    }
}
