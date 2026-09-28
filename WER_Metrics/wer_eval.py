#!/usr/bin/env python3
"""
iTantra WER Evaluation Script
Usage:
    python wer_eval.py --hyp hypotheses.txt --ref references.txt
"""

import argparse
from jiwer import wer, cer

# Compatibility for jiwer v4.0+ where compute_measures was replaced by process_words
try:
    from jiwer import compute_measures
    HAS_COMPUTE_MEASURES = True
except ImportError:
    from jiwer import process_words
    HAS_COMPUTE_MEASURES = False

def load_lines(path):
    with open(path, "r", encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]

def main():
    parser = argparse.ArgumentParser(description="Calculate WER / CER for iTantra STT")
    parser.add_argument("--hyp", required=True, help="File with STT output (one sentence per line)")
    parser.add_argument("--ref", required=True, help="File with ground-truth sentences")
    args = parser.parse_args()

    hypotheses = load_lines(args.hyp)
    references = load_lines(args.ref)

    if len(hypotheses) != len(references):
        print(f"WARNING: {len(hypotheses)} hypotheses vs {len(references)} references")
        min_len = min(len(hypotheses), len(references))
        hypotheses = hypotheses[:min_len]
        references = references[:min_len]

    # Overall metrics
    if HAS_COMPUTE_MEASURES:
        measures = compute_measures(references, hypotheses)
        overall_wer = measures['wer'] * 100
        substitutions = measures['substitutions']
        insertions = measures['insertions']
        deletions = measures['deletions']
    else:
        out = process_words(references, hypotheses)
        overall_wer = out.wer * 100
        substitutions = out.substitutions
        insertions = out.insertions
        deletions = out.deletions

    overall_cer = cer(references, hypotheses) * 100

    print("\n========== iTantra STT Evaluation ==========")
    print(f"Sentences evaluated : {len(references)}")
    print(f"WER                 : {overall_wer:.2f}%")
    print(f"CER                 : {overall_cer:.2f}%")
    print(f"Substitutions       : {substitutions}")
    print(f"Insertions          : {insertions}")
    print(f"Deletions           : {deletions}")
    print("============================================\n")

    # Per-sentence view
    print("Per-sentence breakdown:")
    for i, (ref, hyp) in enumerate(zip(references, hypotheses), 1):
        s_wer = wer(ref, hyp)
        print(f"{i:02d}. WER={s_wer*100:5.1f}%  | REF: {ref}")
        print(f"       {'':8}  | HYP: {hyp}\n")

if __name__ == "__main__":
    main()
