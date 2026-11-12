import { BaseCommand } from "./base.js";
export default class HelpCommand extends BaseCommand {
    commandManager;
    name = "help";
    description = "Show available commands";
    usage = "help";
    constructor(commandManager) {
        super(commandManager);
        this.commandManager = commandManager;
    }
    async execute(context) {
        const commandList = this.commandManager.getCommandList();
        const helpText = [
            '📽 **Available Commands**',
            '',
            commandList,
        ].join('\n');
        await context.message.react('📋');
        await context.message.reply(helpText);
    }
}
//# sourceMappingURL=help.js.map