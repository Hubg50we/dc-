import { Client } from "@lng2004/discord.js-selfbot-v13";
import config from "./config.js";
import { StreamingService } from "./services/streaming.js";
import { QueueService } from "./services/queue.js";
import path from "path";
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
client.on("ready", async () => {
    console.log(`[+] Logged in as ${client.user?.tag} (${client.user?.id})`);
    const testFile = path.resolve("./videos/test.mp4");
    console.log(`[*] Target video: ${testFile}`);
    console.log(`[*] Target Guild: ${config.guildId}, Channel: ${config.videoChannelId}`);
    const mockMessage = {
        author: { username: client.user?.username || "lkgamerbg" },
        react: async (emoji) => {
            console.log(`[Discord React]: ${emoji}`);
        },
        reply: async (msg) => {
            console.log(`[Discord Reply]: ${msg}`);
            return {
                delete: async () => { },
                edit: async (m) => console.log(`[Discord Edit]: ${m}`)
            };
        },
        channel: {
            send: async (msg) => console.log(`[Channel Send]:`, msg)
        }
    };
    try {
        console.log("[*] Joining voice channel and preparing real stream...");
        await streamingService.playVideo(mockMessage, testFile, "Test Video 720p", undefined, undefined, () => {
            console.log("[+] STREAM STARTED SUCCESSFULLY! Packets transmitting over Voice UDP!");
        });
        console.log("[+] Stream playback finished cleanly.");
    }
    catch (err) {
        console.error("[!] Stream error:", err);
    }
    finally {
        process.exit(0);
    }
});
client.login(config.token);
//# sourceMappingURL=test_stream.js.map