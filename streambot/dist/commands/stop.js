import { BaseCommand } from "./base.js";
import logger from '../utils/logger.js';
export default class StopCommand extends BaseCommand {
    name = "stop";
    description = "Stop current video playback and clear queue";
    usage = "stop";
    aliases = ["leave", "s"];
    async execute(context) {
        if (!context.streamStatus.joined) {
            await this.sendError(context.message, '**Already Stopped!**');
            return;
        }
        try {
            context.streamStatus.manualStop = true;
            await this.sendSuccess(context.message, 'Stopped playing video and cleared queue.');
            logger.info("Stopped playing video and cleared queue.");
            await context.streamingService.stopAndClearQueue();
        }
        catch (error) {
            logger.error("Error during force termination:", error);
        }
    }
}
//# sourceMappingURL=stop.js.map