import { BaseCommand } from "./base.js";
import config, { parseBoolean, parseVideoCodec, parsePreset } from "../config.js";
import logger from "../utils/logger.js";
export default class ConfigCommand extends BaseCommand {
    name = "config";
    description = "View or adjust bot configuration parameters (Admin only)";
    usage = "config [parameter] [value]";
    aliases = ["cfg", "set"];
    async execute(context) {
        if (!this.isAdmin(context.message.author.id)) {
            await this.sendError(context.message, "You don't have permission to use this command. Admin access required.");
            logger.warn(`Unauthorized config command attempt by user ${context.message.author.id}`);
            return;
        }
        const args = context.args;
        if (args.length === 0) {
            await this.showConfig(context);
            return;
        }
        if (args.length === 1) {
            await this.showParameter(context, args[0]);
            return;
        }
        const parameter = args[0].toLowerCase();
        const value = args.slice(1).join(' ');
        await this.setParameter(context, parameter, value);
    }
    async showConfig(context) {
        const configInfo = [
            "**Stream Options:**",
            `• respect_video_params: ${config.respect_video_params}`,
            `• bitrateOverride: ${config.bitrateOverride}`,
            `• width: ${config.width}`,
            `• height: ${config.height}`,
            `• fps: ${config.fps}`,
            `• bitrateKbps: ${config.bitrateKbps}`,
            `• maxBitrateKbps: ${config.maxBitrateKbps}`,
            `• maxWidth: ${config.maxWidth || 'None'}`,
            `• maxHeight: ${config.maxHeight || 'None'}`,
            `• hardwareAcceleratedDecoding: ${config.hardwareAcceleratedDecoding}`,
            `• h26xPreset: ${config.h26xPreset}`,
            `• videoCodec: ${config.videoCodec}`,
            "",
            "**General Options:**",
            `• videosDir: ${config.videosDir}`,
            `• previewCacheDir: ${config.previewCacheDir}`,
            "",
            "**yt-dlp Options:**",
            `• ytdlpCookiesPath: ${config.ytdlpCookiesPath || '(not set)'}`,
            "",
            "Use `config <parameter>` to view a specific parameter",
            "Use `config <parameter> <value>` to change a parameter"
        ].join('\n');
        await this.sendInfo(context.message, 'Bot Configuration', configInfo);
    }
    async showParameter(context, parameter) {
        const key = Object.keys(config).find(k => k.toLowerCase() === parameter.toLowerCase());
        if (!key) {
            await this.sendError(context.message, `Unknown parameter: ${parameter}`);
            return;
        }
        const value = config[key];
        await this.sendInfo(context.message, `Config: ${key}`, `Current value: \`${value}\``);
    }
    async setParameter(context, parameter, value) {
        const key = Object.keys(config).find(k => k.toLowerCase() === parameter.toLowerCase());
        if (!key) {
            await this.sendError(context.message, `Unknown parameter: ${parameter}`);
            return;
        }
        try {
            switch (key) {
                case 'respect_video_params':
                case 'bitrateOverride':
                case 'hardwareAcceleratedDecoding':
                    const boolValue = parseBoolean(value);
                    config[key] = boolValue;
                    await this.sendSuccess(context.message, `Set ${key} to \`${boolValue}\``);
                    logger.info(`Config updated: ${key} = ${boolValue}`);
                    break;
                case 'width':
                case 'height':
                case 'fps':
                case 'bitrateKbps':
                case 'maxBitrateKbps':
                case 'maxWidth':
                case 'maxHeight':
                    const numValue = parseInt(value);
                    if (isNaN(numValue) || numValue < 0) {
                        await this.sendError(context.message, `Invalid number value: ${value}. Must be non-negative.`);
                        return;
                    }
                    if (['width', 'height', 'fps', 'bitrateKbps', 'maxBitrateKbps'].includes(key) && numValue === 0) {
                        await this.sendError(context.message, `Invalid number value: ${value}. Must be greater than 0.`);
                        return;
                    }
                    config[key] = numValue;
                    await this.sendSuccess(context.message, `Set ${key} to \`${numValue}\``);
                    logger.info(`Config updated: ${key} = ${numValue}`);
                    break;
                case 'videoCodec':
                    const codec = parseVideoCodec(value);
                    if (!codec) {
                        await this.sendError(context.message, `Invalid video codec. Valid options: VP8, H264, H265`);
                        return;
                    }
                    config.videoCodec = codec;
                    await this.sendSuccess(context.message, `Set videoCodec to \`${codec}\``);
                    logger.info(`Config updated: videoCodec = ${codec}`);
                    break;
                case 'h26xPreset':
                    const preset = parsePreset(value);
                    if (!preset) {
                        await this.sendError(context.message, `Invalid preset. Valid options: ultrafast, superfast, veryfast, faster, fast, medium, slow, slower, veryslow`);
                        return;
                    }
                    config.h26xPreset = preset;
                    await this.sendSuccess(context.message, `Set h26xPreset to \`${preset}\``);
                    logger.info(`Config updated: h26xPreset = ${preset}`);
                    break;
                case 'videosDir':
                case 'previewCacheDir':
                case 'ytdlpCookiesPath':
                    config[key] = value;
                    await this.sendSuccess(context.message, `Set ${key} to \`${value}\``);
                    logger.info(`Config updated: ${key} = ${value}`);
                    break;
                default:
                    await this.sendError(context.message, `Cannot modify parameter: ${key}`);
                    return;
            }
        }
        catch (error) {
            logger.error(`Error setting config parameter ${parameter}:`, error);
            await this.sendError(context.message, `Failed to set ${parameter}: ${error}`);
        }
    }
    isAdmin(userId) {
        if (!config.adminIds || config.adminIds.length === 0) {
            return true;
        }
        return config.adminIds.includes(userId);
    }
}
//# sourceMappingURL=config.js.map