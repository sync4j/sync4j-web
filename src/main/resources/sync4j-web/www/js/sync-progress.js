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

export class SyncProgress {
    constructor() {
        this.progressSection = document.querySelector('.progress-section');
        this.progressContent = document.querySelector('.progress-content');
        this.statusMessage = document.getElementById('statusMessage');
        this.logOutput = document.getElementById('logOutput');
        
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

        // The progress blocks created by the plugins (indexed by plugin id)
        this.pluginBlocks = new Map();

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
    }

    updateLibraryStatus(status) {
        if (!status) return;

        this.updateProgress(status.result);
        if (status.status === 'COMPLETED') {
            this.handleSyncCompletion(status);
        } else if (status.status === 'FAILED') {
            this.handleSyncError(status);
        } else if (status.status === 'CANCELLED') {
            this.handleSyncCancellation(status);
        }
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
     * Displays a message sent by a plugin: data.message is added to the log and data.progress ({done, total}) is displayed as a progress bar titled data.label.
     */
    handlePluginMessage(pluginId, data) {
        if (!data) return;
        if (data.message) {
            this.addLog(`[${pluginId}] ${data.message}`);
        }
        if (data.progress) {
            const key = String(pluginId).replace(/[^a-zA-Z0-9_-]/g, '_');
            let block = this.pluginBlocks.get(key);
            if (!block) {
                block = ProgressBlock.createProgressBlock(this.progressContent, {
                    title: data.label || pluginId,
                    idPrefix: `plugin-${key}`,
                    formatValue: (n) => `${n}`
                });
                this.pluginBlocks.set(key, block);
            }
            block.update(data.progress);
        }
    }

    showProgressSection() {
        this.progressSection.style.display = 'block';
        this.resetProgress();
    }

    resetProgress() {
        this.checksBlock.reset();
        this.bytesBlock.reset();
        this.pluginBlocks.forEach(block => block.reset());
        if (this.logOutput) {
            this.logOutput.innerHTML = '';
            this.logOutput.classList.remove('show');
        }
        this.resetStatusIndicators();
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
        // The log is hidden as long as it is empty
        this.logOutput.classList.add('show');
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
