# Take Master — User Guide

**Take Master** is an offline multi-take audio alignment, video comping, and export studio (Android 10 / API 29+). Select 2–6 recorded performance videos (drums, vocals, instruments, acting, or multi-cam angles), automatically align them to sub-millisecond sync by audio, audition and compare angles side-by-side, paint or split your ideal Master Comp timeline, and export a gapless stitched MP4 directly to your Gallery.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [Loading & Managing Takes](#2-loading--managing-takes)
3. [Auto Audio Sync & Manual Nudge](#3-auto-audio-sync--manual-nudge)
4. [Previewing, Auditioning & Multi-Angle View](#4-previewing-auditioning--multi-angle-view)
5. [Comp Mode vs. Compare Mode (Mute & Solo)](#5-comp-mode-vs-compare-mode-mute--solo)
6. [Editing the Master Comp Timeline](#6-editing-the-master-comp-timeline)
7. [Exporting Your Master Video](#7-exporting-your-master-video)
8. [Tips & Troubleshooting](#8-tips--troubleshooting)

---

## 1. Quick Start

1. Launch **Take Master** and tap **Select Video Takes (2–6)** (tap **Try 3-Take Demo Session** to explore with built-in demo audio, or **User Guide** anytime for this manual).
2. Wait briefly while the app decodes waveforms and automatically aligns all takes against **Take 1**.
3. Tap **Play** (or tap the video preview) to watch and listen.
4. Use **Compare** mode, **Multi-Angle** view, or the **Audition** strip to inspect each take at any moment.
5. **Pan** is selected by default so you can freely drag across zoomed waveforms; switch to **Swipe Comp** to paint ranges onto the Master Comp, or use **Split Tap** / **Split Here** / **Auto-Split** to create cuts.
6. Drag the **white interior handles** on the **MASTER** track to adjust cut timing, or drag the **yellow outer handles** to trim the start and end.
7. Tap **Export**, choose your audio stitching mode and quality, and tap **Save Video to Gallery**.

---

## 2. Loading & Managing Takes

### Starting Screen
- **Select Video Takes (2–6):** Opens the Android media picker (video only) so you can load up to 6 camera takes at once. Everything is processed 100% locally on your device.
- **Try 3-Take Demo Session:** Generates a 3-take synthesized multi-track session (~20–21.5 s each with realistic start offsets and pre-generated **Auto-Split** cuts) so you can test playback, auditioning, and timeline editing immediately without video files.
  > **Note:** Demo takes contain synthesized audio only (shown on an animated **Demo Synth Stage** canvas) and cannot be exported as an MP4. Tap **New** and select real camera videos when you are ready to export.
- **User Guide:** Opens this complete on-device manual.

### Top Bar Controls
Once takes are loaded, the top bar shows the take count and total Master timeline duration, along with:
- **New:** Replaces the current project with a newly selected set of 1–6 videos.
- **`+` Take:** Appends additional video takes to your existing session (visible when fewer than 6 takes are loaded). New takes are automatically aligned to **Take 1**, while preserving your current Master Comp segments.
- **Export:** Pauses playback and opens the **Export Master Video** sheet.
- **`?`:** Opens the **User Guide** right inside the studio at any time.

### Take Color Coding
Each take is assigned a distinct accent color across the preview badges, audition chips, timeline waveforms, and cut list:
- **Take 1:** Emerald
- **Take 2:** Sky Blue
- **Take 3:** Amber
- **Take 4:** Rose
- **Take 5:** Violet
- **Take 6:** Teal

---

## 3. Auto Audio Sync & Manual Nudge

### How Automatic Alignment Works
When you import videos, Take Master:
1. Decodes each take's audio into a local 48 kHz stereo PCM cache and builds a 50-peaks/second waveform display.
2. Uses **Take 1** as the timing reference (`Reference`, `0 ms`).
3. Runs a two-stage alignment pass on each remaining take:
   - Applies a transient-suppression filter (high-pass + soft-knee compression) so sharp hits like kick/snare transients don't skew alignment against the continuous backing track or room audio.
   - **Coarse stage:** Performs FFT normalized cross-correlation at 2 kHz across up to the first 120 seconds, searching offsets within ±60 seconds.
   - **Fine stage:** Refines alignment at 16 kHz over a 10-second high-energy window within the first 75 seconds (±60 ms search) using parabolic interpolation for sub-millisecond accuracy.
4. Places every take on a unified **Master Timeline** starting at `0:00.0` for whichever take began recording earliest, so identical musical moments line up vertically across all lanes.

### Sync & Info Dialog (Manual Nudge & Take Removal)
In each take's left lane header, tap the sync badge (labeled **Reference**, **`+125ms`**, **`-42ms`**, or **No Audio** with the slider icon) to open **`<Take> Sync & Info`**:
- **Info Card:** Displays filename, display resolution, duration, automatic sync offset with match confidence `%`, current manual nudge, and total effective offset. (If a video has no audio track, it displays *"No audio track detected (use manual nudge)"*.)
- **Fine Sync Adjustment:** Tap **`-20ms`**, **`-5ms`**, **`-1ms`**, **`+1ms`**, **`+5ms`**, or **`+20ms`** to shift the take earlier or later relative to Take 1. Timeline segments automatically stay bounded within valid take coverage.
- **Reset Nudge:** Restores the manual nudge to `0 ms` (back to the automatic alignment offset).
- **Remove:** Deletes that take from the session (available when 2 or more takes are loaded), renumbers remaining takes, and cleans up unused audio cache.

---

## 4. Previewing, Auditioning & Multi-Angle View

### Video Preview Card
- **Tap anywhere on the single-angle preview** to **Play / Pause**.
- **Top-left badge** shows the current preview state:
  - **`COMP • Take X`**: Following the active segment in the Master Comp.
  - **`AUDITION LOCK • Take X`**: Temporarily locked onto one take for auditioning in Comp mode.
  - **`COMPARE • Take X`**: Viewing a focused take in Compare mode.
  - **`MULTI-ANGLE • Take X`**: Multi-angle grid view is active.
- **Top-right badge** displays `<playhead time> / <total master duration>`.
- **Bottom-left amber badge (`CROSSFADE <N>ms`)**: Appears briefly whenever playback in Comp mode crosses within ±120 ms of an interior cut boundary.
- **Bottom-right toggle (`Multi-Angle` / `Follow Comp`)**: Shown when 2 or more takes are loaded.

### Multi-Angle Split Grid
Tap **Multi-Angle** to view up to **4 takes** simultaneously in a synchronized split grid (2×1 for 2 takes, 2×2 for 3–4+ takes; up to 4 hardware video decoders run concurrently):
- Each tile shows its label (`Take X • ON`, `Take X • LOCK`, and `(M)` if muted).
- **Tap any tile:**
  - In **Comp** mode: Locks audition playback to that angle (tap again to unlock).
  - In **Compare** mode: Switches video focus to that angle.
- Tap **Follow Comp** to return to full-size single-angle preview.

### Transport & Quick Audition Strip
- **Previous Cut (`|<`)** / **Next Cut (`>|`)**: Jumps to the previous or next cut boundary on the Master Comp timeline.
- **Play / Pause**: Starts or pauses synchronized audio/video playback.
  - In **Comp** mode (without Audition Lock), playback runs through your Master Comp segments in sequence and jumps cleanly across cuts with live equal-power audio crossfades.
  - In **Compare** mode or **Audition Lock**, playback runs linearly along the full Master Timeline.
- **Quick Audition / Focus Strip** (visible with 2+ takes):
  - In **Comp** mode (**`Audition:`**): Tap any take chip (**Take 1**, **Take 2**, …) to toggle **`Take X (Lock)`**, letting you preview that take's video and audio at the current spot without modifying your Master Comp cuts. Tap **Auto Comp** (or tap the locked chip again) to return to the Master Comp.
  - In **Compare** mode (**`Focus:`**): Tap any take chip to switch which take is highlighted and shown in single-angle video preview.

---

## 5. Comp Mode vs. Compare Mode (Mute & Solo)

Use the **Comp | Compare** pill toggle on the right side of the transport bar to switch listening modes:

- **Comp (`Comp`)**:
  - Previews your edited Master Comp exactly as it will export, using your current **Export** audio setting (**Active Take Audio** with crossfade, or **Lock Audio Track**).
- **Compare (`Compare`)**:
  - Plays all unmuted takes mixed together simultaneously along the Master Timeline so you can verify sync accuracy (listen for phasing or double hits) and compare performances.
  - **Mute (`M`) & Solo (`Solo`)** buttons in each take's left lane header control the Compare mix:
    - If one or more takes have **Solo** active, only soloed takes are heard.
    - Otherwise, all takes without **M** active are heard.
    - Tapping **M** or **Solo** while in Comp mode automatically switches to **Compare** mode and focuses that take so you immediately hear the change.
    - *Note:* **M** and **Solo** apply only to Compare mode listening; they do not silence takes inside your Master Comp segments or exported video.

### Comp vs. Compare at a Glance

| Aspect | **Comp** | **Compare** |
|---|---|---|
| **Purpose** | Preview your edited Master Comp exactly as it will export | Check alignment across takes and audition/compare raw takes |
| **Audio playback** | Plays **one** take at a time: either **Active Take Audio** (switching at cuts with your `2–50 ms` equal-power crossfade) or **Lock Audio Track** (continuous single-take audio) | **Simultaneously mixes** all unmuted takes (or only **Solo**ed takes if any **Solo** is active) |
| **Mute (`M`) & Solo** | **Ignored** in Comp playback and export | **Controls** which takes are included in the live audio mix |
| **Video preview (single-angle)** | Automatically switches camera angles at each cut boundary (`COMP • Take X`), unless you lock a take with **Audition** (`AUDITION LOCK • Take X`) | Stays on the take selected in **Focus:** / lane header / Multi-Angle tile (`COMPARE • Take X`) |
| **Timeline clock & range** | Follows your edited comp segments (respecting outer yellow trim handles and jumping across cuts); switches to linear Master clock only while an **Audition Lock** is active | Always runs linearly across the **full Master Timeline** (`0:00` to end of longest take), ignoring comp trims and cuts |
| **Quick strip below transport** | Labeled **`Audition:`** (`Auto Comp` + `Take X (Lock)` temporary override) | Labeled **`Focus:`** (`Take 1`, `Take 2`, … to switch the viewed video angle) |
| **Multi-Angle tile tap** | Toggles **Audition Lock** on that take | Switches **Focus** to that take |
| **Crossfade indicator** | Shows amber `CROSSFADE XXms` badge within ±120 ms of interior cuts | Not shown (no comp crossfades are occurring) |

---

## 6. Editing the Master Comp Timeline

The timeline is split into a pinned top **Time Ruler + MASTER track** and vertically scrollable **Take Lanes** below.

### Scrubbing & Zooming
- **Scrub:** Tap or drag anywhere along the top **Time Ruler** (`0:00`, `0:05`, …) to move the red playhead.
- **Zoom:** Use the **`-` / `+`** controls in the timeline toolbar to zoom from **`1.0x`** up to **`12.0x`**. During playback, the timeline scrolls automatically to keep the red playhead in view.

### Timeline Tools
Select one of three tools on the left side of the timeline toolbar:

1. **Pan** *(default, first tool on the left)*
   - Lets you drag horizontally to scroll a zoomed-in timeline without accidentally painting cuts.
   - Tapping a take lane while in Pan mode still switches the segment at that spot to that take.
2. **Swipe Comp**
   - **Swipe horizontally** across any take lane to assign that exact time range (`start – end`) to that take in the Master Comp. A live floating tooltip shows the selected start, end, and duration (minimum segment length: `0.15 s`).
   - **Tap** a take lane to switch the comp segment at that timestamp to that take. If you tap outside the current comp span where that take has coverage, it paints a 3-second segment (`±1.5 s`) around your tap.
3. **Split Tap**
   - **Tap on a take lane:** Splits the Master Comp at that timestamp and assigns everything from the cut forward (within that segment) to the tapped take.
   - **Tap on the MASTER track:** Splits the segment at that timestamp and cycles the right-side segment to the next covering take (`Take 1 → Take 2 → Take 3 → Take 1`, or `Take 1 → Take 2 → Take 1` with two takes).

### Toolbar Actions
- **Undo** *(appears first on the far left whenever you have edits to undo)*: Undoes up to **25** recent Master Comp segment edits (splits, swipes, switches, boundary drags, merges, deletions, Auto-Split, Reset).
- **Split Here:** Splits the Master Comp segment at the current red playhead position (keeping the same take on both sides).
- **Auto-Split** *(shown with 2+ takes)*: Divides the shared overlapping span evenly across all takes (`Take 1`, `Take 2`, `Take 3`…) so you can immediately preview multi-angle cuts.
- **Reset:** Resets the Master Comp back to a single segment covering all of **Take 1**.

### Adjusting Boundaries & Trimming
On the **MASTER** track:
- **Move a cut:** Drag any **white pill handle** between two segments left or right to fine-tune the cut point. (Constrained by the `0.15 s` minimum segment duration and each take's available start/end bounds.)
- **Trim comp start/end:** Drag the **yellow handle** at the start of the first segment or the end of the last segment to trim the overall start or end of your Master Comp.

### Selected Segment Bar & Cut Decision List
- **Selected Segment Bar (`Part X/Y`):** Tap any segment on the **MASTER** track (with **Swipe Comp** or **Pan** active) to select it and show quick actions below the timeline:
  - **Take chips:** Reassign the selected part to another take.
  - **Play Part:** Jumps to the start of the selected part and starts playback in Comp mode.
  - **Merge Left** *(Part 2 onward)*: Combines the selected part back into the previous part (when the adjacent take covers the combined span).
  - **Remove** *(when 2+ parts exist)*: Deletes the selected part and closes the gap.
- **Cut Decision List (`Cuts (N):`):** Located along the bottom of the screen, listing every part (`#1 0:00.0→0:06.5 • Take 1`, etc.). Tap any chip to select that part and jump the playhead straight to its start.

---

## 7. Exporting Your Master Video

Tap **Export** in the top-right bar to open the **Export Master Video** sheet.

### Export Options
1. **Audio Stitching Mode**
   - **Active Take Audio** (*"Follows each cut"*): Switches audio at every cut to match the active video take, blended with a sample-accurate equal-power (`sin`/`cos`) crossfade so there are no clicks or silence gaps.
     - **Equal-Power Cross-Fade (`2–50 ms`):** Adjust the crossfade length (default **`12 ms`**). *This setting also controls the live crossfade you hear during **Comp** playback.*
   - **Lock Audio Track** (*"Uncut audio + video cuts"*): Keeps one chosen take's audio track playing continuously from start to finish while cutting between video angles. Select your preferred take under **Select Continuous Audio Source** (a yellow warning appears if that take is shorter than the full comp range). *This setting also applies to live **Comp** playback.*
2. **Snap cuts to quietest valley** *(default: ON)*
   - Automatically shifts **new** splits, swipe-paint edges, and **Auto-Split** boundaries up to ±45 ms into the quietest nearby waveform valley so cuts avoid slicing mid-transient (such as a snare hit or vocal syllable).
   - *Tip:* This switch takes effect for future timeline edits made after toggling it; you can still drag white cut handles manually anywhere you like afterward.
3. **Output Quality**
   - **Original Resolution:** Exports at the reference take's original display resolution (rounded to even dimensions).
   - **1080p HD (Faster):** Scales down if the short side exceeds 1080 px (for example, 4K → 1080p) for faster hardware encoding and smaller file sizes.

### Saving & Sharing
- Tap **Save Video to Gallery**. Take Master pre-renders a gapless 48 kHz 16-bit stereo WAV audio mix and stitches the clipped video segments using Media3's hardware-accelerated `Transformer`.
- You can monitor the percentage progress bar or tap **Cancel Export** at any time.
- Exported MP4 files are saved to **`Movies/TakeMaster/TakeMaster_YYYYMMDD_HHmmss.mp4`** and appear in your phone's Gallery.
- When finished, tap **Play Video** to open it in a video player or **Share** to send it directly.

---

## 8. Tips & Troubleshooting

- **Make sure takes overlap early in the recording:** Auto-alignment analyzes up to the first 120 seconds (coarse) and 75 seconds (fine), within a ±60 second start-time difference, requiring at least 30% overlap. Start your backing track or count-in within the first minute of recording each take.
- **Fixing slight acoustic delay or lip/stick sync:** If one angle was recorded with a different microphone distance or wireless latency, tap its sync badge (`+XXms`), switch to **Compare** mode, and nudge by **`±1ms`** or **`±5ms`** while listening until transients snap into a single tight hit.
- **Video shows "No Audio":** Silent screen recordings or camera angles without an audio track cannot be auto-aligned, so they default to `0 ms`. Use the **Sync & Info** dialog's manual nudge buttons (`±20ms`, `±5ms`, `±1ms`) to align them visually in **Multi-Angle** view.
- **5 or 6 takes in Multi-Angle view:** While you can load up to **6** takes into a project, the **Multi-Angle** split grid displays up to **4** tiles at a time (keeping up to 4 concurrent hardware video decoders active, prioritized by your Audition Lock, Focused take, and Comp segments). You can still edit and comp across all 6 takes on the timeline.
- **Landscape vs. Portrait:** Rotate your device to **Landscape** for a split studio view (video preview & transport on the left, wide multi-track timeline on the right), or use **Portrait** for one-handed timeline swiping.