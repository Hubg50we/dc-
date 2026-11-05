import { Client } from "discord.js-selfbot-v13";
import config from "./config.js";
import fs from 'fs';
import path from 'path';
import logger from './utils/logger.js';
import { downloadExecutable, checkForUpdatesAndUpdate } from './utils/yt-dlp.js';
import { handleReady } from './events/client/ready.js';
import { handleMessageCreate } from './events/messageCreate.js';
import { handleVoiceStateUpdate } from './events/voiceStateUpdate.js';
import { StreamingService } from './services/streaming.js';
import { MediaService } from './services/media.js';
import { CommandManager } from './commands/manager.js';
import { QueueService } from './services/queue.js';
(async () => {
    try {
        await downloadExecutable();
        await checkForUpdatesAndUpdate();
    }
    catch (error) {
        logger.error("Error during initial yt-dlp setup/update:", error);
    }
})();
const client = new Client();
const queueService = new QueueService();
const streamStatus = {
    joined: false,
    joinsucc: false,
    playing: false,
    manualStop: false,
    channelInfo: {
        guildId: config.guildId,
        channelId: config.videoChannelId,
        cmdChannelId: config.cmdChannelId
    },
    queue: queueService.getQueueStatus()
};
const streamingService = new StreamingService(client, streamStatus);
const mediaService = new MediaService();
const commandManager = new CommandManager();
if (!fs.existsSync(config.videosDir)) {
    fs.mkdirSync(config.videosDir);
}
if (!fs.existsSync(path.dirname(config.previewCacheDir))) {
    fs.mkdirSync(path.dirname(config.previewCacheDir), { recursive: true });
}
if (!fs.existsSync(config.previewCacheDir)) {
    fs.mkdirSync(config.previewCacheDir);
}
const videoFiles = fs.readdirSync(config.videosDir);
let videos = videoFiles.map(file => {
    const fileName = path.parse(file).name;
    return { name: fileName, path: path.join(config.videosDir, file) };
});
if (videos.length > 0) {
    logger.info(`Available videos:\n${videos.map(m => m.name).join('\n')}`);
}
client.on("ready", async () => {
    await handleReady(client);
});
client.on('voiceStateUpdate', async (oldState, newState) => {
    await handleVoiceStateUpdate(oldState, newState, streamStatus, client);
});
client.on('messageCreate', async (message) => {
    await handleMessageCreate(message, videos, streamStatus, streamingService, commandManager);
});
process.on('uncaughtException', (error) => {
    if (!(error instanceof Error && error.message.includes('SIGTERM'))) {
        logger.error('Uncaught Exception:', error);
        return;
    }
});
if (config.server_enabled) {
    import('./server/index.js').then((module) => {
        module.startServer(streamingService, streamStatus, client);
    });
}
client.login(config.token);
//# sourceMappingURL=index.js.map