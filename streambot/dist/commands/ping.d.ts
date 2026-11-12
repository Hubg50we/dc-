import { BaseCommand } from "./base.js";
import { CommandContext } from "../types/index.js";
export default class PingCommand extends BaseCommand {
    name: string;
    description: string;
    usage: string;
    execute(context: CommandContext): Promise<void>;
}
