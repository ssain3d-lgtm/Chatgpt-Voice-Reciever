# Wake-word assets (not committed)

Porcupine needs two files that are **licensed Picovoice material** and are therefore
excluded by `.gitignore`. Put them here by hand:

```text
app/src/main/assets/wakeword/keyword.ppn      custom "헤이 지피티" keyword model (ko)
app/src/main/assets/wakeword/params_ko.pv     Korean model parameters
```

Both come from the Picovoice Console. The Korean `params` file is **required** for a
Korean keyword — Porcupine ships English parameters by default and a `ko` keyword will
not work against them.

Then add your AccessKey to `local.properties`:

```properties
PICOVOICE_ACCESS_KEY=...
```

Usage conditions, free-plan limits and whether custom `.ppn` models expire
**MUST BE VERIFIED AGAINST CURRENT PICOVOICE TERMS** (RISK_REGISTER.md R-13). This
repository makes no claim about them.

## Without these files

The app builds and runs. `PorcupineWakeWordEngine` reports `Unavailable`, the debug UI
shows the reason, and `DisabledWakeWordEngine` takes its place.

That still leaves the most important S-1 measurement available: **GV-03**, whether an
FGS started by a `VoiceInteractionService` keeps receiving non-silent microphone audio
with the screen off, is answered by the RMS / zero-frame telemetry on the S-1 screen
and needs no wake word at all.

What you cannot measure without them: **GV-02**, wake-word detection rate and
false-accept rate.
