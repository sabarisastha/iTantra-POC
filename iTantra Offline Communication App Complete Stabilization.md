# iTantra — COMPLETE APP STABILIZATION, BLUETOOTH IMPLEMENTATION & AUDIO RELIABILITY

You are working on my existing Android Kotlin project for the **iTantra / Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links** system.

I have already implemented and tested a working version, but it is currently unreliable in real device-to-device testing.

Your job is NOT to create a new app from scratch.

Your job is to:

1. Understand the existing architecture completely.
2. Identify the actual root causes of the current bugs.
3. Fix the existing implementation without unnecessarily redesigning working components.
4. Implement Bluetooth as a reliable primary offline transport.
5. Keep Wi-Fi Direct as a secondary/fallback transport where appropriate.
6. Make PTT / Walkie-Talkie mode reliable.
7. Make Phone/Full-Duplex mode reliable.
8. Make STT → transport → TTS/audio playback reliable.
9. Add proper connection recovery, timeout handling, retry mechanisms and lifecycle cleanup.
10. Detect and fix unknown bugs that become visible while auditing the system.
11. Preserve the existing UI and existing application functionality unless a change is required for correctness.
12. Do not give me theoretical suggestions only. Make the actual code changes.

---

# 1. PROJECT OBJECTIVE

This application is an **offline communication system** intended for disaster/distress scenarios.

Two Android devices must be able to communicate without:

- Internet
- Cloud
- Mobile data
- External Wi-Fi router
- External hotspot
- Any server on the Internet

Communication should work directly between nearby devices.

The application supports:

### Mode A — Walkie-Talkie / PTT

Half-duplex communication.

One user gets the communication floor, speaks, the system performs STT, sends the resulting data to the other device, and the receiver plays it through TTS/audio.

The floor-control mechanism must prevent both users from transmitting simultaneously.

### Mode B — Phone / Full-Duplex

Both devices can send and receive audio/data independently.

Both directions must work:

Device A → Device B

AND

Device B → Device A

Neither direction may silently fail.

---

# 2. VERY IMPORTANT DEVELOPMENT RULE

DO NOT immediately start modifying random files.

First inspect the entire project.

Understand:

- package structure
- transport architecture
- SessionController
- ViewModels
- Compose screens
- navigation
- audio recorder
- audio player
- STT engine
- TTS engine
- VAD
- transport implementations
- Wi-Fi Direct
- Bluetooth
- serialization
- coroutine scopes
- lifecycle handling
- permissions
- connection state
- floor-control state machine
- model loading
- PCM pipeline

Create a dependency map before making major changes.

You must understand:

```text
UI
 ↓
ViewModel
 ↓
SessionController
 ↓
TransportChannel
 ↓
Bluetooth / Wi-Fi Direct
 ↓
Remote Device
 ↓
Transport
 ↓
SessionController
 ↓
STT / TTS / AudioPlayer
```

Do NOT bypass `TransportChannel` from higher-level code.

The transport layer must remain replaceable.

---

# 3. CURRENT MAJOR PROBLEMS

The following problems have already been observed during real device testing.

Treat them as symptoms, NOT automatically as the final root causes.

## PROBLEM 1 — CONNECTION IS INTERMITTENT

Sometimes:

Device A sees Device B

but

Device B does not properly see/connect to Device A.

Other times both devices work.

Other times connection appears established but communication does not work.

Investigate:

- peer discovery
- Bluetooth discovery
- Wi-Fi Direct discovery
- connection state transitions
- stale peer lists
- lifecycle
- permissions
- socket creation
- socket acceptance
- IP addressing
- Group Owner/client roles
- connection race conditions
- coroutine cancellation
- duplicate connection attempts
- reconnect behavior
- transport cleanup

---

# 4. WI-FI DIRECT REQUIREMENT

Wi-Fi Direct must NOT require:

- router
- external hotspot
- Internet

The devices must communicate directly.

Audit the existing Wi-Fi Direct implementation.

Verify that the socket uses the actual Wi-Fi Direct connection information rather than assuming that normal Wi-Fi networking is available.

When a Wi-Fi Direct group is formed:

- correctly determine Group Owner
- correctly determine client
- correctly obtain Group Owner address
- correctly establish TCP socket
- correctly start server/client
- correctly handle reconnection

Do not make assumptions about fixed IP addresses.

Use the actual connection information provided by Android.

---

# 5. WI-FI DISCOVERY

Current discovery appears to behave inconsistently.

Audit:

```text
discoverPeers()
requestPeers()
WIFI_P2P_PEERS_CHANGED_ACTION
WIFI_P2P_CONNECTION_CHANGED_ACTION
WIFI_P2P_THIS_DEVICE_CHANGED_ACTION
```

Make discovery robust.

Requirements:

- discovery should not depend on only one call
- discovery should be restarted when appropriate
- stale peers must be cleared appropriately
- receiver lifecycle must be correct
- receiver must not leak
- receiver must not silently stop receiving events
- discovery must stop when no longer required
- discovery must restart after failure
- UI must always receive current device state

Do not create an uncontrolled infinite discovery loop.

Use coroutine cancellation and lifecycle-aware logic.

---

# 6. IMPORTANT JOB MANAGEMENT BUG

Audit the existing connection job architecture.

Do NOT reuse one coroutine Job for multiple unrelated responsibilities.

Separate jobs where necessary, for example:

```kotlin
p2pDiscoveryJob
p2pConnectionJob
socketServerJob
socketClientJob
receiveLoopJob
reconnectJob
```

Exact naming is up to you.

The important requirement is:

One logical operation must not accidentally cancel another unrelated operation.

For example:

```text
connectTo()
```

must not accidentally cancel:

```text
socket setup
receive loop
reconnection
```

unless that is explicitly intended.

---

# 7. SERVER SOCKET RELIABILITY

Audit the server-side socket implementation.

It must handle:

- BindException
- port already in use
- socket closed
- connection refused
- peer disconnect
- connection timeout
- rapid reconnect
- app restart
- previous socket not completely released

Implement controlled retry/backoff.

Do not retry forever.

Example conceptual behavior:

```text
attempt 1
 ↓
failure
 ↓
short delay
 ↓
attempt 2
 ↓
failure
 ↓
backoff
 ↓
attempt 3
```

After maximum retries:

```text
ConnectionState.Error(...)
```

must be emitted.

The UI must not remain stuck in:

```text
Connecting
```

forever.

---

# 8. TRANSPORT PROTOCOL

The existing transport uses framed messages.

Current concept:

```text
MAGIC
VERSION
FRAME TYPE
PAYLOAD LENGTH
PAYLOAD
```

Preserve this concept unless you identify a serious flaw.

Audit:

```kotlin
FramedSocket
FrameType
Payload
PayloadType
TransportChannel
```

Make framing robust.

Validate:

- magic
- protocol version
- frame type
- payload length
- malformed payload
- oversized payload
- incomplete payload
- socket EOF
- timeout
- disconnect
- corrupted frame

Never allocate dangerous amounts of memory based on an untrusted length field.

Never allow malformed input to crash the application.

---

# 9. CRITICAL RECEIVE FLOW BUG

Audit every:

```kotlin
MutableSharedFlow
StateFlow
Flow
collect()
collectLatest()
launchIn()
```

especially:

```text
onPayloadReceived
onPcmReceived
```

Do not allow important control/data frames to disappear because the collector was not ready.

Pay special attention to:

```kotlin
MutableSharedFlow<Payload>()
```

with:

```text
replay = 0
```

Determine whether this can cause:

```text
connection succeeds
↓
remote sends FLOOR_GRANTED / SPEECH
↓
collector not yet attached
↓
message disappears
↓
session appears dead
```

Fix the architecture rather than blindly increasing buffer size.

Control messages and audio data may require different buffering strategies.

Do NOT use a giant replay buffer for PCM audio.

---

# 10. PAYLOAD RELIABILITY

Classify messages.

### Control messages

Examples:

```text
REQUEST_FLOOR
FLOOR_GRANTED
FLOOR_DENIED
RELEASE_FLOOR
MODE_CHANGE
CONTROL
```

These are important.

They should not silently disappear.

Consider:

- sequence numbers
- acknowledgements
- timeout
- retry
- duplicate detection
- state recovery

where appropriate.

### Speech messages

These may require ordered delivery.

### PCM audio

PCM is time-sensitive.

Do NOT blindly retry old PCM indefinitely.

Old audio is usually worse than dropping a stale packet.

Design the transport accordingly.

---

# 11. CONNECTION HANDSHAKE

Implement a clear connection lifecycle.

Conceptually:

```text
IDLE
 ↓
DISCOVERING
 ↓
CONNECTING
 ↓
SOCKET_CONNECTED
 ↓
HANDSHAKE
 ↓
CONNECTED
```

Handshake should exchange enough information to verify:

- protocol version
- device/session identity
- supported transport
- supported modes
- language capability if required
- session identifier

Then:

```text
CONNECTED
```

should only be exposed after the connection is actually usable.

Do not mark the UI as connected merely because a socket object was created.

---

# 12. HEARTBEAT / KEEPALIVE

Implement a lightweight heartbeat for an established session.

Purpose:

Detect:

```text
dead socket
dead peer
Wi-Fi transition
Bluetooth disconnect
half-open connection
```

Do not send heartbeats at an unnecessarily high frequency.

When heartbeat fails:

```text
CONNECTED
 ↓
DISCONNECTED / RECOVERING
 ↓
RECONNECTING
```

Implement controlled recovery.

---

# 13. BLUETOOTH MUST ACTUALLY BE IMPLEMENTED

Current:

```kotlin
BluetoothSocketTransport
```

is only a stub.

It currently throws:

```kotlin
UnsupportedOperationException
```

This must be fully implemented.

Use Android's supported Bluetooth Classic socket/RFCOMM mechanism if that is compatible with the project's minimum SDK and requirements.

Do NOT implement Bluetooth merely as another UI option.

It must integrate with:

```kotlin
TransportChannel
```

so higher-level code does not care whether the transport is:

```text
Bluetooth
```

or:

```text
Wi-Fi Direct
```

---

# 14. BLUETOOTH ARCHITECTURE

Implement:

```text
Bluetooth discovery
        ↓
device list
        ↓
user selects peer
        ↓
RFCOMM connection
        ↓
socket
        ↓
FramedSocket / equivalent framing
        ↓
TransportChannel
```

Support:

- advertising / discoverability where required
- discovery
- bonded devices
- connection
- server socket
- client socket
- accept
- connect
- disconnect
- reconnect
- cancellation
- cleanup

Avoid duplicate connections.

If both devices attempt to connect simultaneously, the implementation must resolve the situation deterministically.

Do not leave two competing sockets running.

---

# 15. BLUETOOTH PERMISSIONS

Audit AndroidManifest and runtime permission handling.

Depending on Android version, correctly handle relevant Bluetooth permissions.

Do not crash when permissions are missing.

The UI should show a meaningful state such as:

```text
Bluetooth permission required
```

instead of silently failing.

Also verify location/discovery requirements for the Android versions actually supported by this project.

Do not request unnecessary permissions.

---

# 16. TRANSPORT SELECTION

Create a clean transport strategy.

Preferred conceptual architecture:

```text
TransportManager
       |
       +---- BluetoothSocketTransport
       |
       +---- WifiDirectSocketTransport
```

The rest of the app uses:

```kotlin
TransportChannel
```

only.

The UI should not contain:

```kotlin
BluetoothSocket
WifiP2pManager
Socket
```

logic.

---

# 17. AUDIO SYSTEM — MOST IMPORTANT

Both communication modes must support reliable audio.

Current symptoms:

### PTT

Sometimes:

```text
A speaks
→
B receives
```

works.

Sometimes:

```text
A speaks
→
B receives nothing
```

or both sides become silent.

### Phone mode

Sometimes:

```text
A → B
```

works but:

```text
B → A
```

doesn't.

Sometimes the reverse happens.

This must be fixed.

---

# 18. AUDIT AUDIO PIPELINE END-TO-END

Trace the complete pipeline.

For microphone input:

```text
Microphone
 ↓
AudioRecord
 ↓
VAD
 ↓
STT
 ↓
Text
 ↓
Payload
 ↓
Transport
 ↓
Remote Transport
 ↓
Payload
 ↓
TTS
 ↓
AudioTrack
 ↓
Speaker
```

For phone/full-duplex PCM:

```text
Microphone A
 ↓
AudioRecord A
 ↓
PCM
 ↓
Transport
 ↓
Device B
 ↓
AudioTrack B
 ↓
Speaker B
```

AND simultaneously:

```text
Microphone B
 ↓
AudioRecord B
 ↓
PCM
 ↓
Transport
 ↓
Device A
 ↓
AudioTrack A
 ↓
Speaker A
```

Both directions must work simultaneously.

---

# 19. AUDIORECORDER AUDIT

Inspect:

```text
AudioRecord creation
buffer size
sample rate
channel configuration
audio format
startRecording()
read()
stop()
release()
```

Verify that:

- microphone permission is available
- AudioRecord initializes successfully
- buffer size is sufficient
- recording starts exactly once
- recording stops safely
- no duplicate recording threads are created
- no old AudioRecord remains active
- coroutine cancellation releases resources
- exceptions do not permanently break future recording

Add logs around:

```text
AudioRecord INIT
AudioRecord START
AudioRecord READ
AudioRecord STOP
AudioRecord RELEASE
```

---

# 20. AUDIOPLAYER AUDIT

Inspect:

```text
AudioTrack
```

lifecycle.

Verify:

```text
create
 ↓
initialize
 ↓
play
 ↓
write PCM
 ↓
stop
 ↓
release
```

Do not write PCM before AudioTrack is ready.

Do not repeatedly recreate AudioTrack unnecessarily.

Do not allow concurrent writers to corrupt playback.

Use a dedicated playback pipeline if necessary.

---

# 21. AUDIO QUEUE

Do not directly write random incoming PCM chunks from multiple coroutines into AudioTrack.

Use a controlled audio queue.

Conceptually:

```text
Network Receive
      ↓
PCM Queue
      ↓
Single Audio Playback Consumer
      ↓
AudioTrack
```

This prevents:

- concurrent writes
- race conditions
- buffer corruption
- playback gaps caused by thread competition

For phone mode, the receiver should continuously consume the queue.

---

# 22. PHONE MODE

Phone mode must be genuinely full-duplex.

Both devices must:

```text
record
+
send
+
receive
+
play
```

simultaneously.

Do NOT design phone mode as:

```text
send first
then receive
```

It must support concurrent send and receive pipelines.

Architecture:

```text
Device A

Audio Capture A ───────→ Transport A→B
                              ↓
                         Transport Receive
                              ↓
                         Audio Playback A


Device B

Audio Capture B ───────→ Transport B→A
                              ↓
                         Transport Receive
                              ↓
                         Audio Playback B
```

---

# 23. PTT / FLOOR CONTROL

Audit the complete state machine.

Possible states:

```text
IDLE
REQUESTING
GRANTED
TRANSMITTING
RELEASING
```

Handle:

- request timeout
- grant timeout
- denied request
- duplicate request
- lost grant
- lost release
- disconnect while holding floor
- disconnect while waiting
- simultaneous requests
- reconnect during transmission

Never allow the system to remain permanently stuck in:

```text
WAITING
CHANNEL_BUSY
REQUESTING
```

without recovery.

---

# 24. FLOOR CONTROL RELIABILITY

If:

```text
REQUEST_FLOOR
```

is sent and no response arrives:

Do NOT wait forever.

Use timeout:

```text
REQUEST
 ↓
timeout
 ↓
retry
 ↓
timeout
 ↓
recover to IDLE
```

If both devices request simultaneously:

Use the existing deterministic mechanism if already implemented.

Do not invent random behavior.

If there is already a sender ID tie-breaker, preserve and verify it.

---

# 25. STT / TTS CONCURRENCY

Audit:

```text
sttMutex
ttsMutex
acquireSttForInference()
releaseSttForInference()
```

and equivalent logic.

Make absolutely sure:

```text
acquire
 ↓
inference
 ↓
release
```

occurs even if inference throws an exception.

Use safe constructs such as:

```kotlin
try {
    ...
} finally {
    release(...)
}
```

where appropriate.

A mutex that remains locked after an exception can make the entire application appear dead.

---

# 26. MODEL LIFECYCLE

Audit:

- model loading
- model unloading
- initialization
- concurrent inference
- repeated session start
- session restart
- app background/foreground
- low-memory conditions

Do not reload large models unnecessarily.

Do not initialize multiple copies of the same model.

Do not allow two concurrent native inference operations if the engine is not thread-safe.

---

# 27. UNKNOWN BUG DETECTION

Do a full reliability audit.

Search for:

```text
TODO
FIXME
UnsupportedOperationException
!! 
GlobalScope
CoroutineScope without lifecycle ownership
launch without cancellation
async without await
catch (Exception) {}
empty catch blocks
silent failures
lateinit usage
race conditions
SharedFlow replay=0
unbounded queues
infinite loops
missing timeout
missing finally
resource leaks
socket leaks
AudioRecord leaks
AudioTrack leaks
BluetoothSocket leaks
ServerSocket leaks
BroadcastReceiver leaks
```

Also inspect:

- lifecycle transitions
- orientation/configuration changes
- app backgrounding
- app foregrounding
- screen navigation
- session restart
- disconnect/reconnect
- permission denial
- Bluetooth disabled
- Wi-Fi disabled
- peer disappearing
- peer restarting app
- transport switching

---

# 28. LOGGING REQUIREMENT

Add structured logs.

Use a consistent tag structure such as:

```text
ITAN-TRANSPORT
ITAN-BLUETOOTH
ITAN-WIFI
ITAN-SOCKET
ITAN-SESSION
ITAN-FLOOR
ITAN-AUDIO-REC
ITAN-AUDIO-PLAY
ITAN-STT
ITAN-TTS
ITAN-VAD
```

Important events must log:

```text
timestamp
device/session ID
state
event
success/failure
exception
```

Do NOT log sensitive user content unnecessarily.

For every connection attempt we should be able to see:

```text
DISCOVERY_START
PEER_FOUND
CONNECT_START
CONNECT_SUCCESS
SOCKET_CONNECTED
HANDSHAKE_START
HANDSHAKE_SUCCESS
CONNECTED
```

And on failure:

```text
CONNECT_FAILED
RETRY
TIMEOUT
DISCONNECTED
RECOVERY_START
RECOVERY_SUCCESS
```

---

# 29. AUDIO DEBUG LOGGING

For audio debugging, log metadata rather than huge PCM data.

Example:

```text
AudioRecord started
sampleRate=16000
channels=1
format=PCM_16BIT
bufferSize=...
```

For transmitted PCM:

```text
PCM_SEND seq=123 samples=320 bytes=640
```

For received PCM:

```text
PCM_RECEIVE seq=123 samples=320
```

For playback:

```text
PCM_PLAY seq=123
```

This lets us determine exactly where the pipeline breaks.

---

# 30. SEQUENCE NUMBERS

Audit sequence handling.

Every important data stream should have enough information to determine:

```text
what was sent
what was received
what was played
```

Avoid using one sequence counter incorrectly for unrelated message types.

If separate counters are needed:

```text
controlSequence
speechSequence
pcmSequence
```

use them.

---

# 31. DISCONNECT / RECONNECT

A disconnect must not require force-closing the app.

Expected behavior:

```text
CONNECTED
 ↓
peer disappears
 ↓
detect failure
 ↓
cleanup socket
 ↓
reset session state
 ↓
transport recovery
 ↓
rediscover/reconnect
 ↓
handshake
 ↓
CONNECTED
```

All resources from the previous connection must be cleaned.

No stale jobs should continue running.

No old socket should continue emitting events.

No old audio pipeline should continue playing.

---

# 32. TRANSPORT SWITCHING

If Bluetooth and Wi-Fi Direct are both available, switching transport must not corrupt the session.

For example:

```text
Bluetooth connected
 ↓
disconnect
 ↓
Wi-Fi Direct selected
 ↓
new transport
 ↓
new handshake
```

The SessionController should not care about implementation details.

---

# 33. UI REQUIREMENTS

Do not redesign the UI unless necessary.

The UI should clearly show:

```text
Searching...
```

```text
Device found
```

```text
Connecting...
```

```text
Connected
```

```text
Connection lost
```

```text
Reconnecting...
```

```text
Bluetooth permission required
```

```text
Bluetooth disabled
```

etc.

Never leave the user staring at an apparently frozen screen.

---

# 34. ERROR HANDLING

Every important failure should result in one of:

```text
retry
recover
show error
disconnect cleanly
```

Never:

```text
catch Exception
→ ignore
→ continue as if everything is fine
```

Do not hide root causes.

At the same time, do not crash the app for recoverable network/device failures.

---

# 35. THREADING / COROUTINE RULES

Use appropriate dispatchers.

Typical separation:

```text
Main
→ UI/state

IO
→ sockets
→ Bluetooth
→ Wi-Fi networking

Default
→ CPU-heavy processing where appropriate

Audio thread / controlled coroutine
→ real-time playback/capture
```

Do not block the Main thread.

Do not perform socket operations on Main.

Do not run expensive STT/TTS inference on Main.

Ensure all jobs have an owner and can be cancelled.

---

# 36. RESOURCE OWNERSHIP

Every resource created must have a clear owner.

Examples:

```text
Socket
ServerSocket
BluetoothSocket
BluetoothServerSocket
AudioRecord
AudioTrack
BroadcastReceiver
CoroutineScope
Job
Mutex
STT model
TTS model
```

For each resource answer:

```text
Who creates it?
Who owns it?
Who stops it?
Who releases it?
What happens if initialization fails?
What happens if the peer disconnects?
What happens if the screen closes?
```

Fix any unclear ownership.

---

# 37. DO NOT BREAK EXISTING FUNCTIONALITY

Existing working features must remain functional:

- multilingual STT
- multilingual TTS
- VAD
- PTT
- Phone mode
- emergency alerts
- mode synchronization
- session state
- UI
- language selection
- offline operation

Do not replace working AI models unless absolutely necessary.

Do not change model behavior just to solve transport bugs.

Do not remove existing functionality.

---

# 38. TEST MATRIX

After implementation, test systematically.

## Test 1

Bluetooth:

```text
A discovers B
B discovers A
A connects B
```

## Test 2

Reverse:

```text
B connects A
```

## Test 3

PTT:

```text
A → B
```

## Test 4

PTT reverse:

```text
B → A
```

## Test 5

Repeated PTT:

```text
A → B
B → A
A → B
B → A
```

at least 10 cycles.

## Test 6

Phone mode:

```text
A → B
B → A
```

simultaneously.

## Test 7

Long phone-mode test.

## Test 8

Disconnect during PTT.

## Test 9

Disconnect during phone mode.

## Test 10

Kill/restart one device.

## Test 11

Reconnect after killing one device.

## Test 12

Bluetooth disabled/enabled.

## Test 13

Permission denied/re-granted.

## Test 14

App background/foreground.

## Test 15

Repeated connect/disconnect cycles.

## Test 16

Wi-Fi Direct without external router/hotspot.

## Test 17

Bluetooth without Internet.

## Test 18

Both devices restart application and reconnect.

---

# 39. ACCEPTANCE CRITERIA

Do not consider the task complete merely because the app compiles.

The implementation is complete only when:

### Connectivity

- Bluetooth discovery works reliably.
- Bluetooth connection works reliably.
- Wi-Fi Direct works without external infrastructure.
- Both devices see appropriate connection state.
- Connection failures recover.
- Reconnection works.
- No stale sockets remain.

### PTT

- A → B works.
- B → A works.
- repeated alternating transmission works.
- floor state cannot permanently deadlock.
- lost control messages recover.

### Phone Mode

- A → B audio works.
- B → A audio works.
- both directions work simultaneously.
- no random silent direction.
- playback does not stop unexpectedly.

### Audio

- AudioRecord reliably starts/stops.
- AudioTrack reliably starts/stops.
- no duplicate recorder/player.
- no leaked resources.
- PCM queue is controlled.
- playback is ordered.
- transport/audio failures recover.

### STT/TTS

- STT continues working after reconnect.
- TTS continues working after reconnect.
- mutexes cannot remain permanently locked.
- model instances are not duplicated unnecessarily.

### Stability

- no crashes during normal connection failure.
- no permanent Connecting state.
- no permanent Waiting-for-Floor state.
- no silent failure.
- no uncontrolled coroutine leaks.

---

# 40. IMPORTANT — CODE QUALITY

Do not solve bugs using hacks such as:

```text
delay(5000)
delay(10000)
while(true)
```

unless the delay is technically justified and cancellation-aware.

Do not:

- duplicate code unnecessarily
- create multiple independent connection systems
- bypass TransportChannel
- use global state unnecessarily
- swallow exceptions
- add arbitrary sleeps to hide race conditions
- recreate audio engines repeatedly
- retry forever
- leak sockets/jobs

Prefer deterministic state transitions.

---

# 41. IMPLEMENTATION PROCESS

Follow this exact order.

### PHASE 1 — AUDIT

Inspect the whole repository.

Do not modify code yet.

Identify:

```text
Transport
Session
Audio
STT
TTS
VAD
UI
Lifecycle
Permissions
```

Create a short list of:

```text
Confirmed bug
Likely bug
Potential bug
```

Do not pretend hypotheses are confirmed.

---

### PHASE 2 — TRANSPORT FOUNDATION

Fix:

```text
TransportChannel
FramedSocket
Payload
connection lifecycle
state machine
socket lifecycle
discovery
reconnect
```

---

### PHASE 3 — BLUETOOTH

Fully implement:

```text
BluetoothSocketTransport
```

using the existing `TransportChannel`.

---

### PHASE 4 — AUDIO

Fix:

```text
AudioRecord
AudioTrack
PCM queues
send loop
receive loop
full duplex
```

---

### PHASE 5 — SESSION

Fix:

```text
SessionController
floor control
mode switching
timeouts
recovery
```

---

### PHASE 6 — STT/TTS

Verify:

```text
STT
VAD
TTS
mutex lifecycle
model lifecycle
```

---

### PHASE 7 — UNKNOWN BUG AUDIT

Search the complete project for reliability problems.

---

### PHASE 8 — TESTING

Run the complete test matrix.

---

# 42. HOW YOU SHOULD RESPOND WHILE WORKING

Do not simply say:

> "I fixed it."

For every major modification, explain:

```text
FILE
PROBLEM
ROOT CAUSE
CHANGE
WHY IT FIXES THE ISSUE
```

Example:

```text
BluetoothSocketTransport.kt

Problem:
Bluetooth was completely unimplemented.

Root cause:
All methods threw UnsupportedOperationException.

Change:
Implemented RFCOMM discovery/connection/server/client/socket lifecycle.

Why:
The application can now use Bluetooth through TransportChannel.
```

---

# 43. VERY IMPORTANT — DO NOT INVENT APIs

Before writing code:

- inspect existing Gradle dependencies
- inspect minSdk/targetSdk
- inspect AndroidManifest
- inspect existing classes
- inspect existing imports
- inspect existing architecture

Use APIs compatible with the project's actual Android configuration.

If an API differs by Android version, implement proper compatibility handling.

---

# 44. WHEN SOMETHING IS UNCLEAR

Do not guess silently.

Clearly state:

```text
I need to inspect X because Y depends on it.
```

Then inspect it.

If you cannot inspect something, tell me exactly which file/function is required.

Do not replace missing code with imaginary code.

---

# 45. FINAL DELIVERABLE

At the end provide:

## A. Root Cause Report

Table:

| Issue | Root Cause | Severity | Fixed |
|---|---|---|---|

## B. Modified Files

```text
file
what changed
```

## C. Architecture

Show the final architecture:

```text
UI
 ↓
ViewModel
 ↓
SessionController
 ↓
TransportManager
 ├── BluetoothSocketTransport
 └── WifiDirectSocketTransport
        ↓
     FramedSocket
        ↓
     Remote Device
```

along with the audio pipeline.

## D. Testing Checklist

Mark:

```text
[ ] Bluetooth discovery
[ ] Bluetooth connection
[ ] Wi-Fi Direct
[ ] PTT A→B
[ ] PTT B→A
[ ] Phone A→B
[ ] Phone B→A
[ ] Full duplex
[ ] Reconnect
[ ] Disconnect recovery
[ ] Permission handling
[ ] App restart
[ ] Background/foreground
```

## E. Remaining Risks

Clearly separate:

```text
FIXED
VERIFIED
NOT YET VERIFIED
KNOWN LIMITATION
```

Do not claim a bug is fixed if it has not actually been tested.

---

# FINAL INSTRUCTION

Treat this as a **production/hackathon reliability stabilization task**, not a simple coding question.

The most important goals are:

1. Reliable device-to-device connection.
2. Bluetooth as a real working offline transport.
3. Wi-Fi Direct without external router/hotspot.
4. Reliable PTT in both directions.
5. Reliable full-duplex phone mode.
6. Reliable STT/TTS/audio pipeline.
7. Proper timeout/retry/reconnection.
8. No silent failures.
9. No resource leaks.
10. No race-condition-driven intermittent behavior.

Do not stop after fixing the first visible bug.

Trace the complete system end-to-end and fix the underlying reliability problems.

Start by auditing the complete repository before making destructive architectural changes.