import express, { Router } from "express";
import session from "express-session";
import expressLayouts from "express-ejs-layouts";
import path from "path";
import fs from "fs";
import https from "https";
import http from "http";
import config from "../config.js";
import logger from "../utils/logger.js";
import { stringify, prettySize } from "./utils/helpers.js";

// Import middleware
import { requireAuth } from "./middleware/auth.js";

// Import route handlers
import authRoutes from "./routes/auth.js";
import dashboardRoutes from "./routes/dashboard.js";
import uploadRoutes from "./routes/upload.js";
import previewRoutes from "./routes/preview.js";

export function startServer(streamingService: any, streamStatus: any, client: any) {
	const app = express();

	app.use(express.json());
	app.use(express.urlencoded({ extended: true }));

	// Helper to create mock Discord message for headless API calls
	const getMockMessage = () => ({
		author: { username: client?.user?.username || "StreamBot" },
		react: async (emoji: string) => {},
		reply: async (msg: string) => ({
			delete: async () => {},
			edit: async (m: string) => {}
		}),
		channel: {
			send: async (msg: any) => {}
		}
	});

	// ==========================================
	// Unauthenticated Public REST API for Web UI
	// ==========================================
	const api = Router();

	api.get("/status", (req, res) => {
		res.json({
			success: true,
			online: !!client?.user,
			user: client?.user ? { tag: client.user.tag, id: client.user.id } : null,
			joined: streamStatus?.joined || false,
			playing: streamStatus?.playing || false,
			channel: streamStatus?.channelInfo || {},
			queue: streamStatus?.queue || []
		});
	});

	api.post("/play", async (req, res) => {
		const { url, title } = req.body;
		if (!url) return res.status(400).json({ success: false, error: "URL is required" });

		try {
			logger.info(`[REST API] Play request received: ${url} (${title || "Media Stream"})`);
			const mockMsg = getMockMessage();
			await streamingService.addToQueue(mockMsg, url, title);
			if (!streamStatus.playing) {
				streamingService.playFromQueue(mockMsg).catch((e: any) => logger.error("[REST API] Play error:", e));
			}
			res.json({ success: true, message: `Started playback: ${title || url}`, url, title });
		} catch (err: any) {
			logger.error("[REST API] Play error:", err);
			res.status(500).json({ success: false, error: err.message || String(err) });
		}
	});

	api.post("/stop", async (req, res) => {
		try {
			logger.info("[REST API] Stop playback request received");
			streamStatus.manualStop = true;
			await streamingService.stopAndClearQueue();
			res.json({ success: true, message: "Playback stopped and queue cleared" });
		} catch (err: any) {
			logger.error("[REST API] Stop error:", err);
			res.status(500).json({ success: false, error: err.message || String(err) });
		}
	});

	api.post("/skip", async (req, res) => {
		try {
			logger.info("[REST API] Skip request received");
			const mockMsg = getMockMessage();
			await streamingService.skipCurrent(mockMsg);
			res.json({ success: true, message: "Skipped to next track" });
		} catch (err: any) {
			logger.error("[REST API] Skip error:", err);
			res.status(500).json({ success: false, error: err.message || String(err) });
		}
	});

	api.post("/sfx", async (req, res) => {
		const sound = String(req.body.sound || "scream").toLowerCase();
		try {
			const sfxPath = path.resolve(config.videosDir, `${sound}.mp3`);
			logger.info(`[REST API] Soundboard SFX request: ${sound} (${sfxPath})`);
			const mockMsg = getMockMessage();
			await streamingService.addToQueue(mockMsg, sfxPath, `SFX: ${sound.toUpperCase()}`);
			if (!streamStatus.playing) {
				streamingService.playFromQueue(mockMsg).catch((e: any) => logger.error("[REST API] SFX play error:", e));
			}
			res.json({ success: true, message: `Playing SFX ${sound.toUpperCase()}`, sound });
		} catch (err: any) {
			logger.error("[REST API] SFX error:", err);
			res.status(500).json({ success: false, error: err.message || String(err) });
		}
	});

	api.post("/tts", async (req, res) => {
		const text = String(req.body.text || "").trim();
		if (!text) return res.status(400).json({ success: false, error: "Text is required" });

		const ttsFile = path.resolve(config.videosDir, "tts.mp3");
		const ttsUrl = `https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=en&q=${encodeURIComponent(text)}`;

		https.get(ttsUrl, { headers: { "User-Agent": "Mozilla/5.0" } }, (ttsRes) => {
			const fileStream = fs.createWriteStream(ttsFile);
			ttsRes.pipe(fileStream);
			fileStream.on("finish", async () => {
				fileStream.close();
				try {
					logger.info(`[REST API] TTS Announcer request: "${text}"`);
					const mockMsg = getMockMessage();
					await streamingService.addToQueue(mockMsg, ttsFile, `TTS: ${text}`);
					if (!streamStatus.playing) {
						streamingService.playFromQueue(mockMsg).catch((e: any) => logger.error("[REST API] TTS play error:", e));
					}
					res.json({ success: true, message: `TTS broadcasted: "${text}"`, text });
				} catch (err: any) {
					logger.error("[REST API] TTS play error:", err);
					res.status(500).json({ success: false, error: err.message || String(err) });
				}
			});
		}).on("error", (err) => {
			logger.error("[REST API] TTS download error:", err);
			res.status(500).json({ success: false, error: err.message || String(err) });
		});
	});

	app.use("/api", api);

	// ==========================================
	// Authenticated Web Management Dashboard
	// ==========================================
	app.set("view engine", "ejs");
	app.set("views", path.join(process.cwd(), "src/server/views"));
	app.use(expressLayouts);
	app.set("layout", "layouts/main");

	app.use(session({
		secret: "streambot-2024",
		resave: false,
		saveUninitialized: true,
		cookie: { secure: process.env.NODE_ENV === "production" }
	}));

	app.use(express.static(path.join(process.cwd(), "src/server/public")));

	app.use((req, res, next) => {
		res.locals.stringify = stringify;
		res.locals.prettySize = prettySize;
		next();
	});

	app.use(requireAuth);
	app.use("/", authRoutes);
	app.use("/", dashboardRoutes);
	app.use("/", uploadRoutes);
	app.use("/", previewRoutes);

	if (!fs.existsSync(config.videosDir)) {
		fs.mkdirSync(config.videosDir);
	}
	if (!fs.existsSync(path.dirname(config.previewCacheDir))) {
		fs.mkdirSync(path.dirname(config.previewCacheDir), { recursive: true });
	}
	if (!fs.existsSync(config.previewCacheDir)) {
		fs.mkdirSync(config.previewCacheDir);
	}

	app.listen(config.server_port, () => {
		logger.info(`StreamBot REST API & Dashboard is running on port ${config.server_port}`);
	});

	return app;
}

export default startServer;
