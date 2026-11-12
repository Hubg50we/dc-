import ytdl_dlp from './yt-dlp.js';
import logger from './logger.js';
import yts from 'play-dl';
export class Youtube {
    async getVideoInfo(url) {
        try {
            const videoData = await ytdl_dlp(url, { dumpSingleJson: true, noPlaylist: true });
            if (typeof videoData === 'object' && videoData !== null && videoData.id && videoData.title) {
                return {
                    id: videoData.id,
                    title: videoData.title,
                    formats: [],
                    videoDetails: {
                        isLiveContent: videoData.is_live === true || videoData.live_status === 'is_live'
                    }
                };
            }
            logger.warn(`Failed to parse video info from yt-dlp for URL: ${url}. Data: ${JSON.stringify(videoData)}`);
            return null;
        }
        catch (error) {
            logger.error(`Failed to get video info using yt-dlp for URL ${url}:`, error);
            return null;
        }
    }
    async searchAndGetPageUrl(title) {
        try {
            const results = await yts.search(title, { limit: 1 });
            if (results.length === 0 || !results[0]?.url) {
                logger.warn(`No video found on YouTube for title: "${title}" using play-dl.`);
                return { pageUrl: null, title: null };
            }
            return { pageUrl: results[0].url, title: results[0].title || null };
        }
        catch (error) {
            logger.error(`Video search for page URL failed for title "${title}":`, error);
            return { pageUrl: null, title: null };
        }
    }
    async search(query, limit = 5) {
        try {
            const searchResults = await yts.search(query, { limit });
            return searchResults.map((video, index) => `${index + 1}. \`${video.title}\``);
        }
        catch (error) {
            logger.warn(`No videos found with the given title: "${query}"`);
            return [];
        }
    }
    async getLiveStreamUrl(youtubePageUrl) {
        try {
            const streamUrl = await ytdl_dlp(youtubePageUrl, {
                getUrl: true,
                format: 'best[protocol=https]/best[protocol=http]/best',
                noPlaylist: true,
                quiet: true,
            });
            if (typeof streamUrl === 'string' && streamUrl.trim()) {
                logger.info(`Got live stream URL for ${youtubePageUrl}: ${streamUrl.trim()}`);
                return streamUrl.trim();
            }
            logger.warn(`yt-dlp did not return a valid live stream URL for: ${youtubePageUrl}. Received: ${streamUrl}`);
            return null;
        }
        catch (error) {
            logger.error(`Failed to get live stream URL using yt-dlp for ${youtubePageUrl}:`, error);
            return null;
        }
    }
}
//# sourceMappingURL=youtube.js.map