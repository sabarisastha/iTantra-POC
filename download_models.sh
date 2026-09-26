#!/usr/bin/env bash
# ============================================================
# iTantra – Model Download Script
# Models:
#   STT : IndicConformer (parismitaglobalsolutions/indicconformer-sherpa-onnx)
#   TTS : RASA-13 (MatiasLin/sherpa-onnx-vits-rasa-13)
#         MMS   (csukuangfj/vits-mms-<lang>)
# ============================================================

set -e

echo "=== 1. Downloading STT (IndicConformer) ==="
mkdir -p models/stt/hi models/stt/en
wget -q --show-progress -O models/stt/tokens.txt https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/tokens.txt
wget -q --show-progress -O models/stt/available_languages.json https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/available_languages.json

# Download Hindi and English models
wget -q --show-progress -O models/stt/hi/model.int8.onnx https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi/model.int8.onnx
wget -q --show-progress -O models/stt/en/model.int8.onnx https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/en/model.int8.onnx
cp models/stt/hi/model.int8.onnx models/stt/model.int8.onnx

echo "=== 2. Downloading TTS (RASA-13) ==="
mkdir -p models/tts/rasa
wget -q --show-progress -O models/tts/rasa/model.onnx https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/main/model.onnx
wget -q --show-progress -O models/tts/rasa/tokens.txt https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/main/tokens.txt

echo "=== 3. Downloading TTS (MMS - English) ==="
mkdir -p models/tts/mms/eng
wget -q --show-progress -O models/tts/mms/eng/model.onnx https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/model.onnx
wget -q --show-progress -O models/tts/mms/eng/tokens.txt https://huggingface.co/csukuangfj/vits-mms-eng/resolve/main/tokens.txt
cp models/tts/mms/eng/model.onnx models/tts/mms/model.onnx
cp models/tts/mms/eng/tokens.txt models/tts/mms/tokens.txt

echo "=== All models downloaded successfully! ==="
