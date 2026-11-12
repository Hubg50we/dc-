import config from "../config.js";
import logger from "./logger.js";
import fs from 'fs';
export const DiscordUtils = {
    status_idle() {
        return {
            name: config.prefix + "help",
            type: 'WATCHING'
        };
    },
    status_watch(name) {
        return {
            name: `${name}`,
            type: 'WATCHING'
        };
    },
    async sendError(message, error) {
        await message.react('❌');
        await message.reply(`❌ **Error**: ${error}`);
    },
    async sendSuccess(message, description) {
        await message.react('✅');
        await message.channel.send(`✅ **Success**: ${description}`);
    },
    async sendInfo(message, title, description) {
        await message.react('ℹ️');
        await message.channel.send(`ℹ️ **${title}**: ${description}`);
    },
    async sendPlaying(message, title) {
        const content = `📽 **Now Playing**: \`${title}\``;
        await Promise.all([
            message.react('▶️'),
            message.reply(content)
        ]);
    },
    async sendFinishMessage(message) {
        const content = '⏹️ **Finished**: Finished playing video.';
        await message.channel.send(content);
    },
    async sendList(message, items, type) {
        await message.react('📋');
        if (type == "ytsearch") {
            await message.reply(`📋 **Search Results**:\n${items.join('\n')}`);
        }
        else if (type == "refresh") {
            await message.reply(`📋 **Video list refreshed**:\n${items.join('\n')}`);
        }
        else {
            await message.channel.send(`📋 **Local Videos List**:\n${items.join('\n')}`);
        }
    }
};
export const ErrorUtils = {
    async handleError(error, context, message) {
        logger.error(`Error in ${context}:`, error);
        if (message) {
            await DiscordUtils.sendError(message, `An error occurred: ${error.message || 'Unknown error'}`);
        }
    },
    async withErrorHandling(operation, context, message) {
        try {
            return await operation();
        }
        catch (error) {
            await this.handleError(error, context, message);
            return null;
        }
    }
};
export const GeneralUtils = {
    isValidUrl(input) {
        if (!input || typeof input !== 'string') {
            return false;
        }
        return input.includes('youtube.com/') ||
            input.includes('youtu.be/') ||
            input.includes('twitch.tv/') ||
            input.startsWith('http://') ||
            input.startsWith('https://');
    },
    isLocalFile(filePath) {
        try {
            return fs.existsSync(filePath) && fs.lstatSync(filePath).isFile();
        }
        catch (error) {
            return false;
        }
    }
};
//# sourceMappingURL=shared.js.map