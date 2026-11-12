import logger from "../../utils/logger.js";
import { DiscordUtils } from "../../utils/shared.js";
export async function handleReady(client) {
    if (client.user) {
        logger.info(`${client.user.tag} is ready`);
        client.user.setActivity(DiscordUtils.status_idle());
    }
}
//# sourceMappingURL=ready.js.map