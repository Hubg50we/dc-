import { BaseCommand } from "./base.js";
export default class QueueCommand extends BaseCommand {
    name = "queue";
    description = "Display the current video queue";
    usage = "queue";
    async execute(context) {
        const queueItems = context.streamingService.getQueueService().getQueue();
        const currentItem = context.streamingService.getQueueService().getCurrent();
        const queueStatus = context.streamingService.getQueueService().getQueueStatus();
        if (queueItems.length === 0) {
            await this.sendInfo(context.message, 'Queue', 'The queue is currently empty.');
            return;
        }
        let queueText = `📋 **Queue** (${queueItems.length} item${queueItems.length !== 1 ? 's' : ''})\n\n`;
        if (queueStatus.isPlaying && currentItem) {
            const status = currentItem.resolved ? '▶️' : '⏳';
            const title = currentItem.resolved ? currentItem.title : `${currentItem.title} (resolving...)`;
            queueText += `${status} **Currently Playing:**\n\`${title}\` (requested by ${currentItem.requestedBy})\n\n`;
        }
        queueText += '**Up Next:**\n';
        const upcomingItems = queueItems.filter(item => !queueStatus.isPlaying || item.id !== currentItem?.id);
        if (upcomingItems.length === 0) {
            if (queueStatus.isPlaying && currentItem) {
                queueText += '*No upcoming items*\n';
            }
            else {
                queueText += '*Queue is empty*\n';
            }
        }
        else {
            upcomingItems.forEach((item, index) => {
                const position = queueStatus.isPlaying ? index + 1 : index;
                const addedTime = item.addedAt.toLocaleTimeString();
                const status = item.resolved ? '' : '⏳';
                const title = item.resolved ? item.title : `${item.title} (pending)`;
                queueText += `${position + 1}. ${status} \`${title}\` (by ${item.requestedBy}) - Added at ${addedTime}\n`;
            });
        }
        if (queueText.length > 1900) {
            const firstPart = queueText.substring(0, 1900) + '...';
            const secondPart = '...(continued)\n' + queueText.substring(1900);
            await context.message.channel.send(firstPart);
            await context.message.channel.send(secondPart);
        }
        else {
            await context.message.channel.send(queueText);
        }
    }
}
//# sourceMappingURL=queue.js.map