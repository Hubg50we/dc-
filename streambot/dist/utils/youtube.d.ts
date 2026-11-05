import { YouTubeVideo } from '../types/index.js';
export declare class Youtube {
    getVideoInfo(url: string): Promise<YouTubeVideo | null>;
    searchAndGetPageUrl(title: string): Promise<{
        pageUrl: string | null;
        title: string | null;
    }>;
    search(query: string, limit?: number): Promise<string[]>;
    getLiveStreamUrl(youtubePageUrl: string): Promise<string | null>;
}
