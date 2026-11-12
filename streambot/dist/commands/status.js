import { BaseCommand } from "./base.js";
export default class StatusCommand extends BaseCommand {
    name = "status";
    description = "Show current streaming status";
    usage = "status";
    async execute(context) {
        await this.sendInfo(context.message, 'Status', `Joined: ${context.streamStatus.joined}\nPlaying: ${context.streamStatus.playing}`);
    }
}
//# sourceMappingURL=status.js.map