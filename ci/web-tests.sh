#!/usr/bin/env bash
# The web app's tests. Usage: ci/web-tests.sh <unit|1-7|subs>.
#   unit  the pure tests (no browser, no server, no media; a few seconds): run them first, so a failure shows fast.
#   1-7   the Chromium tests, in shards that take about the same time each (seconds measured on CI, in brackets;
#         [~n] estimated from a local run). They need the built web/dist served at http://localhost:4173
#         (vite preview) and the test-media clips in /tmp/samples.
#   subs  the subtitles (Whisper and ivrit.ai's models, downloaded): its own job.
# FULL=1 (the weekly and manual runs) adds the longer variants: picture.cjs on the people's whole video, and
# `e2e.cjs ... tablet` [34 s] and `edit.cjs ... phone` [18 s] (variants of flows that run on desktop, and for e2e on
# a phone, in every run).
set -euo pipefail
cd "$(dirname "$0")/../web"
# ?coi=0: the service worker doesn't isolate the page (no reload on the first visit): the same pages as before it was added. coi.cjs and the subs job test the isolated page.
U="http://localhost:4173/?backend=wasm&coi=0"
S=/tmp/samples
R=/tmp/web-results
FULL="${FULL:-0}"
export FULL
# A script that fails is run once more (a timing flake shows as a warning); two failures fail the shard.
# The failure's own lines (FAIL ..., the error and its first call frames), as an annotation anyone can read
# on the run page (the log itself needs sign-in). A GitHub annotation is one line: newlines are %0A.
note() {
  local level=$1 title=$2 log=$3 text
  text=$(grep -v '^\s*at .*node_modules' "$log" | grep -E -A12 'FAIL|Error|error:|Timeout|expect' | head -40)
  [ -n "$text" ] || text=$(tail -25 "$log")
  text=${text//'%'/'%25'}; text=${text//$'\r'/}; text=${text//$'\n'/'%0A'}
  echo "::$level title=$title::$text"
}
t() {
  echo "::group::$*"
  local log
  log=$(mktemp)
  if ! node "tests/$1" "${@:2}" 2>&1 | tee "$log"; then
    note warning "tests/$1 failed once, running it again" "$log"
    if ! node "tests/$1" "${@:2}" 2>&1 | tee "$log"; then
      echo "::endgroup::"
      note error "tests/$1 failed" "$log"
      rm -f "$log"
      return 1
    fi
  fi
  rm -f "$log"
  echo "::endgroup::"
}

case "${1:?shard}" in
  unit) # 3 s
    # Bleeped words never show in the subtitles: 5000 random sequences of split, merge, retime, delete, bleep, edit, translate and undo (pure, no browser).
    node tests/splitmask.cjs   # 2
    # Face detection's pure parts: YuNet's decoding, the fusion with RetinaFace (the app's rules), the pace (1280 / 960 / 640 px, the full rate down to 8 a second), which frames get a detection and what that really covered (any frame rate, timestamps, VFR, cuts; never weaker in frames than before), the low-confidence flag, fast detection's 5 a second, the sensitivity (0 the default exactly; a higher one never covers less) (pure, no browser).
    node tests/fusion.cjs   # 1
    # Selfie detection's pure parts: BlazeFace's anchors, decoding, weighted NMS and letterbox against MediaPipe's own results (the Android app's BlazeFaceTest checks the same fixture), the sensitivity's threshold (0.5 at 0 exactly, 0.3 at 1), the choice of mode (pure, no browser).
    node tests/blazeface.cjs   # 1
    # The bleep's loudness follows the speech it replaces (the app's BleepLevelTest checks the same vectors): a quiet word a quiet beep, a loud one a louder beep under the cap, nothing clips (pure, no browser).
    node tests/bleep.cjs   # 1
    node tests/cutsrt.cjs   # 1
    ;;
  1) # about 187 s
    # Face detection with YuNet: RetinaFace + YuNet at 960 px (in the detector workers, and as one detector with YuNet in its own worker) finds no fewer faces than the old web detector (RetinaFace alone, 640 px) on the same frames of the stress clip and the classroom.
    t detect.cjs "$U" $R/detect 1.5 $S/stress_cuts.mp4 $S/classroom.mp4   # 73
    # Templates (a built-in look applied, edited and restored, copied and deleted) and two videos
    # at once as a batch kept in a folder of projects. The speech model is blocked, not downloaded.
    t templates.cjs "$U" $S/speech_two.mp4 $S/walk.mp4 $R/templates   # 88
    # Settings: language, light / dark / the device's, the color palette; kept after a reload.
    t settings.cjs "$U" $R/settings   # 2
    # A video shared to the installed app (Web Share Target) or opened with it.
    t share.cjs "$U" $S/walk.mp4 $R/share   # 2
    # The page is cross-origin isolated by the service worker (threads for WebAssembly): one reload on the first visit, none after.
    t coi.cjs "http://localhost:4173/?backend=wasm" $R/coi   # 8
    # Two fingers on the video resize the selected blur, one undo step each.
    t pinch.cjs "$U" $S/walk.mp4 $R/pinch   # 8
    # The keyboard: groups by number, play / pause, a frame step, undo and redo.
    t keys.cjs "$U" $S/walk.mp4 $R/keys.png   # 6
    # Open-source licences (the footer's link): the models, libraries and fonts the web app is made of, each with its notice and full text, served by the site itself (no request leaves it).
    t licences.cjs "$U" $R/licences   # 3
    ;;
  2) # about 189 s
    # The editor's panels on a phone (390 px), as the app's, part 1: each group one compact panel of a bounded height with its tools as chips, the video in view, nothing sideways, no empty "more options" (he and en, four phone sizes); no detection; jobs that outlive a group switch.
    t panels.cjs "$U" $S/speech_two.mp4 $R/panels1 $S/classroom.mp4 1   # ~65
    # Detection that ran lighter than the app's is never silent: the notice in the editor, on a phone, on the "no faces" screen (he and en), none after a full run; "blur and save straight" stopping at the check screen.
    t reduced.cjs "$U" $S/walk.mp4 $R/reduced   # 63
    # Subtitle design: styles, fonts (one imported with Hebrew, one without), effects, burned into the export.
    t style.cjs "$U" $S/speech_two.mp4 ../app/src/main/assets/fonts/heebo.ttf /usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf $R/style   # 38
    # An SRT imported over subtitles that have bleeped words asks first; keeping changes nothing; replacing is undone with its words and bleeps.
    t subsimport.cjs "$U" $S/speech_two.mp4 $R/subsimport   # 5
    # Speakers found from their voices (the sample's two halves).
    t speakers.cjs "$U" $S/speech_two.mp4 $R/speakers   # 10
    # Sounds imported from the device: kept, placed, mixed into the export, deleted.
    t mysounds.cjs "$U" $S/walk.mp4 $R/mysounds   # 8
    if [ "$FULL" = 1 ]; then
      # Face detection, the editor and the export on a tablet.
      t e2e.cjs "$U" $S/walk.mp4 $R/tablet tablet   # 34
    fi
    ;;
  3) # about 198 s
    # The editor's panels on a phone, part 2: many face tracks and a failed sound step on the smallest phone; the phone's states at four sizes and on short screens (he and en); a wide screen's "more options".
    t panels.cjs "$U" $S/speech_two.mp4 $R/panels2 $S/classroom.mp4 2   # ~63
    # A deep voice on a clip with speech.
    (export VOICE=1; t e2e.cjs "$U" $S/speech_two.mp4 $R/voice)   # 58
    # The editor as the app's, part two: effect cards that play (still with less motion), font cards each in its font, a group's tool chips on one row (360-412 px, he and en), the tablet layouts (upright under 840 px: one column; the rail of groups and the panel beside the video).
    t parity.cjs "$U" $S/speech_two.mp4 $R/parity ../app/src/main/assets/fonts/heebo.ttf   # 54
    # Subtitles on a phone (390 px): the one button on the first screen, the looks as drawn cards, one tap applies one, a subtitle edited where it is, 44 px targets. No model.
    t subsmobile.cjs "$U" $S/speech_two.mp4 $R/subsmobile   # 10
    # Where it was filmed and on what phone don't go out with the export.
    t privacy.cjs "$U" $S/speech_two.mp4 $R/privacy   # 13
    ;;
  4) # about 187 s (FULL=1: about 250 s)
    # The picture: a green screen, a 9:16 frame, a steadied camera, the people's background (on 6 s of their video; FULL=1: all of it).
    t picture.cjs "$U" $S/green_screen.mp4 $S/classroom.mp4 $S/walk.mp4 $S/speech_two.mp4 $R/picture   # ~60
    # The ready screen: the video first, the blur's settings, "blur and save" without the editor.
    t ready.cjs "$U" $S/walk.mp4 $S/classroom.mp4 $R/ready   # 46
    # A template renamed where it is picked; no face detection with no face blur (one video, a batch); the home screen's several videos apart; the template editor's subtitle preview moving; a template's font; a template saved from a tool's video.
    t usernotes.cjs "$U" $S/speech_two.mp4 $S/walk.mp4 $R/usernotes   # 43
    # The speech model's flow with no download: faster / better quality kept, a stuck recognition ends with an error and a retry, Cancel ends the worker, a template asks before downloading.
    t subsflow.cjs "$U" $S/speech_two.mp4 $R/subsflow   # 38
    # A subtitles job shows life at once (no model: a stub for the Hub): the model is asked for at the start, beside the sound's reading, and the sound's percent shows with the model's phase.
    t subsstart.cjs "$U" $S/speech_two.mp4 $R/subsstart strict   # 8
    ;;
  5) # about 210 s
    # A subtitle edited so a bleeped word may show: the alert, the mark on the timeline, the export asking first; a deleted subtitle's bleeps listed.
    t maskwarn.cjs "$U" $S/speech_two.mp4 $R/maskwarn   # 37
    # SRT is no switch in the export sheet: "Save subtitles (SRT)" in the subtitles panel writes the masked file; the unmasked one (for editing) asks first.
    t srtsave.cjs "$U" $S/speech_two.mp4 $R/srtsave   # 12
    # The landing page (/home/): it loads whole, its parts work, its links lead to the editor.
    t home.cjs "http://localhost:4173/" $R/home   # 32
    # The watermark's logo kept between visits and a template's logo shown; the template editor asking before it drops changes, and "Edit" for the chosen template (ready screen, batch); the safe zone's switch, chooser and mock (9:16 only, never in the export); the thin scrubber over a phone's panel; the low-confidence rule.
    t parity2.cjs "$U" $S/speech_two.mp4 $S/stress_cuts.mp4 $R/parity2   # 30
    # Playback over cuts close together (one a frame apart, one to the end of the video): the clock keeps moving, no jumping again and again.
    t cutskip.cjs "$U" $S/speech_two.mp4 $R/cutskip   # 30
    # Face detection on the CPU path, the editor, the export: desktop and a phone.
    t e2e.cjs "$U" $S/walk.mp4 $R/desktop   # 28
    t e2e.cjs "$U" $S/walk.mp4 $R/phone phone   # 28
    if [ "$FULL" = 1 ]; then
      # Editing on a phone.
      t edit.cjs "$U" $S/speech_two.mp4 $R/editp phone   # 18
    fi
    # The editor as the app's, part three (in English): a subtitle merged with the one before or after it, a tablet's side panel with
    # the selected subtitle's properties, the blur view (blur / outlines), the export's size estimate, a failed export's details to
    # copy, the report of an error nothing caught.
    t parity3.cjs "$U" $S/speech_two.mp4 $R/parity3   # 25
    ;;
  6) # about 210 s
    # The sound's cleanup, MP3 (from the editor and the home screen), a lower size and frame rate.
    t sound.cjs "$U" $S/speech_two.mp4 $S/speech_en.mp4 $S/classroom60.mp4 $R/sound   # 28
    # No faces found: wider detection offered, a blur by hand in the editor.
    t nofaces.cjs "$U" $R/nofaces   # 25
    # Editing: a blur by hand (followed, split, deleted, ended), cuts by hand and of pauses.
    t edit.cjs "$U" $S/speech_two.mp4 $R/edit   # 24
    # A tool from the home screen; faces found later; the editor kept across the export.
    t tools.cjs "$U" $S/walk.mp4 $R/tools   # 22
    # A sound step that fails never exports the original voice: the editor's export stays blocked, a batch item fails.
    t soundfail.cjs "$U" $S/speech_two.mp4 $R/soundfail   # 19
    # Subtitles as a list (from an SRT) and their translation; projects kept, opened, renamed, deleted.
    t project.cjs "$U" $S/speech_two.mp4 $R/project   # 15
    # Which speech model the subtitles tab offers (no download): ivrit.ai's for Hebrew, with its size; Whisper for the other languages.
    t subsplan.cjs "$U" $S/speech_two.mp4 $R/subsplan   # 15
    # The analysis stats panel: tiles and charts that update, more details, an (i), Copy (the report).
    t stats.cjs "$U" $S/walk.mp4 $R/stats   # 14
    # The export's stats panel: a tap on the percentage opens tiles (encode speed, frames, blur per frame, encoder, time), details, (i), Copy; phone (he) and desktop (en); the pure history and report.
    t exportstats.cjs "$U" $S/speech_two.mp4 $R/exportstats   # ~25
    # Subtitles on the timeline, as the app's: a tap selects, the bar's Start / End here, split, merge, delete and text, the edges dragged, all undone; bleeps follow their words. Desktop and a phone.
    t cues.cjs "$U" $S/speech_two.mp4 $R/cues   # 13
    t cues.cjs "$U" $S/speech_two.mp4 $R/cuesp phone   # 13
    # A template's translation (no model, a stubbed translator): the editor's switch and the video's four choices, the switch on the ready screen, translated in "save straight away" and in the editor it opens; a browser with none: a note, the subtitles kept.
    t tpltranslate.cjs "$U" $S/speech_two.mp4 $R/tpltranslate   # 22
    ;;
  7) # about 220 s
    # Which frames get a detection, measured in the browser: a clip re-timed to 20, 24, 25 (WebM), 30 and 31 fps at the full rate (15 a second or the app's cadence), no notice; a tune under the need measured short.
    t gate.cjs "$U" $S/walk.mp4 $R/gate   # ~70 (110 measured locally)
    # Fast detection and the sensitivity: the ready screen's modes, fast's warning and the slider; a fast run measured near 5 a second
    # (not reduced) with its notice kept in the editor (he; en on a phone) and "blur and save straight" stopping at the check screen;
    # a higher sensitivity finding extra detections and no fewer faces (classroom, pinned at 320 px).
    t fast.cjs "$U" $S/walk.mp4 $S/classroom.mp4 $R/fast   # ~90 (measured locally)
    # Selfie detection (BlazeFace on every frame): the ready screen's four modes and selfie's warning; a face on (nearly) every frame of two
    # talking-head clips (one stored sideways), the notice kept in the editor (he; en on a phone), faster than normal detection on the same
    # clip; "blur and save straight" stopping at the check screen; the mode kept in a template (older versions read normal); a sensitivity
    # finding extra detections and no fewer faces (classroom).
    t selfie.cjs "$U" $S/headpose.mp4 $S/portrait_rot90.mp4 $S/classroom.mp4 $R/selfie   # ~60 (measured locally)
    ;;
  subs) # Whisper's model downloads from Hugging Face: its own job, only when subtitle code changes. The small one (whisper-base, 77 MB on the CPU) is forced: the phone path, and quick. The page is isolated here (threads), as on the site.
    t subs.cjs "http://localhost:4173/?backend=wasm&subsmodel=base" $S/speech_en.mp4 $R/subs   # 60
    # The same clip on one thread (as before the page was isolated): the two recognition times are in the log.
    t subs.cjs "http://localhost:4173/?backend=wasm&subsmodel=base&threads=1" $S/speech_en.mp4 $R/subs1
    # Hebrew with ivrit.ai's model (the app's), on the CPU: a clip of Hebrew speech made here by a text-to-speech
    # voice (edge-tts, Microsoft's he-IL voice; nothing of a person's), over the picture of walk.mp4. The model is 760 MB.
    # The test runs twice (the second time with the Hub blocked). If the voice service can't be reached the test is
    # skipped with a warning (it isn't the app's failure); speech_he.mp4 on the test-media branch would replace this.
    mkdir -p /tmp/he
    HE=$S/speech_he.mp4
    if [ ! -s "$HE" ]; then
      TEXT="שלום, קוראים לי אבי ואני גר בתל אביב. היום מזג האוויר יפה מאוד בישראל."
      HE=/tmp/he/speech_he.mp4
      # (Inside the if, a failed install takes the skip below instead of failing the shard under set -e.)
      if python3 -m venv /tmp/he/venv && /tmp/he/venv/bin/pip install --quiet edge-tts==7.3.1; then
        # (A try that fails can leave part of a file: each try starts without one.)
        for i in 1 2 3; do rm -f /tmp/he/he.mp3; /tmp/he/venv/bin/edge-tts --voice he-IL-AvriNeural --text "$TEXT" --write-media /tmp/he/he.mp3 && break || { rm -f /tmp/he/he.mp3; sleep 5; }; done
      fi
      # A voice file that ffmpeg can't read (cut off) is the voice service's failure too: skipped.
      if ! { [ -s /tmp/he/he.mp3 ] && ffmpeg -v error -y -stream_loop -1 -i $S/walk.mp4 -i /tmp/he/he.mp3 -map 0:v -map 1:a -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac -ar 44100 -ac 2 $HE; }; then
        echo "::warning::edge-tts could not be installed or reached, or its voice file was unreadable: the Hebrew subtitles test (tests/subshe.cjs) was skipped"
        HE=
      fi
    fi
    if [ -n "$HE" ]; then
      t subshe.cjs "http://localhost:4173/?backend=wasm" $HE "תל אביב|ישראל" $R/subshe   # model download + two CPU runs, minutes
    fi
    ;;
  *) echo "unknown shard: $1" >&2; exit 2 ;;
esac
