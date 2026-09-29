# Pre-Merge Architectural Audit: `darika` vs `origin/sabari` (Bala Feature Branch)

## Executive Summary
This report presents a 100% read-only comparative analysis between our current branch (`HEAD` / `darika`) and Sabari's feature branch (merged into `origin/main` via PR #1 by author Balasubramanian, tracked across commits `16aa07b`, `37cad73`, `d9471fa`, `ab905bf`).

---

## 1. Remote Branch Identification & History Mapping
- **Branch Origin:** `origin/sabari` corresponds to PR #1 (`sabarisastha/Bala`) authored by `subramaniang172@gmail.com`.
- **Key Incoming Commits:**
  1. `16aa07b`: *Bala initial commit* (Base framing & session structure)
  2. `37cad73`: *Feature telemetry added* (`TelemetryState`, `DutyCycleScheduler`, Location telemetry in `Payload.kt`)
  3. `d9471fa`: *deflate compression integration* (FramedSocket DEFLATE frame compressed headers)
  4. `ab905bf`: *Custom Binary Protocol Implementation* (`BinaryProtocol`, `BinaryEncoder`, `BinaryDecoder`, `DeflateCompressor`)

---

## 2. Component-by-Component Architectural Comparison

### A. Session & Floor Control (`SessionController.kt`)
| Architectural Aspect | `darika` (HEAD) | `origin/sabari` (Bala) |
| :--- | :--- | :--- |
| **Floor State Ordering** | `sttEngine.stop()` (flushes VAD) $\rightarrow$ `_floorState = IDLE` | `_floorState = IDLE` $\rightarrow$ `sttEngine.stop()` **(Race Condition)** |
| **Telemetry Integration** | Standard session logging | Tracks `DutyCycleScheduler`, battery metrics, & GPS updates |
| **Disconnect Handling** | Safe reset of VAD pipelines & audio focus | Triggers `dutyCycleScheduler.reset()` |

> [!WARNING]
> **Critical Race Condition in Sabari's Code:** Sabari sets `_floorState = IDLE` prior to stopping `sttEngine`. If an incoming frame arrives before `stop()` completes, the floor state machine enters an invalid concurrent state. **`darika`'s execution order MUST be preserved.**

### B. VAD & Audio Pipeline
| Component | `darika` (HEAD) | `origin/sabari` (Bala) |
| :--- | :--- | :--- |
| **Silero VAD & Noise Floor** | Production-ready `SileroVadEngine`, `AdaptiveNoiseFloorVadPipeline` | Standard text/audio state |
| **Audio Routing & Focus** | Transient Exclusive Audio Focus, DND Bypass, Max Volume | Basic audio stream routing |
| **Lockscreen/WakeLock** | Full `PowerManager` WakeLock lifecycle management | Utterance completion listeners |

### C. Transport & Protocol Layer (`Payload.kt`, `BinaryProtocol.kt`)
- **`Payload.kt`:** Sabari adds 8 new optional telemetry/location fields (`gpsLocation`, `batteryLevel`, etc.) and `PayloadType.LOCATION_UPDATE`. This is a clean, backward-compatible superset.
- **Custom Binary Compression:** Sabari introduces `BinaryProtocol.kt`, `BinaryEncoder.kt`, `BinaryDecoder.kt`, and `DeflateCompressor.kt` (magic header `0x4954`).

---

## 3. Merge Conflict Points & Directives

1. **`SessionController.kt` (Lines 340-420, 550-575):**
   - *Conflict:* Floor release & state reset logic collide with telemetry duty cycle calls.
   - *Resolution:* Port `DutyCycleScheduler` hooks, but maintain `darika`'s STT stop $\rightarrow$ VAD flush $\rightarrow$ IDLE state transition.
2. **`FramedSocket.kt` / `BluetoothSocketTransport.kt`:**
   - *Conflict:* Sabari wraps payload frames in DEFLATE compression.
   - *Resolution:* Feature-gate `DeflateCompressor` so text-framed legacy sockets remain fully supported.

---

## 4. Strategic Step-by-Step Reconciliation Plan
1. **Preserve Audio & VAD Core:** Retain `darika`'s `SileroVadEngine`, `AdaptiveNoiseFloorVadPipeline`, and `AudioPlayer` unchanged.
2. **Adopt Telemetry & Location:** Integrate Sabari's `Payload.kt` additions and `TelemetryState.kt` / `DutyCycleScheduler.kt`.
3. **Safe Protocol Wrapper:** Incorporate `BinaryEncoder` / `BinaryDecoder` as an opt-in binary framing layer.
