import { YTFlags } from "../types/index.js";
export declare function downloadExecutable(): Promise<void>;
export declare function exec(url: string, options?: Partial<YTFlags>, spawnOptions?: Record<string, any>): import("node:child_process").ChildProcessByStdio<null, import("node:stream").Readable, import("node:stream").Readable>;
export default function ytdl(url: string, options?: Partial<YTFlags>, spawnOptions?: Record<string, any>): Promise<unknown>;
export declare function downloadToTempFile(url: string, options?: Partial<YTFlags>): Promise<string>;
export declare function checkForUpdatesAndUpdate(): Promise<void>;
