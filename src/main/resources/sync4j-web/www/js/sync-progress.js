class ProgressBlock {
    constructor({ fillEl, percentEl, processedEl, totalEl, speedEl, formatValue }) {
        this.fillEl = fillEl;
        this.percentEl = percentEl;
        this.processedEl = processedEl;
        this.totalEl = totalEl;
        this.speedEl = speedEl;
        this.formatValue = formatValue;
        this.lastDone = 0;
        this.lastUpdate = 0;
    }

    static createProgressBlock(container, { title, idPrefix, formatValue = (n) => `${n}` }) {
        const block = document.createElement('div');
        block.className = 'progress-block';
        block.innerHTML = `
            <h3>${title}</h3>
            <div class="progress-bar-container">
                <div class="progress-bar">
                    <div id="${idPrefix}Fill" class="progress-fill"></div>
                </div>
                <div class="progress-text"><span id="${idPrefix}Percent">0%</span></div>
            </div>
            <div class="stats-row">
                <div class="stat-field">
                    <span class="stat-label">PROCESSED</span>
                    <span id="${idPrefix}Processed" class="stat-value">${formatValue(0)}</span>
                </div>
                <div class="stat-field">
                    <span class="stat-label">TOTAL</span>
                    <span id="${idPrefix}Total" class="stat-value">${formatValue(0)}</span>
                </div>
                <div class="stat-field">
                    <span class="stat-label">SPEED</span>
                    <span id="${idPrefix}Speed" class="stat-value">${formatValue(0)}/s</span>
                </div>
                ${idPrefix === 'checks' ? '<span id="checksProgress" class="visually-hidden">0 / 0 files checked</span>' : ''}
            </div>
        `;
        
        if (container) {
            container.appendChild(block);
        }

        return new ProgressBlock({
            fillEl: document.getElementById(`${idPrefix}Fill`),
            percentEl: document.getElementById(`${idPrefix}Percent`),
            processedEl: document.getElementById(`${idPrefix}Processed`),
            totalEl: document.getElementById(`${idPrefix}Total`),
            speedEl: document.getElementById(`${idPrefix}Speed`),
            formatValue
        });
    }

    update(counter) {
        counter = counter || { done: 0, total: 0 };
        const percent = this.calculatePercentage(counter);
        if (this.fillEl) this.fillEl.style.width = `${percent}%`;
        if (this.percentEl) this.percentEl.textContent = `${Math.ceil(percent)}%`;
        if (this.processedEl) this.processedEl.textContent = this.formatValue(counter.done);
        if (this.totalEl) this.totalEl.textContent = this.formatValue(counter.total);
        if (this.speedEl) this.speedEl.textContent = this.formatValue(this.getSpeed(counter.done))+"/s";
    }

    getSpeed(done) {
        const now = Date.now();
        let result;
        if (done < this.lastDone) {
            console.log("reset was not called, strange!");
            result = 0;
        } else {
            const timeDiff = now - this.lastUpdate;
            const speed = (done - this.lastDone)*1000 / timeDiff;
            result = Math.round(speed);
        }
        this.lastUpdate = now;
        this.lastDone = done;
        return result;
    }

    reset() {
        this.lastDone = 0;
        this.lastUpdate = 0;
        if (this.fillEl) this.fillEl.style.width = '0%';
        if (this.percentEl) this.percentEl.textContent = '0%';
        if (this.processedEl) this.processedEl.textContent = this.formatValue(0);
        if (this.totalEl) this.totalEl.textContent = this.formatValue(0);
        if (this.speedEl) this.speedEl.textContent = this.formatValue(0)+"/s";
    }

    calculatePercentage(counter) {
        if (!counter || counter.total === 0) return 0;
        return Math.min(100, (counter.done / counter.total) * 100);
    }
}

const PLUGIN_STATES = {
    RUNNING: 'In progress',
    DONE: 'Done',
    FAILED: 'Failed',
    UNKNOWN: 'Unknown'
};

// The state of the card of the synchronization, according to the status of the library
const SYNC_STATES = {
    SYNCING: { css: 'running', label: 'In progress' },
    COMPLETED: { css: 'done', label: 'Done' },
    FAILED: { css: 'failed', label: 'Failed' },
    CANCELLED: { css: 'cancelled', label: 'Cancelled' }
};

/**
 * The cards of the plugins: one card per plugin, that tells the end user what the plugin does.
 * <br>A message of a plugin updates its card with: label (the title), state (RUNNING, DONE, FAILED or UNKNOWN), message (a sentence),
 * details (a list of sentences) and progress ({done, total}). The texts are never interpreted as HTML.
 */
class PluginCards {
    constructor(container) {
        this.container = container;
        this.cards = new Map();
    }

    update(pluginId, data) {
        let card = this.cards.get(pluginId);
        if (!card) {
            card = this.createCard();
            this.cards.set(pluginId, card);
            this.container.appendChild(card.element);
        }
        this.fill(card, pluginId, data);
    }

    createCard() {
        const element = document.createElement('section');
        element.className = 'plugin-card';
        const header = document.createElement('div');
        header.className = 'plugin-card-header';
        const title = document.createElement('h2');
        const badge = document.createElement('span');
        badge.className = 'badge';
        header.append(title, badge);
        const message = document.createElement('p');
        message.className = 'plugin-card-message';
        const details = document.createElement('ul');
        details.className = 'plugin-card-details';
        const progress = document.createElement('div');
        progress.className = 'plugin-card-progress';
        progress.innerHTML = '<div class="progress-bar"><div class="progress-fill"></div></div><span class="plugin-card-progress-text"></span>';
        element.append(header, message, details, progress);
        return { element, title, badge, message, details, progress,
            fill: progress.querySelector('.progress-fill'), progressText: progress.querySelector('.plugin-card-progress-text') };
    }

    fill(card, pluginId, data) {
        card.title.textContent = data.label || pluginId;

        const state = String(data.state ?? '').toUpperCase();
        const known = Object.hasOwn(PLUGIN_STATES, state);
        card.element.className = `plugin-card state-${known ? state.toLowerCase() : 'unknown'}`;
        card.badge.textContent = known ? PLUGIN_STATES[state] : (state ? state : '');
        card.badge.style.display = known || state ? '' : 'none';

        card.message.textContent = data.message ?? '';
        card.message.style.display = data.message ? '' : 'none';

        card.details.replaceChildren(...(Array.isArray(data.details) ? data.details : []).map(text => {
            const item = document.createElement('li');
            item.textContent = String(text);
            return item;
        }));
        card.details.style.display = card.details.children.length > 0 ? '' : 'none';

        const progress = data.progress;
        if (progress && progress.total > 0) {
            const percent = Math.max(0, Math.min(100, progress.done / progress.total * 100));
            card.fill.style.width = `${percent}%`;
            card.progressText.textContent = `${progress.done} / ${progress.total}`;
            card.progress.style.display = '';
        } else {
            card.progress.style.display = 'none';
        }
    }

    clear() {
        this.cards.forEach(card => card.element.remove());
        this.cards.clear();
    }
}

export class SyncProgress {
    constructor() {
        this.progressSection = document.querySelector('.progress-section');
        this.progressContent = document.querySelector('.progress-content');
        this.statusMessage = document.getElementById('statusMessage');
        this.progressBadge = document.getElementById('progressBadge');
        this.logOutput = document.getElementById('logOutput');
        this.logSection = document.getElementById('logSection');
        this.pluginCards = new PluginCards(document.getElementById('pluginCards'));
        
        // Create progress blocks
        this.checksBlock = ProgressBlock.createProgressBlock(this.progressContent, {
            title: 'Checked Files',
            idPrefix: 'checks',
            formatValue: (n) => `${n}`
        });

        this.bytesBlock = ProgressBlock.createProgressBlock(this.progressContent, {
            title: 'Copied Bytes',
            idPrefix: 'bytes',
            formatValue: (n) => this.formatBytes(n)
        });


        // Get references to the created elements for checksProgress
        this.checksProgress = document.getElementById('checksProgress');

        // Initialize the progress section toggle
        const toggleButton = document.getElementById('toggleProgressSection');
        if (toggleButton && this.progressSection) {
            toggleButton.addEventListener('click', () => {
                this.progressSection.classList.toggle('collapsed');
                const arrow = toggleButton.querySelector('.arrow');
                if (arrow) {
                    arrow.classList.toggle('up', !this.progressSection.classList.contains('collapsed'));
                }
            });
        }

        // The log is collapsed by default
        const toggleLog = document.getElementById('toggleLogSection');
        if (toggleLog && this.logSection) {
            toggleLog.addEventListener('click', () => {
                this.logSection.classList.toggle('collapsed');
                toggleLog.querySelector('.arrow')?.classList.toggle('up', !this.logSection.classList.contains('collapsed'));
            });
        }
    }

    updateLibraryStatus(status) {
        if (!status) return;

        // The card is displayed when the library has a synchronization to show, for instance after the page was loaded again
        if (SYNC_STATES[status.status]) {
            this.progressSection.style.display = 'block';
        }
        this.updateProgress(status.result);
        this.showState(status.status);
        if (status.status === 'COMPLETED') {
            this.handleSyncCompletion(status);
        } else if (status.status === 'FAILED') {
            this.handleSyncError(status);
        } else if (status.status === 'CANCELLED') {
            this.handleSyncCancellation(status);
        }
    }

    // Displays the state of the synchronization (badge and colored edge). The card is folded when the synchronization succeeded.
    showState(status) {
        const state = SYNC_STATES[status];
        this.progressSection.classList.remove(...Object.values(SYNC_STATES).map(s => `state-${s.css}`));
        if (this.progressBadge) {
            this.progressBadge.textContent = state ? state.label : '';
            this.progressBadge.style.display = state ? '' : 'none';
        }
        if (state) {
            this.progressSection.classList.add(`state-${state.css}`);
        }
        // Only when the state changes, so a card that the user unfolded is not folded again by the next status
        if (status !== this.shownStatus && (status === 'COMPLETED' || status === 'SYNCING')) {
            this.setCollapsed(status === 'COMPLETED');
        }
        this.shownStatus = status;
    }

    setCollapsed(collapsed) {
        this.progressSection.classList.toggle('collapsed', collapsed);
        document.querySelector('#toggleProgressSection .arrow')?.classList.toggle('up', !collapsed);
    }

    updateProgress(data) {
        if (!data) {
            this.resetProgress();
            return;
        }
        this.checksBlock.update(data.checkedFiles);
        this.bytesBlock.update(data.copiedBytes);
    }

    handleSyncCompletion(data) {
        this.showStatus('Sync completed successfully', 'success');
        this.addLog('Sync completed successfully');
    }

    handleSyncError(data) {
        const errorMessage = data.errorMessage || 'An error occurred during sync';
        this.showStatus(`Sync failed: ${errorMessage}`, 'error');
        this.addLog(`Error: ${errorMessage}`);
    }

    handleSyncCancellation(data) {
        this.showStatus('Sync was cancelled', 'warning');
        this.addLog('Sync was cancelled by user');
    }

    /**
     * Displays a message sent by a plugin: data.message is added to the technical log, and the card of the plugin is updated if the message
     * has a state, details or a progress (see PluginCards).
     */
    handlePluginMessage(pluginId, data) {
        if (!data) return;
        if (data.message) {
            this.addLog(`[${pluginId}] ${data.message}`);
        }
        if (data.state || data.progress || data.details) {
            this.pluginCards.update(pluginId, data);
        }
    }

    showProgressSection() {
        this.progressSection.style.display = 'block';
        this.resetProgress();
        this.setCollapsed(false);
    }

    hideProgressSection() {
        this.resetProgress();
        this.progressSection.style.display = 'none';
    }

    resetProgress() {
        this.checksBlock.reset();
        this.bytesBlock.reset();
        this.clearPluginData();
        this.showState(null);
        this.resetStatusIndicators();
    }

    // Removes what the plugins displayed (their cards and the technical log), for instance when another library is selected
    clearPluginData() {
        this.pluginCards.clear();
        if (this.logOutput) this.logOutput.innerHTML = '';
        if (this.logSection) this.logSection.style.display = 'none';
    }

    showStatus(message, type = 'info') {
        this.statusMessage.textContent = message;
        this.statusMessage.className = `status-message status-${type}`;
        this.statusMessage.style.display = 'block';
    }

    addLog(message) {
        const logEntry = document.createElement('div');
        logEntry.className = 'log-entry';
        logEntry.textContent = `[${new Date().toISOString()}] ${message}`;
        this.logOutput.appendChild(logEntry);
        // The card of the log is hidden as long as the log is empty
        if (this.logSection) this.logSection.style.display = 'block';
        this.logOutput.scrollTop = this.logOutput.scrollHeight;
    }

    formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const dm = Math.max(0, decimals);
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return Number.parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }

    resetStatusIndicators() {
        this.statusMessage.style.display = 'none';
    }
}
