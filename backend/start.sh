#!/bin/bash
set -e

echo "Starting LiveKit Voice Agent Worker..."
python -u agent.py dev &

echo "Starting FastAPI Gateway on port 7860..."
PYTHONUNBUFFERED=1 uvicorn main:app --host 0.0.0.0 --port 7860
