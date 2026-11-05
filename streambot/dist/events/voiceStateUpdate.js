import { DiscordUtils } from "../utils/shared.js";
export async function handleVoiceStateUpdate(oldState, newState, streamStatus, client) {
    if (oldState.member?.user.id == client.user?.id) {
        if (oldState.channelId && !newState.channelId) {
            streamStatus.joined = false;
            streamStatus.joinsucc = false;
            streamStatus.playing = false;
            streamStatus.channelInfo = {
                guildId: "",
                channelId: "",
                cmdChannelId: ""
            };
            client.user?.setActivity(DiscordUtils.status_idle());
        }
    }
    if (newState.member?.user.id == client.user?.id) {
        if (newState.channelId && !oldState.channelId) {
            streamStatus.joined = true;
            if (newState.guild.id == streamStatus.channelInfo.guildId && newState.channelId == streamStatus.channelInfo.channelId) {
                streamStatus.joinsucc = true;
            }
        }
    }
}
//# sourceMappingURL=voiceStateUpdate.js.map