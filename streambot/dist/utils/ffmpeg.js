import config from "../config.js";
import ffmpeg from "fluent-ffmpeg";
const ffmpegRunning = {};
export async function ffmpegScreenshot(video) {
    return new Promise((resolve, reject) => {
        if (ffmpegRunning[video]) {
            const wait = (images) => {
                if (ffmpegRunning[video] == false) {
                    resolve(images);
                }
                setTimeout(() => wait(images), 100);
            };
            wait([]);
            return;
        }
        ffmpegRunning[video] = true;
        const ts = ['10%', '30%', '50%', '70%', '90%'];
        const images = [];
        const takeScreenshots = (i) => {
            if (i >= ts.length) {
                ffmpegRunning[video] = false;
                resolve(images);
                return;
            }
            ffmpeg(`${config.videosDir}/${video}`)
                .on("end", () => {
                const screenshotPath = `${config.previewCacheDir}/${video}-${i + 1}.jpg`;
                images.push(screenshotPath);
                takeScreenshots(i + 1);
            })
                .on("error", (err) => {
                ffmpegRunning[video] = false;
                reject(err);
            })
                .screenshots({
                count: 1,
                filename: `${video}-${i + 1}.jpg`,
                timestamps: [ts[i]],
                folder: config.previewCacheDir
            });
        };
        takeScreenshots(0);
    });
}
export function formatFfmpegHeaders(headers) {
    return Object.entries(headers)
        .map(([key, value]) => `${key}: ${value}`)
        .join("\r\n") + "\r\n";
}
export async function getVideoParams(videoPath, headers) {
    const probeOptions = headers && Object.keys(headers).length > 0
        ? ['-headers', formatFfmpegHeaders(headers)]
        : [];
    return new Promise((resolve, reject) => {
        ffmpeg.ffprobe(videoPath, probeOptions, (err, metadata) => {
            if (err) {
                return reject(err);
            }
            const videoStream = metadata.streams.find(stream => stream.codec_type === 'video');
            if (videoStream && videoStream.width && videoStream.height && videoStream.bit_rate) {
                const rFrameRate = videoStream.r_frame_rate || videoStream.avg_frame_rate;
                if (rFrameRate) {
                    const [numerator, denominator] = rFrameRate.split('/').map(Number);
                    videoStream.fps = numerator / denominator;
                }
                else {
                    videoStream.fps = 0;
                }
                resolve({ width: videoStream.width, height: videoStream.height, bitrate: videoStream.bit_rate, maxbitrate: videoStream.maxBitrate, fps: videoStream.fps });
            }
            else {
                reject(new Error('Unable to get Resolution.'));
            }
        });
    });
}
//# sourceMappingURL=ffmpeg.js.map