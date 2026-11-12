import { VideoQueue, QueueItem, MediaSource } from "../types/index.js";
export declare class QueueService {
    private mediaService;
    private queue;
    constructor();
    addToQueue(mediaSource: MediaSource, requestedBy: string, originalInput?: string): Promise<QueueItem>;
    add(url: string, title: string, requestedBy: string, type?: 'youtube' | 'twitch' | 'local' | 'url', isLive?: boolean, originalInput?: string, headers?: Record<string, string>): Promise<QueueItem>;
    getNext(): QueueItem | null;
    getCurrent(): QueueItem | null;
    skip(): QueueItem | null;
    removeFromQueue(id: string): boolean;
    clearQueue(): void;
    resetCurrentIndex(): void;
    getQueue(): QueueItem[];
    getQueueStatus(): VideoQueue;
    setPlaying(isPlaying: boolean): void;
    isEmpty(): boolean;
    getLength(): number;
    moveItem(fromIndex: number, toIndex: number): boolean;
    private generateId;
}
