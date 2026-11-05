import { BaseCommand } from "./base.js";
import { CommandContext } from "../types/index.js";
export default class YTSearchCommand extends BaseCommand {
    name: string;
    description: string;
    usage: string;
    private mediaService;
    constructor();
    execute(context: CommandContext): Promise<void>;
}
