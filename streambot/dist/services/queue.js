import { MediaService } from "./media.js";
import logger from '../utils/logger.js';
export class QueueService {
    mediaService;
    queue;
    constructor() {
        this.mediaService = new MediaService();
        this.queue = {
            items: [],
            currentIndex: -1,
            isPlaying: false
        };
    }
    async addToQueue(mediaSource, requestedBy, originalInput) {
        return this.add(mediaSource.url, mediaSource.title, requestedBy, mediaSource.type, mediaSource.isLive, originalInput || mediaSource.url, mediaSource.headers);
    }
    async add(url, title, requestedBy, type = 'url', isLive = false, originalInput, headers) {
        const queueItem = {
            id: this.generateId(),
            url,
            title,
            type,
            isLive,
            requestedBy,
            addedAt: new Date(),
            originalInput: originalInput || url,
            resolved: originalInput === url,
            headers,
        };
        this.queue.items.push(queueItem);
        logger.info(`Added to queue: ${title} (requested by ${requestedBy}, resolved: ${queueItem.resolved})`);
        return queueItem;
    }
    getNext() {
        if (this.queue.items.length === 0) {
            this.queue.currentIndex = -1;
            return null;
        }
        if (this.queue.currentIndex < this.queue.items.length - 1) {
            this.queue.currentIndex++;
            return this.queue.items[this.queue.currentIndex];
        }
        this.queue.currentIndex = -1;
        return null;
    }
    getCurrent() {
        if (this.queue.items.length === 0) {
            this.queue.currentIndex = -1;
            return null;
        }
        if (this.queue.currentIndex >= 0 && this.queue.currentIndex < this.queue.items.length) {
            return this.queue.items[this.queue.currentIndex];
        }
        if (this.queue.items.length > 0) {
            if (this.queue.currentIndex >= this.queue.items.length) {
                this.queue.currentIndex = this.queue.items.length - 1;
                return this.queue.items[this.queue.currentIndex];
            }
            if (this.queue.currentIndex < 0) {
                this.queue.currentIndex = 0;
                return this.queue.items[this.queue.currentIndex];
            }
        }
        return null;
    }
    skip() {
        const currentItem = this.getCurrent();
        if (currentItem) {
            this.removeFromQueue(currentItem.id);
        }
        const nextItem = this.getNext();
        if (nextItem && this.queue.currentIndex >= 0) {
            const verifyCurrent = this.getCurrent();
            if (!verifyCurrent || verifyCurrent.id !== nextItem.id) {
                const correctIndex = this.queue.items.findIndex(item => item.id === nextItem.id);
                if (correctIndex !== -1) {
                    this.queue.currentIndex = correctIndex;
                }
            }
        }
        return nextItem;
    }
    removeFromQueue(id) {
        const index = this.queue.items.findIndex(item => item.id === id);
        if (index !== -1) {
            this.queue.items.splice(index, 1);
            if (index < this.queue.currentIndex) {
                this.queue.currentIndex--;
            }
            else if (index === this.queue.currentIndex) {
                this.queue.currentIndex--;
            }
            return true;
        }
        return false;
    }
    clearQueue() {
        this.queue.items = [];
        this.queue.currentIndex = -1;
        this.queue.isPlaying = false;
        logger.info('Queue cleared');
    }
    resetCurrentIndex() {
        this.queue.currentIndex = -1;
    }
    getQueue() {
        return [...this.queue.items];
    }
    getQueueStatus() {
        return { ...this.queue };
    }
    setPlaying(isPlaying) {
        this.queue.isPlaying = isPlaying;
    }
    isEmpty() {
        return this.queue.items.length === 0;
    }
    getLength() {
        return this.queue.items.length;
    }
    moveItem(fromIndex, toIndex) {
        if (fromIndex < 0 || fromIndex >= this.queue.items.length ||
            toIndex < 0 || toIndex >= this.queue.items.length) {
            return false;
        }
        const item = this.queue.items.splice(fromIndex, 1)[0];
        this.queue.items.splice(toIndex, 0, item);
        if (fromIndex === this.queue.currentIndex) {
            this.queue.currentIndex = toIndex;
        }
        else if (fromIndex < this.queue.currentIndex && toIndex >= this.queue.currentIndex) {
            this.queue.currentIndex--;
        }
        else if (fromIndex > this.queue.currentIndex && toIndex <= this.queue.currentIndex) {
            this.queue.currentIndex++;
        }
        return true;
    }
    generateId() {
        return Date.now().toString(36) + Math.random().toString(36).substr(2);
    }
}
//# sourceMappingURL=queue.js.map