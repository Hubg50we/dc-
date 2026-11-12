import { BaseCommand } from "./base.js";
export default class PingCommand extends BaseCommand {
    name = "ping";
    description = "Check bot latency";
    usage = "ping";
    async execute(context) {
        const sent = await context.message.reply('🏓 Pinging...');
        const timeDiff = sent.createdTimestamp - context.message.createdTimestamp;
        await sent.edit(`🏓 Pong! Latency: ${timeDiff}ms`);
    }
}
//# sourceMappingURL=ping.js.map