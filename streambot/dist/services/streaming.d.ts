import { Client, Message } from "discord.js-selfbot-v13";
import { Streamer } from "@dank074/discord-video-stream";
import { QueueService } from './queue.js';
import { StreamStatus } from '../types/index.js';
export declare class StreamingService {
    private streamer;
    private mediaService;
    private queueService;
    private controller;
    private streamStatus;
    private failedVideos;
    private isSkipping;
    private playbackSession;
    constructor(client: Client, streamStatus: StreamStatus);
    getStreamer(): Streamer;
    getQueueService(): QueueService;
    private markVideoAsFailed;
    addToQueue(message: Message, videoSource: string, title?: string, headers?: Record<string, string>): Promise<boolean>;
    playFromQueue(message: Message): Promise<void>;
    skipCurrent(message: Message): Promise<void>;
    private playVideoFromQueueItem;
    private getVideoParameters;
    private ensureVoiceConnection;
    private setupStreamConfiguration;
    private executeStream;
    private handleQueueAdvancement;
    private handleDownload;
    private prepareVideoSource;
    private executeStreamWorkflow;
    private finalizeStream;
    playVideo(message: Message, videoSource: string, title?: string, videoParams?: {
        width: number;
        height: number;
        fps?: number;
        bitrate?: number;
    }, headers?: Record<string, string>, onStarted?: () => void): Promise<void>;
    cleanupStreamStatus(): Promise<void>;
    stopAndClearQueue(): Promise<void>;
}
