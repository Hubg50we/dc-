import config from "../config.js";
export async function handleMessageCreate(message, videos, streamStatus, streamingService, commandManager) {
    if (message.author.bot ||
        message.author.id === message.client.user?.id ||
        !message.content.startsWith(config.prefix))
        return;
    const args = message.content.slice(config.prefix.length).trim().split(/ +/);
    if (args.length === 0) {
        return;
    }
    const commandName = args.shift().toLowerCase();
    const context = {
        message,
        args,
        videos,
        streamStatus,
        streamingService
    };
    const executed = await commandManager.executeCommand(commandName, context);
    if (!executed) {
        await message.react('❌');
        await message.reply(`❌ **Error**: Unknown command. Use \`${config.prefix}help\` to see available commands.`);
    }
}
//# sourceMappingURL=messageCreate.js.map