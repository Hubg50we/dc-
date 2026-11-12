import { Command, CommandContext } from "../types/index.js";
export declare abstract class BaseCommand implements Command {
    abstract name: string;
    abstract description: string;
    abstract usage: string;
    aliases?: string[];
    constructor(commandManager?: any);
    abstract execute(context: CommandContext): Promise<void>;
    protected sendError(message: any, error: string): Promise<void>;
    protected sendSuccess(message: any, description: string): Promise<void>;
    protected sendInfo(message: any, title: string, description: string): Promise<void>;
    protected sendList(message: any, items: string[], type?: string): Promise<void>;
    protected sendPlaying(message: any, title: string): Promise<void>;
    protected sendFinishMessage(message: any): Promise<void>;
}
