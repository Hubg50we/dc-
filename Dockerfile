# ========================================================
# dc- 24/7 Dedicated Server Container
# Multi-Platform Discord Voice & Media Streaming Server
# ========================================================
FROM python:3.11-slim

# Install system dependencies: FFmpeg (audio/video DSP & streaming), Node.js, npm, curl
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    curl \
    nodejs \
    npm \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt yt-dlp

# Install StreamBot dependencies
COPY streambot/package*.json ./streambot/
RUN cd streambot && npm install --omit=dev --ignore-scripts || true

# Copy full application codebase
COPY . .

# Expose Web UI ports (8000 for Koyeb/cloud, 5000 for local/Render) and StreamBot relay port (8080)
EXPOSE 8000 5000 8080

# Environment variables
ENV PYTHONUNBUFFERED=1
ENV PORT=8000

# Default healthcheck supporting both 8000 and 5000
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8000}/api/status || curl -f http://localhost:5000/api/status || exit 1

# Start the dc- server
CMD ["python", "main.py"]
