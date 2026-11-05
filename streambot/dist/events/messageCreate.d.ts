import { Message } from "discord.js-selfbot-v13";
import { CommandManager } from "../commands/manager.js";
import { Video, StreamStatus } from "../types/index.js";
export declare function handleMessageCreate(message: Message, videos: Video[], streamStatus: StreamStatus, streamingService: any, commandManager: CommandManager): Promise<void>;
