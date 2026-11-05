import { Message, ActivityOptions } from "discord.js-selfbot-v13";
export declare const DiscordUtils: {
    status_idle(): ActivityOptions;
    status_watch(name: string): ActivityOptions;
    sendError(message: Message, error: string): Promise<void>;
    sendSuccess(message: Message, description: string): Promise<void>;
    sendInfo(message: Message, title: string, description: string): Promise<void>;
    sendPlaying(message: Message, title: string): Promise<void>;
    sendFinishMessage(message: Message): Promise<void>;
    sendList(message: Message, items: string[], type?: string): Promise<void>;
};
export declare const ErrorUtils: {
    handleError(error: any, context: string, message?: Message): Promise<void>;
    withErrorHandling<T>(operation: () => Promise<T>, context: string, message?: Message): Promise<T | null>;
};
export declare const GeneralUtils: {
    isValidUrl(input: string): boolean;
    isLocalFile(filePath: string): boolean;
};
