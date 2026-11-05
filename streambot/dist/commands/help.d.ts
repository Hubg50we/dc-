import { BaseCommand } from "./base.js";
import { CommandContext } from "../types/index.js";
import { CommandManager } from "./manager.js";
export default class HelpCommand extends BaseCommand {
    private commandManager;
    name: string;
    description: string;
    usage: string;
    constructor(commandManager: CommandManager);
    execute(context: CommandContext): Promise<void>;
}
