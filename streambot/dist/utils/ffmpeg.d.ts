export declare function ffmpegScreenshot(video: string): Promise<string[]>;
export declare function formatFfmpegHeaders(headers: Record<string, string>): string;
export declare function getVideoParams(videoPath: string, headers?: Record<string, string>): Promise<{
    width: number;
    height: number;
    bitrate: string;
    maxbitrate: string;
    fps: number;
}>;
