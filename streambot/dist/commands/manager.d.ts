import { Command, CommandContext } from "../types/index.js";
export declare class CommandManager {
    private commands;
    private aliases;
    constructor();
    private loadCommands;
    private isCommand;
    getCommand(name: string): Command | null;
    getAllCommands(): Command[];
    executeCommand(commandName: string, context: CommandContext): Promise<boolean>;
    getCommandList(): string;
}
