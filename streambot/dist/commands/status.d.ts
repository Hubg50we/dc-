import { BaseCommand } from "./base.js";
import { CommandContext } from "../types/index.js";
export default class StatusCommand extends BaseCommand {
    name: string;
    description: string;
    usage: string;
    execute(context: CommandContext): Promise<void>;
}
