import { DiscordUtils } from "../utils/shared.js";
export class BaseCommand {
    aliases;
    constructor(commandManager) {
    }
    async sendError(message, error) {
        await DiscordUtils.sendError(message, error);
    }
    async sendSuccess(message, description) {
        await DiscordUtils.sendSuccess(message, description);
    }
    async sendInfo(message, title, description) {
        await DiscordUtils.sendInfo(message, title, description);
    }
    async sendList(message, items, type) {
        await DiscordUtils.sendList(message, items, type);
    }
    async sendPlaying(message, title) {
        await DiscordUtils.sendPlaying(message, title);
    }
    async sendFinishMessage(message) {
        await DiscordUtils.sendFinishMessage(message);
    }
}
//# sourceMappingURL=base.js.map