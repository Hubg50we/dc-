import { BaseCommand } from "./base.js";
import { MediaService } from "../services/media.js";
import { ErrorUtils, GeneralUtils } from '../utils/shared.js';
import { tokenize, parseHeaderFlags, hasHeaderFlags } from '../utils/headers.js';
import fs from 'fs';
import path from 'path';
import config from "../config.js";
export default class PlayCommand extends BaseCommand {
    name = "play";
    description = "Play local video, URL, or search YouTube videos";
    usage = "play <video_name|url|search_query> [--referer URL] [--origin URL] [--user-agent UA] [--header \"Name: Value\"]";
    mediaService;
    constructor() {
        super();
        this.mediaService = new MediaService();
    }
    async execute(context) {
        let input = context.args.join(' ');
        let headers = {};
        if (hasHeaderFlags(context.args)) {
            try {
                const tokens = tokenize(context.message.content.slice(config.prefix.length)).slice(1);
                ({ input, headers } = parseHeaderFlags(tokens));
            }
            catch (error) {
                await this.sendError(context.message, error instanceof Error ? error.message : String(error));
                return;
            }
            if (!/^https?:\/\//i.test(input)) {
                await this.sendError(context.message, 'Custom headers can only be used with http(s) URLs.');
                return;
            }
            if (/youtube\.com\/|youtu\.be\/|twitch\.tv\//i.test(input)) {
                await this.sendError(context.message, 'Custom headers are not supported for YouTube or Twitch URLs.');
                return;
            }
        }
        if (!input) {
            await this.sendError(context.message, 'Please provide a video name, URL, or search query.');
            return;
        }
        if (GeneralUtils.isValidUrl(input)) {
            await this.handleUrl(context, input, headers);
        }
        else {
            const videoFiles = fs.readdirSync(config.videosDir);
            const refreshedVideos = videoFiles.map(file => ({
                name: path.parse(file).name,
                path: path.join(config.videosDir, file)
            }));
            context.videos.length = 0;
            context.videos.push(...refreshedVideos);
            const video = context.videos.find(m => m.name.toLowerCase() === input.toLowerCase());
            if (video) {
                await this.handleLocalVideo(context, video);
            }
            else {
                await this.handleSearchQuery(context, input);
            }
        }
    }
    async handleLocalVideo(context, video) {
        const success = await context.streamingService.addToQueue(context.message, video.path, video.name);
        if (success) {
            if (!context.streamStatus.playing) {
                await context.streamingService.playFromQueue(context.message);
            }
        }
    }
    async handleUrl(context, url, headers = {}) {
        try {
            const success = await context.streamingService.addToQueue(context.message, url, undefined, Object.keys(headers).length > 0 ? headers : undefined);
            if (success) {
                if (!context.streamStatus.playing) {
                    await context.streamingService.playFromQueue(context.message);
                }
            }
        }
        catch (error) {
            await ErrorUtils.handleError(error, `processing URL: ${url}`, context.message);
        }
    }
    async handleSearchQuery(context, query) {
        try {
            const success = await context.streamingService.addToQueue(context.message, query, `Search: ${query}`);
            if (success) {
                if (!context.streamStatus.playing) {
                    await context.streamingService.playFromQueue(context.message);
                }
            }
        }
        catch (error) {
            await ErrorUtils.handleError(error, 'adding search query to queue', context.message);
        }
    }
}
//# sourceMappingURL=play.js.map