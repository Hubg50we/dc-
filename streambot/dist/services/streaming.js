import { Streamer, Utils, prepareStream, playStream } from "@dank074/discord-video-stream";
import fs from 'fs';
import path from 'path';
import ffmpeg from 'fluent-ffmpeg';
import config from "../config.js";
import { MediaService } from './media.js';
import { QueueService } from './queue.js';
import { getVideoParams } from "../utils/ffmpeg.js";
import logger from '../utils/logger.js';
import { DiscordUtils, ErrorUtils } from '../utils/shared.js';
export class StreamingService {
    streamer;
    mediaService;
    queueService;
    controller = null;
    streamStatus;
    failedVideos = new Set();
    isSkipping = false;
    playbackSession = 0;
    constructor(client, streamStatus) {
        this.streamer = new Streamer(client);
        this.mediaService = new MediaService();
        this.queueService = new QueueService();
        this.streamStatus = streamStatus;
    }
    getStreamer() {
        return this.streamer;
    }
    getQueueService() {
        return this.queueService;
    }
    markVideoAsFailed(videoSource) {
        this.failedVideos.add(videoSource);
        logger.info(`Marked video as failed: ${videoSource}`);
    }
    async addToQueue(message, videoSource, title, headers) {
        try {
            const username = message.author.username;
            const mediaSource = await this.mediaService.resolveMediaSource(videoSource, headers);
            if (mediaSource) {
                const queueItem = await this.queueService.addToQueue(mediaSource, username);
                await DiscordUtils.sendSuccess(message, `Added to queue: \`${queueItem.title}\``);
                return true;
            }
            else {
                const queueItem = await this.queueService.add(videoSource, title || videoSource, username, 'url', false, videoSource, headers);
                await DiscordUtils.sendSuccess(message, `Added to queue: \`${queueItem.title}\``);
                return true;
            }
        }
        catch (error) {
            await ErrorUtils.handleError(error, `adding to queue: ${videoSource}`, message);
            return false;
        }
    }
    async playFromQueue(message) {
        if (this.streamStatus.playing) {
            await DiscordUtils.sendError(message, 'Already playing a video. Use skip command to skip current video.');
            return;
        }
        const nextItem = this.queueService.getNext();
        if (!nextItem) {
            await DiscordUtils.sendError(message, 'Queue is empty.');
            return;
        }
        this.queueService.setPlaying(true);
        await this.playVideoFromQueueItem(message, nextItem);
    }
    async skipCurrent(message) {
        if (!this.streamStatus.playing) {
            await DiscordUtils.sendError(message, 'No video is currently playing.');
            return;
        }
        if (this.isSkipping) {
            await DiscordUtils.sendError(message, 'Skip already in progress.');
            return;
        }
        this.isSkipping = true;
        let nextItem;
        let playback;
        try {
            this.playbackSession++;
            this.streamStatus.manualStop = true;
            this.controller?.abort();
            this.streamer.stopStream();
            const currentItem = this.queueService.getCurrent();
            nextItem = this.queueService.skip();
            if (!nextItem) {
                await DiscordUtils.sendInfo(message, 'Queue', 'No more videos in queue.');
                this.queueService.setPlaying(false);
                await this.cleanupStreamStatus();
                return;
            }
            const currentTitle = currentItem ? currentItem.title : 'current video';
            await DiscordUtils.sendInfo(message, 'Skipping', `Skipping \`${currentTitle}\`. Playing next: \`${nextItem.title}\``);
            this.streamStatus.manualStop = false;
            let markStarted;
            const started = new Promise(resolve => markStarted = resolve);
            playback = this.playVideoFromQueueItem(message, nextItem, markStarted);
            await Promise.race([started, playback]);
        }
        finally {
            this.isSkipping = false;
        }
        await playback;
    }
    async playVideoFromQueueItem(message, queueItem, onStarted) {
        this.queueService.setPlaying(true);
        let videoParams = undefined;
        if (config.respect_video_params) {
            videoParams = await this.getVideoParameters(queueItem.url, queueItem.headers);
        }
        logger.info(`Playing from queue: ${queueItem.title} (${queueItem.url})`);
        await this.playVideo(message, queueItem.url, queueItem.title, videoParams, queueItem.headers, onStarted);
    }
    async getVideoParameters(videoUrl, headers) {
        try {
            const resolution = await getVideoParams(videoUrl, headers);
            logger.info(`Video parameters: ${resolution.width}x${resolution.height}, FPS: ${resolution.fps || 'unknown'}, Bitrate: ${resolution.bitrate || 'unknown'}`);
            let bitrateKbps;
            if (resolution.bitrate) {
                bitrateKbps = Math.round(parseInt(resolution.bitrate) / 1000);
            }
            return {
                width: resolution.width,
                height: resolution.height,
                fps: resolution.fps,
                bitrate: bitrateKbps
            };
        }
        catch (error) {
            await ErrorUtils.handleError(error, 'determining video parameters');
            return undefined;
        }
    }
    async ensureVoiceConnection(guildId, channelId, title) {
        if (!this.streamStatus.joined || !this.streamer.voiceConnection) {
            await this.streamer.joinVoice(guildId, channelId);
            this.streamStatus.joined = true;
        }
        this.streamStatus.playing = true;
        this.streamStatus.channelInfo = { guildId, channelId, cmdChannelId: config.cmdChannelId };
        if (title) {
            this.streamer.client.user?.setActivity(DiscordUtils.status_watch(title));
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
        if (!this.streamer.voiceConnection) {
            throw new Error('Voice connection is not established');
        }
    }
    setupStreamConfiguration(videoParams, headers) {
        let width = videoParams?.width || config.width;
        let height = videoParams?.height || config.height;
        let frameRate = videoParams?.fps || config.fps;
        let bitrateVideo = config.bitrateKbps;
        if (videoParams && videoParams.bitrate && !config.bitrateOverride) {
            bitrateVideo = videoParams.bitrate;
        }
        if (config.maxWidth > 0 || config.maxHeight > 0) {
            const ratio = width / height;
            if (config.maxWidth > 0 && width > config.maxWidth) {
                width = config.maxWidth;
                height = Math.round(width / ratio);
            }
            if (config.maxHeight > 0 && height > config.maxHeight) {
                height = config.maxHeight;
                width = Math.round(height * ratio);
            }
            width = Math.round(width / 2) * 2;
            height = Math.round(height / 2) * 2;
        }
        return {
            width,
            height,
            frameRate,
            bitrateVideo,
            bitrateVideoMax: config.maxBitrateKbps,
            videoCodec: Utils.normalizeVideoCodec(config.videoCodec),
            hardwareAcceleratedDecoding: config.hardwareAcceleratedDecoding,
            minimizeLatency: false,
            h26xPreset: config.h26xPreset,
            ...(headers && Object.keys(headers).length > 0 && { customHeaders: headers })
        };
    }
    async executeStream(controller, inputForFfmpeg, streamOpts, message, title, videoSource) {
        const { command, output: ffmpegOutput } = prepareStream(inputForFfmpeg, streamOpts, controller.signal);
        command.on("error", (err, stdout, stderr) => {
            if (!this.streamStatus.manualStop && !controller.signal.aborted) {
                logger.error("An error happened with ffmpeg:", err.message);
                if (stdout) {
                    logger.error("ffmpeg stdout:", stdout);
                }
                if (stderr) {
                    logger.error("ffmpeg stderr:", stderr);
                }
                controller.abort();
            }
        });
        await playStream(ffmpegOutput, this.streamer, undefined, controller.signal)
            .catch((err) => {
            if (!controller.signal.aborted) {
                logger.error('playStream error:', err);
                DiscordUtils.sendError(message, `Stream error: ${err.message || 'Unknown error'}`).catch(e => logger.error('Failed to send error message:', e));
            }
            if (!controller.signal.aborted)
                controller.abort();
        });
        if (!controller.signal.aborted && !this.streamStatus.manualStop) {
            logger.info(`Finished playing: ${title || videoSource}`);
        }
        else if (this.streamStatus.manualStop) {
            logger.info(`Stopped playing: ${title || videoSource}`);
        }
        else {
            logger.info(`Failed playing: ${title || videoSource}`);
        }
    }
    async handleQueueAdvancement(message) {
        await DiscordUtils.sendFinishMessage(message);
        const finishedItem = this.queueService.getCurrent();
        if (finishedItem) {
            this.queueService.removeFromQueue(finishedItem.id);
        }
        const nextItem = this.queueService.getNext();
        if (nextItem) {
            logger.info(`Auto-playing next item from queue: ${nextItem.title}`);
            setTimeout(() => {
                this.playVideoFromQueueItem(message, nextItem).catch(err => ErrorUtils.handleError(err, 'auto-playing next item'));
            }, 1000);
        }
        else {
            this.queueService.setPlaying(false);
            logger.info('No more items in queue, playback stopped');
            await this.cleanupStreamStatus();
        }
    }
    async handleDownload(message, videoSource, title) {
        const downloadMessage = await message.reply(`📥 Downloading \`${title || 'YouTube video'}\`...`).catch(e => {
            logger.warn("Failed to send 'Downloading...' message:", e);
            return null;
        });
        try {
            logger.info(`Downloading ${title || videoSource}...`);
            const tempFilePath = await this.mediaService.downloadYouTubeVideo(videoSource);
            if (tempFilePath) {
                logger.info(`Finished downloading ${title || videoSource}`);
                if (downloadMessage) {
                    await downloadMessage.delete().catch(e => logger.warn("Failed to delete 'Downloading...' message:", e));
                }
                return tempFilePath;
            }
            throw new Error('Download failed, no temp file path returned.');
        }
        catch (error) {
            logger.error(`Failed to download YouTube video: ${videoSource}`, error);
            const errorMessage = `❌ Failed to download \`${title || 'YouTube video'}\`.`;
            if (downloadMessage) {
                await downloadMessage.edit(errorMessage).catch(e => logger.warn("Failed to edit 'Downloading...' message:", e));
            }
            else {
                await DiscordUtils.sendError(message, `Failed to download video: ${error instanceof Error ? error.message : String(error)}`);
            }
            return null;
        }
    }
    async prepareVideoSource(message, videoSource, title, headers) {
        const mediaSource = await this.mediaService.resolveMediaSource(videoSource, headers);
        if (mediaSource && mediaSource.type === 'youtube' && !mediaSource.isLive) {
            const tempFilePath = await this.handleDownload(message, videoSource, title);
            if (tempFilePath) {
                return { inputForFfmpeg: tempFilePath, tempFilePath };
            }
            throw new Error('Failed to prepare video source due to download failure.');
        }
        const finalInput = mediaSource ? mediaSource.url : videoSource;
        if (typeof finalInput === 'string' && /\.(mp3|wav|ogg|flac|aac|m4a)$/i.test(finalInput)) {
            try {
                if (!fs.existsSync(config.previewCacheDir)) {
                    fs.mkdirSync(config.previewCacheDir, { recursive: true });
                }
                const outTemp = path.join(config.previewCacheDir, 'stream_audio_' + Date.now() + '.mp4');
                await new Promise((resolve, reject) => {
                    ffmpeg()
                        .input('color=c=black:s=640x360:r=15')
                        .inputFormat('lavfi')
                        .input(finalInput)
                        .outputOptions(['-shortest', '-c:v libx264', '-pix_fmt yuv420p', '-c:a aac', '-preset ultrafast'])
                        .save(outTemp)
                        .on('end', () => resolve())
                        .on('error', (err) => reject(err));
                });
                return { inputForFfmpeg: outTemp, tempFilePath: outTemp };
            }
            catch (muxErr) {
                logger.warn('Failed to wrap audio in video container, using raw audio input:', muxErr);
            }
        }
        return { inputForFfmpeg: finalInput, tempFilePath: null };
    }
    async executeStreamWorkflow(input, options, message, title, source, onStarted) {
        const controller = new AbortController();
        this.controller = controller;
        onStarted?.();
        await this.executeStream(controller, input, options, message, title, source);
    }
    async finalizeStream(message, tempFile, session) {
        if (session === this.playbackSession) {
            if (!this.streamStatus.manualStop && this.controller && !this.controller.signal.aborted) {
                await this.handleQueueAdvancement(message);
            }
            else {
                this.queueService.setPlaying(false);
                this.queueService.resetCurrentIndex();
                await this.cleanupStreamStatus();
            }
        }
        if (tempFile) {
            try {
                fs.unlinkSync(tempFile);
            }
            catch (cleanupError) {
                logger.error(`Failed to delete temp file ${tempFile}:`, cleanupError);
            }
        }
    }
    async playVideo(message, videoSource, title, videoParams, headers, onStarted) {
        const [guildId, channelId] = [config.guildId, config.videoChannelId];
        const session = ++this.playbackSession;
        this.streamStatus.manualStop = false;
        if (title) {
            const currentQueueItem = this.queueService.getCurrent();
            if (currentQueueItem?.title === title) {
                this.queueService.setPlaying(true);
            }
        }
        let tempFile = null;
        try {
            const { inputForFfmpeg, tempFilePath } = await this.prepareVideoSource(message, videoSource, title, headers);
            tempFile = tempFilePath;
            if (session !== this.playbackSession)
                return;
            await this.ensureVoiceConnection(guildId, channelId, title);
            if (session !== this.playbackSession)
                return;
            await DiscordUtils.sendPlaying(message, title || videoSource);
            const streamOpts = this.setupStreamConfiguration(videoParams, headers);
            await this.executeStreamWorkflow(inputForFfmpeg, streamOpts, message, title || videoSource, videoSource, onStarted);
        }
        catch (error) {
            await ErrorUtils.handleError(error, `playing video: ${title || videoSource}`);
            if (session === this.playbackSession && this.controller && !this.controller.signal.aborted)
                this.controller.abort();
            this.markVideoAsFailed(videoSource);
        }
        finally {
            await this.finalizeStream(message, tempFile, session);
        }
    }
    async cleanupStreamStatus() {
        try {
            this.controller?.abort();
            this.streamer.stopStream();
            const hasQueueItems = !this.queueService.isEmpty();
            if (!hasQueueItems) {
                this.streamer.leaveVoice();
                this.streamStatus.joined = false;
                this.streamStatus.joinsucc = false;
            }
            this.streamer.client.user?.setActivity(DiscordUtils.status_idle());
            this.streamStatus.playing = false;
            this.streamStatus.manualStop = false;
            this.streamStatus.channelInfo = {
                guildId: "",
                channelId: "",
                cmdChannelId: "",
            };
        }
        catch (error) {
            await ErrorUtils.handleError(error, "cleanup stream status");
        }
    }
    async stopAndClearQueue() {
        this.queueService.clearQueue();
        logger.info("Queue cleared by stop command");
        await this.cleanupStreamStatus();
    }
}
//# sourceMappingURL=streaming.js.map