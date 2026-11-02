#!/bin/bash
# ========================================================
# Run dc- in pure terminal mode (No Web Browser)
# ========================================================
DIR="$(dirname "$0")"
cd "$DIR"
bash start.sh --no-web "$@"
