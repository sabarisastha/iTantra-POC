You are working on the existing iTantra Android application.

I want you to perform a COMPLETE BUG-FIX, RELIABILITY, FEATURE-ENHANCEMENT, and UI/UX improvement pass on the CURRENT project.

IMPORTANT:

I have a working Phone Mode already.

DO NOT redesign, replace, rewrite, or unnecessarily modify the existing Phone Mode communication pipeline.

Treat Phone Mode as a PROTECTED WORKING FEATURE.

The main communication functionality that now requires work is:

1. Walkie-Talkie / PTT
2. Emergency Alert
3. Application flow
4. UI/UX
5. Language/model selection
6. Connection screen
7. History
8. Settings
9. Offline readiness / diagnostics
10. Existing bugs and hidden regressions

Read the complete project documentation and architecture before modifying code.

============================================================
SOURCE DOCUMENTS
============================================================

Read and understand:

- `iTantra Offline Communication App Complete Stabilization.md`
- `complete architecture.md`
- `iTantra_documentation.md`
- latest stabilization walkthrough
- all relevant README files
- current source code
- current UI implementation
- current transport implementation
- current SessionController
- current STT/TTS/VAD/model implementation

The repository is the ultimate source of truth.

Do not assume that a feature documented as “complete” actually works.

Do not rewrite the project from scratch.

Preserve the existing architecture wherever possible.

============================================================
CRITICAL RULE — PHONE MODE
============================================================

PHONE MODE IS CURRENTLY WORKING.

Do NOT redesign or replace its communication architecture.

Do NOT convert Phone Mode into another transport/pipeline.

Do NOT introduce STT/TTS into the existing working raw/live Phone Mode unless the current implementation already requires it.

Do NOT break:

- AudioRecord
- AudioTrack
- full-duplex communication
- Phone Mode lifecycle
- Phone Mode connection flow

Only modify shared components if absolutely necessary and verify that Phone Mode remains functional afterward.

The priority of this task is WALKIE-TALKIE/PTT and the overall application UX.

============================================================
PHASE 1 — COMPLETE BUG AUDIT
============================================================

Before making UI changes, inspect the current implementation for all known and hidden bugs.

Audit:

- Transport
- Bluetooth
- Wi-Fi Direct
- SessionController
- PTT
- floor control
- STT
- TTS
- VAD
- AudioRecorder
- AudioPlayer
- ModelManager
- ModelDownloader
- state machines
- coroutines
- Channels
- Flows
- Compose lifecycle
- Navigation
- permissions
- resource cleanup

Search for:

- stale state
- race conditions
- duplicate collectors
- duplicate coroutines
- duplicate sockets
- resource leaks
- cancellation bugs
- model lifecycle races
- audio lifecycle issues
- UI lifecycle issues
- navigation problems
- PTT state inconsistencies
- alert handling issues
- language state problems

============================================================
PHASE 2 — FIX WALKIE-TALKIE TTS LATENCY
============================================================

CURRENT PROBLEM:

In Walkie-Talkie mode:

- sender speech works
- STT works
- receiver gets the transcript
- transcript appears
- TTS voice is delayed significantly

The user wants the delay between transcript reception and audible TTS to be minimized.

Do NOT blindly add arbitrary threading or delays.

Trace the actual path:

Sender:
PTT
→ microphone
→ STT
→ transcript
→ transport

Receiver:
transport
→ received payload
→ SessionController
→ TTS
→ AudioPlayer
→ speaker

Instrument the complete receive-to-playback pipeline.

Add structured diagnostic logs such as:

[PTT] SPEECH_RECEIVED
[TTS] REQUEST_STARTED
[TTS] MODEL_READY
[TTS] SYNTHESIS_STARTED
[TTS] SYNTHESIS_COMPLETED
[AUDIO] PLAYBACK_STARTED
[AUDIO] PLAYBACK_COMPLETED

Use timestamps.

Measure:

- transcript arrival time
- TTS start time
- TTS model startup time
- TTS synthesis time
- AudioPlayer startup time
- total transcript-to-audio latency

Identify where the real delay occurs.

Then optimize the actual bottleneck.

Possible strategies ONLY where justified by the current implementation:

- keep the correct TTS model warm during an active PTT session
- avoid unnecessary model reloads
- avoid UI-mediated TTS invocation
- remove unnecessary coroutine hops
- avoid unnecessary serialization/deserialization
- start playback immediately when synthesized audio is available
- reduce unnecessary buffering
- reuse initialized resources
- process speech promptly after transcript reception

Do NOT sacrifice stability for latency.

After the fix, verify:

PTT A → B
PTT B → A
multiple messages
repeated PTT sessions

============================================================
PHASE 3 — FIX PTT FLOOR RELEASE DELAY
============================================================

CURRENT PROBLEM:

When User A holds Hold-to-Talk:

User B correctly sees:

REMOTE TALKING

But when User A releases:

User B continues showing REMOTE TALKING for approximately 2–3 seconds before automatically returning to Ready.

In some cases, the lock is only cleared after another interaction with Hold-to-Talk.

THIS MUST BE FIXED.

Desired behavior:

User A presses
↓
REQUEST_FLOOR
↓
FLOOR_GRANTED
↓
User A = TALKING
User B = REMOTE TALKING

User A releases
↓
RELEASE_FLOOR
↓
User A = READY
User B = READY

The floor must be released immediately.

Do NOT use delayed timers as the primary mechanism.

Audit:

- REQUEST_FLOOR
- FLOOR_GRANTED
- RELEASE_FLOOR
- local floor state
- remote floor state
- remote talking UI state
- SessionController
- TransportChannel
- control message priority
- coroutine/channel ordering

Ensure RELEASE_FLOOR cannot be blocked behind:

- PCM traffic
- TTS
- UI updates
- transcript rendering
- unrelated coroutine work

Ensure the remote UI reacts immediately to RELEASE_FLOOR.

Important:

REMOTE TALKING UI state must represent the floor/talking state, not TTS playback completion.

The remote TTS can still be finishing playback while the communication floor is already released.

Verify:

- normal hold/release
- rapid hold/release
- repeated PTT
- disconnect during PTT
- remote disconnect
- reconnect
- both users alternating turns
- no stuck lock

============================================================
PHASE 4 — WALKIE-TALKIE STATE MACHINE
============================================================

Make the PTT state machine explicit and deterministic.

Conceptually:

IDLE
↓
REQUESTING_FLOOR
↓
WAITING_FOR_GRANT
↓
TRANSMITTING
↓
RELEASE
↓
IDLE

Remote:

IDLE
↓
REMOTE_TALKING
↓
RELEASED
↓
IDLE

No state may remain permanently stuck.

Prevent:

- double PTT start
- duplicate microphone capture
- duplicate sender jobs
- duplicate floor grants
- stale remote talking state
- PTT while disconnected
- PTT during cleanup
- simultaneous floor ownership

============================================================
PHASE 5 — EMERGENCY ALERT LOCALIZATION
============================================================

Current problem:

The Send Alert feature always produces an English alert.

Change this.

The emergency alert must use the USER'S CURRENTLY SELECTED SESSION LANGUAGE.

Supported languages:

Hindi
Gujarati
Marathi
Kannada
Malayalam
Tamil
Telugu
Odia
Bengali
English

For fixed predefined emergency messages, it is acceptable to use hardcoded localized phrases for each language.

Example concept:

English:
“Emergency. I need assistance.”

Tamil:
appropriate Tamil emergency phrase

Hindi:
appropriate Hindi emergency phrase

etc.

Do NOT use an online translation API.

The alert must work offline.

The localized message should be:

selected language
→ emergency text
→ transport
→ receiver
→ TTS
→ loud alert

The alert must retain its highest-priority behavior where that functionality is already implemented.

============================================================
PHASE 6 — EMERGENCY LOCATION
============================================================

Enhance the emergency alert so that, when location permission is granted and a location is available, the sender can attach:

- latitude
- longitude
- timestamp

Do not block the emergency alert if location is unavailable.

Fallback:

ALERT SENT
Location unavailable

The application must not crash because GPS/location is unavailable.

============================================================
PHASE 7 — EMERGENCY DISTANCE
============================================================

When both devices have valid locations:

Device A location
+
Device B location
↓
calculate distance

Show something like:

Emergency source
1.4 km away

Use an appropriate geographic distance calculation.

Do NOT call an external maps service.

Everything must remain local/offline.

============================================================
PHASE 8 — EMERGENCY DIRECTION / BEARING
============================================================

If technically available:

Calculate the bearing from the receiving device to the emergency sender.

Convert the bearing into:

N
NE
E
SE
S
SW
W
NW

Example:

Emergency source
1.4 km
NE

Use actual location coordinates.

Do not claim precise direction if sensor/location accuracy is insufficient.

============================================================
PHASE 9 — OPTIONAL COMPASS VIEW
============================================================

Add an emergency compass visualization only if it can be implemented cleanly.

The compass should distinguish:

1. Device orientation
2. Direction/bearing toward the emergency sender

Example concept:

COMPASS

        N
        ↑
    ↗
 Emergency
 1.4 km NE

If sensor/orientation information is unavailable:

show the textual bearing instead.

Do not make the emergency feature dependent on compass hardware.

============================================================
PHASE 10 — APPLICATION STARTUP EXPERIENCE
============================================================

Redesign the initial application entry flow.

When the user launches iTantra:

SHOW A SHORT, PROFESSIONAL ANIMATION RELATED TO:

India
Indian languages
Speech
Communication
Offline connectivity

The animation should visually communicate:

INDIA
↓
INDIAN LANGUAGE SCRIPTS
↓
SPEECH
↓
Wi-Fi / Bluetooth
↓
iTantra

Avoid generic “AI glowing brain” animations.

Avoid excessive flashy graphics.

Prefer clean native Compose animation and lightweight assets.

The startup animation must be short and must not delay normal usability unnecessarily.

============================================================
PHASE 11 — LANGUAGE SELECTION AS THE FIRST REAL SCREEN
============================================================

After the startup animation, immediately show LANGUAGE SELECTION.

The user should select the communication language before entering the connection screen.

Display all supported languages.

Each language item should clearly show:

Language name
Native script where appropriate
STT model status
TTS model status
Overall readiness

Example:

Tamil
தமிழ்

STT ✓ Ready
TTS ✓ Ready

or:

Hindi
हिन्दी

STT ✓
TTS ↓ Download

============================================================
PHASE 12 — MODEL AVAILABILITY
============================================================

When a language is selected:

Check whether the required STT/TTS models are already present locally.

If both are ready:

continue to connection screen.

If required models are missing:

show download state and download only what is required for the selected language.

Show:

Preparing offline language models...
Download progress
STT status
TTS status
Ready

After successful completion:

Automatically move to connection screen.

Do not repeatedly download already available models.

Clearly identify:

Downloaded
Downloading
Ready
Error
Retry

All AI preparation must remain compatible with offline operation after the models are available.

============================================================
PHASE 13 — CONNECTION SCREEN
============================================================

After language/model preparation, open the main connection/home screen.

The connection screen should clearly expose the transport:

BLUETOOTH
WI-FI DIRECT

Make transport selection explicit.

Important:

This is NOT only a visual toggle.

The selected transport must actually control:

discovery
device list
connect
disconnect
connection state

If Bluetooth is selected:

Bluetooth discovery must run.

If Wi-Fi Direct is selected:

Wi-Fi Direct discovery must run.

Do NOT display:

“Scanning over Wi-Fi…”

while Bluetooth is the actual active transport.

The UI must derive the status from the real active transport.

============================================================
PHASE 14 — NEARBY DEVICE UI
============================================================

Improve the current device cards.

Each card should show:

device name
transport
availability
connection state
Connect button

Example:

SABARI
Bluetooth
Available

[ CONNECT ]

or:

SABARI
Wi-Fi Direct
Available

[ CONNECT ]

Do not fabricate signal values.

If RSSI is available from the actual platform/API, show it.

Otherwise omit it.

============================================================
PHASE 15 — CONNECTION HEALTH
============================================================

Add a lightweight connection-health indicator.

When connected show:

Connected
Bluetooth / Wi-Fi Direct
Healthy

Where measurable, show:

latency
last heartbeat
connection status

Do not invent measurements.

Only display actual measured values.

============================================================
PHASE 16 — OFFLINE READINESS
============================================================

Add an optional lightweight offline readiness state.

Before/around connection, the user should be able to see whether the required components are ready:

✓ Language model
✓ STT
✓ TTS
✓ Microphone permission
✓ Bluetooth
✓ Wi-Fi Direct where applicable

Example:

OFFLINE READY

All required local resources available.

This should be informational and should not obstruct the user unnecessarily.

============================================================
PHASE 17 — BOTTOM NAVIGATION
============================================================

Keep the current bottom navigation concept:

Home
History
Settings

But make it functional and consistent.

The bottom navigation should be used for pre-session/application management screens.

Do not force the bottom navigation into the active communication/session screen if that harms the communication UX.

============================================================
PHASE 18 — REAL CONNECTION HISTORY
============================================================

Current History is effectively empty/dummy.

Make it real.

Persist actual connection history locally.

Use an appropriate local persistence mechanism such as Room or an equivalent reliable Android local store.

Record useful information such as:

peer name
peer identifier where appropriate
transport
language
connection start time
connection end time
duration
connection result

Example:

SABARI
Bluetooth
Tamil
Connected
10:42 PM
Duration 04:18

No cloud database.

No Internet requirement.

Do not expose sensitive information unnecessarily.

============================================================
PHASE 19 — SETTINGS SCREEN
============================================================

Expand Settings into a useful technical configuration page.

Sections should include:

AI VOICE MODELS

Selected language
STT status
TTS status
model readiness
download/update/retry state

COMMUNICATION

Preferred transport
Bluetooth
Wi-Fi Direct
fallback behavior if actually supported

PERMISSIONS

Microphone
Bluetooth
Location if required for the feature

OFFLINE STATUS

Model availability
Local inference readiness

DIAGNOSTICS

Current transport
Connection state
Latency if measured
Audio status
STT status
TTS status
Heartbeat status

ABOUT

App version
iTantra information

Do not fill this page with unnecessary settings.

============================================================
PHASE 20 — SESSION SCREEN UI
============================================================

Improve the existing SessionScreen while preserving its functionality.

Current concepts:

Walkie-Talkie
Phone
Language lock
Channel status
Hold to Talk
Transcript
Send Alert
Latency Debug

Keep the communication-first layout.

For Walkie-Talkie:

Clearly distinguish:

READY TO TALK
REQUESTING
TALKING
REMOTE TALKING
RELEASING / READY

The state must update instantly based on actual session state.

The Hold-to-Talk button should provide clear visual feedback for:

idle
pressed
granted
transmitting
remote talking
disabled

Avoid unnecessary animations that delay interaction.

============================================================
PHASE 21 — PTT LATENCY VISUALIZATION
============================================================

The existing latency/debug concept may be improved.

Where actual timestamps exist, optionally show:

STT
NETWORK
TTS
TOTAL

Only show measurements derived from actual runtime data.

Do not hardcode historical/example values.

Make the debug panel collapsible.

Keep the main PTT interface clean.

============================================================
PHASE 22 — ALERT UI
============================================================

Improve Send Alert behavior.

When pressed:

show a clear confirmation or intentional-action mechanism if necessary to avoid accidental triggering.

Then send the localized emergency message.

Display the actual alert result:

Alert Sent
or
Alert Failed

If location is available:

Location attached

If remote information can be calculated:

Distance
Bearing

Do not make the alert depend on Internet connectivity.

============================================================
PHASE 23 — LANGUAGE LOCK
============================================================

Once a communication session starts:

LOCK THE SESSION LANGUAGE.

The session language must not be changed halfway through:

PTT
STT
TTS
active communication
model loading for the session

The UI must make this obvious.

Keep the existing language-lock concept in SessionScreen.

Language can change between separate sessions.

============================================================
PHASE 24 — UI STATE MUST NEVER LIE
============================================================

This is critical.

The UI must always represent the actual underlying state.

Never show:

Connected when disconnected.

Connecting forever.

Bluetooth when Wi-Fi is active.

Ready while floor is owned by the peer.

Remote Talking after RELEASE_FLOOR has actually been received.

Models Ready when models are unavailable.

Alert Sent when transmission failed.

Use actual StateFlow/state sources.

Avoid duplicate sources of truth.

============================================================
PHASE 25 — NAVIGATION / LIFECYCLE
============================================================

Audit navigation.

Desired general application flow:

APP START
↓
INTRO ANIMATION
↓
LANGUAGE SELECTION
↓
MODEL CHECK / DOWNLOAD
↓
CONNECTION / HOME
↓
SESSION

Back behavior must be deterministic.

Avoid:

double navigation
navigation loops
duplicate pop
stale SessionScreen collectors
stale SessionController callbacks

When leaving a session:

perform proper session cleanup.

============================================================
PHASE 26 — UI DESIGN DIRECTION
============================================================

Use the existing visual language as the foundation:

- clean white/light background
- blue primary accent
- rounded cards
- clear typography
- large touch targets
- simple icons
- subtle shadows
- restrained colors

Improve consistency rather than completely changing the design language.

Avoid:

- excessive gradients
- generic AI graphics
- too many cards
- unnecessary decorative elements
- tiny controls
- excessive animations

The UI should feel appropriate for:

field communication
emergency scenarios
low-literacy users
rapid interaction
technical reliability

============================================================
PHASE 27 — ACCESSIBILITY / FIELD USABILITY
============================================================

Improve usability for real-world communication.

Ensure:

- large touch targets
- clear state labels
- high contrast
- readable text
- obvious PTT state
- obvious remote talking state
- obvious connection state
- obvious emergency state

Do not rely on color alone to communicate state.

============================================================
PHASE 28 — PERSISTENCE
============================================================

Persist only information that is genuinely useful:

selected language
model availability
transport preference
connection history
safe app configuration

Do not persist sensitive speech/audio unnecessarily.

Everything should remain local.

============================================================
PHASE 29 — PRESERVE EXISTING WORKING FEATURES
============================================================

Do NOT break:

- Bluetooth communication
- Wi-Fi Direct communication
- existing Phone Mode
- existing STT
- existing TTS
- VAD
- model management
- emergency transport
- existing protocol/framing

When changing shared components, regression-test dependent features.

============================================================
PHASE 30 — TESTING AFTER IMPLEMENTATION
============================================================

After implementation perform automated tests wherever possible.

Then manually verify the following.

PTT:

A presses
B sees Remote Talking immediately

A releases
B becomes Ready immediately

A repeats

B then speaks

A receives

Repeat many times.

TTS:

A sends
B receives transcript
B TTS starts promptly
B hears voice

Reverse direction.

Emergency:

Tamil alert
Hindi alert
Gujarati alert
Marathi alert
Kannada alert
Malayalam alert
Telugu alert
Odia alert
Bengali alert
English alert

Verify correct language.

Location:

location available
location unavailable
permission denied

Verify graceful behavior.

Transport:

Bluetooth
Wi-Fi Direct

Verify UI and actual transport match.

History:

connect
disconnect
verify real history entry

Settings:

model status
permissions
transport status

============================================================
PHASE 31 — REGRESSION TEST PHONE MODE
============================================================

Again:

DO NOT redesign Phone Mode.

After all shared changes verify:

Phone Mode starts
A → B works
B → A works
full duplex remains functional
Phone Mode stops cleanly
another Phone Mode session can start

If Phone Mode breaks because of a shared-resource change, fix the regression.

============================================================
PHASE 32 — BUILD
============================================================

Run:

./gradlew compileDebugKotlin
./gradlew assembleDebug
./gradlew test

Also run relevant lint/static/instrumentation tests if configured.

Fix real build/test errors.

============================================================
PHASE 33 — FINAL REPORT
============================================================

At the end provide:

1. Bugs discovered
2. Root causes
3. Files changed
4. PTT latency diagnosis
5. PTT latency improvement
6. Floor-release bug diagnosis
7. Floor-release fix
8. Emergency localization implementation
9. Location implementation
10. Distance implementation
11. Direction/bearing implementation
12. UI flow changes
13. Language/model flow
14. Connection screen changes
15. History implementation
16. Settings implementation
17. Offline readiness implementation
18. Accessibility improvements
19. Regression testing
20. Build results
21. Automated test results
22. Physical-device tests performed
23. NOT TESTED items
24. Remaining limitations

============================================================
IMPORTANT FINAL RULES
============================================================

Do not solve a technical bug using only a UI change.

Do not fake latency values.

Do not fake location.

Do not fake device history.

Do not fake Bluetooth/Wi-Fi status.

Do not claim offline functionality if a feature actually requires Internet.

Do not claim physical-device testing unless it actually happened.

Do not modify Phone Mode unnecessarily.

Do not create duplicate state sources.

Do not use arbitrary delays to hide state problems.

Do not create unnecessary background jobs.

Do not introduce cloud services.

Do not use online translation for emergency localization.

Do not make location/compass mandatory for sending an emergency alert.

Most importantly:

FIRST make PTT technically correct and fast.

THEN make the application flow and UI polished.

The resulting architecture should be:

STARTUP
→ INTRO
→ LANGUAGE
→ MODEL READINESS
→ CONNECTION
→ SESSION

And inside SESSION:

WALKIE-TALKIE
= STT → TEXT → TRANSPORT → TTS

PHONE
= existing working Phone Mode, preserved without unnecessary modification

EMERGENCY
= localized alert + optional location metadata + optional distance/bearing

Execute the work in the repository.
Do not merely explain what should be changed.
Actually implement the changes and verify them.