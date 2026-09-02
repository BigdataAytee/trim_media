# Milestone 3 kickoff prompt

Paste the block below into Claude Code, in a session that has the Android SDK.
Run `tools/preflight-android.sh` first; if it does not exit 0, stop and fix the
environment rather than the code.

**Precondition: Milestone 2 must be done first.** M3 implements a port that only exists
on-device, so it needs `androidApp` and the Android target. See `docs/M2-STATUS.md` for
what is left of M2 — it also needs the SDK, so the two are unblocked by the same change of
environment.

---

```
Read docs/app-architecture.md (§3, §5, §7, §8, §9, §12), CLAUDE.md, docs/DECISIONS.md
(D2.4, D4.1, D4.3, D9.3, D9.4, D9.8, D9.9) and docs/M2-STATUS.md before writing any code.
These documents are the source of truth. Where they conflict with your instincts, the
documents win. Where they conflict with each other, ask me.

You are building Milestone 3 of Trim: the real Codec port. Every other milestone has run
on a fake codec that tells a consistent story. This one replaces it with hardware, which
does not, and the milestone is mostly about finding out where the hardware disagrees with
the assumptions the pipeline was built on — before a user's file does.

## Milestone 3 scope — build exactly this, nothing more

1. CodecFactory, at androidApp/src/main/kotlin/dev/trim/android/codec/. That exact path is
   already the sole entry on guard #2's allow-list, so it is not a suggestion. It is the
   only place in the repository permitted to touch android.media.MediaCodec or Media3's
   codec-selection APIs; the guard is live and will fail the build otherwise.

   - Hardware only. Reject software codecs explicitly (MediaCodecInfo.isHardwareAccelerated
     on API 29+, and a vendor/name check below it). There is no software fallback to write:
     app-architecture §12 says a file the hardware cannot handle is skipped with a reason.
     A missing hardware encoder is CodecError.NoHardwareSupport, not a reason to reach for
     a CPU encoder.
   - One encode at a time. §5 forbids parallel encodes — they share one encoder block and
     are pure loss. Serialise acquisition in the factory, not by convention at call sites.
   - Release on every path, including cancellation and reclaim.

2. The Codec port implemented with Media3 Transformer / MediaCodec. The interface is
   already fixed by core/ports; do not change its shape without telling me why.

   - analyseWindows: decode a few frames and return high-frequency energy normalised to
     0..1. It runs before any encode (§3), so it must not need an encoder at all.
   - encodeWindows: the search's unit of work — the windows only, at 720p. It returns an
     EncodedSample handle the Scorer consumes. The real Scorer is Milestone 4, so decide
     now what that handle refers to and write it down; a handle the M4 native layer cannot
     resolve means redoing this.
   - encodeFull: decoder Surface wired directly to encoder Surface so frames never enter
     CPU memory (§5), audio stream-copied, 2-second keyframes, front-index MP4 (moov at
     the front), KEY_PRIORITY = 1.

3. Rate control — the open risk. DECISIONS D2.4 records that EncodeSetting.quality is a
   CRF-like index and that mapping it to a device's actual rate-control mode is this
   milestone's job. Detect whether the device exposes constant-quality mode
   (BITRATE_MODE_CQ) or only bitrate modes, and map the index accordingly.

   The constraint that makes this hard is not the mapping, it is that the mapping must
   preserve monotonicity. CodecContract's clause "a more aggressive setting never produces
   a larger encode" and ScorerContract's "a more aggressive setting never scores higher"
   are the preconditions the binary search rests on (D4.3). A quality-to-bitrate mapping
   that is not monotone makes the Searcher unsound on that device. Check it, do not assume
   it.

   Record the finding in docs/DECISIONS.md with the device it was measured on, and load
   the mapping from a data table rather than hardcoding it — same rule as the calibration
   table in §9.

4. Codec reclaim, and the resume gap. KEY_PRIORITY = 1 means Android hands the encoder to
   any foreground app that wants it, so reclaim is routine. The pipeline already handles
   it: encodeFull returns CodecError.CodecReclaimed(atFraction), Encoder maps it to
   EncodeOutcome.Interrupted, and JobRunner.encodeWithRetries waits and retries.

   But it retries by calling encoder.encode(...) again from the top, so the whole file
   re-encodes. app-architecture §7 says the runner "catches the codec-lost exception,
   waits, and resumes the file from its last sync point". Those are not the same thing,
   and on a 4K file the difference is minutes of wasted hardware per reclaim.

   Closing it needs a port change — encodeFull taking a resume point, and the codec
   producing an output that can be appended to or muxed from a sync point. Come to me with
   a proposal before you make it. If you conclude that restarting is the right trade for
   short files and resuming only pays above some duration, that is a fine answer; it is
   just not one to make silently.

5. Colour range and metadata.

   - Detect the input's colour range and preserve it. Verifier already rejects an encode
     whose range changed, so an implementation that does not preserve it cannot complete a
     single job — you will see this as every file failing verification, not as a subtle
     colour shift.
   - Metadata is split between two places and the documents state both: §3 has the encoder
     carrying metadata, §6 step 1 has the Replacer copying it. Read it as the encoder
     carrying what lives in the bitstream and container (rotation, colour information) and
     the Replacer carrying the file-level tags (DATE_TAKEN, GPS, camera tags). Implement
     that split and record it in DECISIONS as a judgment call, because the documents do not
     say it in those words.

## Tests

1. The real Codec passes CodecContract — the same eight clauses FakeCodec already passes,
   as an instrumented test. Subclass dev.trim.ports.contract.CodecContract, supply a
   fixture backed by a bundled sample clip, and run it on a device.

   Two clauses are the ones that matter. "A more aggressive setting never produces a
   larger encode" and "the same source and setting encode to the same size twice" (within
   2%, D9.4) are properties of hardware, not of your code. If either fails, that is a
   finding about the device, not a test to relax — bring it to me.

2. An instrumented test on the bundled clip asserting the four things the documents
   promise: output smaller, duration equal, colour range preserved, DATE_TAKEN survives a
   media rescan.

3. A reclaim test, best-effort: force the encoder away mid-encode and assert the runner
   resumes and the file completes, with the original intact throughout. If you cannot
   force a real reclaim on the device, say so rather than writing a test that passes
   because nothing happened.

## Non-goals — do not build these

- No Scorer. XPSNR and libvmaf are Milestone 4; the fake Scorer stays in use on-device.
- No UI. That is Milestone 5.
- No calibration table values. §9 says the XPSNR/VMAF table is data, shipped per device
  class; deriving it is M4's calibration harness. Do not hardcode a threshold to make a
  test pass.
- No new dependencies beyond Media3 without asking me first.

## How to work

- CodecFactory first, then analyseWindows, then encodeWindows, then encodeFull — smallest
  surface first, and each one green against its contract clauses before the next.
- Run the contract suite on a device after every stage, not at the end. It is eight
  clauses; it will tell you which assumption broke while you still remember what you
  changed.
- Commit per completed unit. Never squash away the history of anything touching the
  encode path.
- Record every judgment call in docs/DECISIONS.md, one line each, continuing the D-numbering.

## Definition of done

- The real Codec passes CodecContract on a device — the same clauses, unmodified, that
  FakeCodec passes on the JVM. If a clause had to change, that is a conversation, not a
  commit.
- FakeCodec still passes it too. The Milestone 5 demo build runs entirely on fakes, so the
  fake going stale is a real regression.
- The bundled-clip instrumented test passes on all four assertions.
- guardCodecFactoryOnly is green with CodecFactory as the only exception, and you have
  shown me it failing on a deliberate MediaCodec call outside that package before removing
  it. A guard demonstrated to fail is real; a guard merely written is decoration.
- `./gradlew jvmTest` still passes without a device. The core stayed platform-free; that is
  what makes the other 130 tests worth running.
- docs/DECISIONS.md records the CQ-versus-bitrate finding, the device it was measured on,
  and the metadata split.

Start by reading the documents and running tools/preflight-android.sh, then show me your
plan as a short ordered task list before writing code.
```
