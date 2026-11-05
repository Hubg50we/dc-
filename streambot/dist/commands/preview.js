import { BaseCommand } from "./base.js";
import { MessageAttachment } from "discord.js-selfbot-v13";
import path from 'path';
import { ffmpegScreenshot } from "../utils/ffmpeg.js";
import logger from '../utils/logger.js';
export default class PreviewCommand extends BaseCommand {
    name = "preview";
    description = "Generate preview thumbnails for a video";
    usage = "preview <video_name>";
    async execute(context) {
        const vid = context.args.join(' ');
        if (!vid) {
            await this.sendError(context.message, 'Please provide a video name.');
            return;
        }
        const vid_name = context.videos.find(m => m.name === vid);
        if (!vid_name) {
            await this.sendError(context.message, 'Video not found');
            return;
        }
        context.message.react('📸');
        context.message.reply('📸 **Generating preview thumbnails...**');
        try {
            const videoFilename = vid_name.name + path.extname(vid_name.path);
            const thumbnails = await ffmpegScreenshot(videoFilename);
            if (thumbnails.length > 0) {
                const attachments = [];
                for (const screenshotPath of thumbnails) {
                    attachments.push(new MessageAttachment(screenshotPath));
                }
                const content = `📸 **Preview**: \`${vid_name.name}\``;
                await context.message.reply({
                    content,
                    files: attachments
                });
            }
            else {
                await this.sendError(context.message, 'Failed to generate preview thumbnails.');
            }
        }
        catch (error) {
            logger.error('Error generating preview thumbnails:', error);
            await this.sendError(context.message, 'Failed to generate preview thumbnails.');
        }
    }
}
//# sourceMappingURL=preview.js.map