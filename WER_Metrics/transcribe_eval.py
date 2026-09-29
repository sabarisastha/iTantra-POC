#!/usr/bin/env python3
"""
iTantra STT Transcription & Evaluation Script
Transcribes 16kHz WAV audio files in an input directory using sherpa-onnx IndicConformer,
saves hypotheses to a text file, and runs WER evaluation against reference sentences.

Usage:
    python transcribe_eval.py --audio-dir audio_tests/hi --lang hi --ref references_hi.txt --hyp hypotheses_hi.txt
    python transcribe_eval.py --audio-dir audio_tests/ta --lang ta --ref references_ta.txt --hyp hypotheses_ta.txt
"""

import os
import glob
import argparse
import wave
import numpy as np

def load_audio(wave_filename):
    with wave.open(wave_filename, "rb") as f:
        assert f.getnchannels() == 1, f"{wave_filename} must be mono (1 channel)"
        assert f.getsampwidth() == 2, f"{wave_filename} must be 16-bit PCM (sample width 2 bytes)"
        sample_rate = f.getframerate()
        num_samples = f.getnframes()
        samples = f.readframes(num_samples)
        samples_np = np.frombuffer(samples, dtype=np.int16).astype(np.float32) / 32768.0
        return samples_np, sample_rate

def main():
    parser = argparse.ArgumentParser(description="Transcribe audio folder and compute WER")
    parser.add_argument("--audio-dir", required=True, help="Directory containing numbered WAV files (01.wav, 02.wav, ...)")
    parser.add_argument("--lang", default="hi", choices=["hi", "en", "ta"], help="Language model subfolder (default: hi)")
    parser.add_argument("--ref", required=True, help="Path to reference text file (e.g. references_hi.txt)")
    parser.add_argument("--hyp", default="hypotheses.txt", help="Path to save output hypothesis text file")
    args = parser.parse_args()

    import sherpa_onnx

    model_dir = os.path.join("models", "stt", args.lang)
    model_path = os.path.join(model_dir, "model.int8.onnx")
    tokens_path = os.path.join("models", "stt", "tokens.txt")

    if not os.path.exists(model_path):
        # Fallback to default models/stt/model.int8.onnx
        model_path = os.path.join("models", "stt", "model.int8.onnx")

    print(f"Loading Sherpa-ONNX model from: {model_path}")

    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
        tokens=tokens_path,
        encoder="",
        decoder="",
        joiner="",
        num_threads=2,
        sample_rate=16000,
        feature_dim=80,
        provider="cpu",
        model_config=sherpa_onnx.OfflineModelConfig(
            transducer=sherpa_onnx.OfflineTransducerModelConfig(
                encoder="",
                decoder="",
                joiner=""
            ),
            onnx=sherpa_onnx.OfflineOnnxModelConfig(
                model=model_path
            )
        )
    ) if hasattr(sherpa_onnx, 'OfflineModelConfig') else None

    # Find WAV files
    wav_files = sorted(glob.glob(os.path.join(args.audio_dir, "*.wav")))
    if not wav_files:
        print(f"No .wav files found in {args.audio_dir}")
        return

    print(f"Found {len(wav_files)} audio files in {args.audio_dir}")
    hypotheses = []

    for wav_file in wav_files:
        samples, sr = load_audio(wav_file)
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, samples)
        recognizer.decode_stream(stream)
        text = stream.result.text.strip()
        print(f"[{os.path.basename(wav_file)}] -> {text}")
        hypotheses.append(text)

    # Write hypotheses to file
    with open(args.hyp, "w", encoding="utf-8") as f:
        for line in hypotheses:
            f.write(line + "\n")
    print(f"\nHypotheses saved to: {args.hyp}")

    # Run WER evaluation
    os.system(f"python3 wer_eval.py --ref {args.ref} --hyp {args.hyp}")

if __name__ == "__main__":
    main()
