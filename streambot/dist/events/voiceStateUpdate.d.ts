import { VoiceState } from "discord.js-selfbot-v13";
import { Client } from "discord.js-selfbot-v13";
import { StreamStatus } from "../types/index.js";
export declare function handleVoiceStateUpdate(oldState: VoiceState, newState: VoiceState, streamStatus: StreamStatus, client: Client): Promise<void>;
