import { BaseCommand } from "./base.js";
export default class SkipCommand extends BaseCommand {
    name = "skip";
    description = "Skip the currently playing video";
    usage = "skip";
    aliases = ["next"];
    async execute(context) {
        const currentItem = context.streamingService.getQueueService().getCurrent();
        const queueLength = context.streamingService.getQueueService().getLength();
        if (!context.streamStatus.playing) {
            await this.sendError(context.message, 'No video is currently playing.');
            return;
        }
        if (queueLength === 0) {
            await this.sendError(context.message, 'No videos in queue to skip to.');
            return;
        }
        await context.streamingService.skipCurrent(context.message);
    }
}
//# sourceMappingURL=skip.js.map