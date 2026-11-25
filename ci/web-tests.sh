#!/usr/bin/env bash
# The web app's Chromium tests, in shards that take about the same time each (seconds measured
# on CI, in brackets). Usage: ci/web-tests.sh <1-4|subs>. Needs the built web/dist served at
# http://localhost:4173 (vite preview) and the test-media clips in /tmp/samples.
#
# Not run (see the PR that introduced this): `e2e.cjs ... tablet` [34 s] and `edit.cjs ... phone`
# [18 s], variants of flows that run on desktop (and, for e2e, on a phone) in this list.
set -euo pipefail
cd "$(dirname "$0")/../web"
# ?coi=0: the service worker doesn't isolate the page (no reload on the first visit): the same pages as before it was added. coi.cjs and the subs job test the isolated page.
U="http://localhost:4173/?backend=wasm&coi=0"
S=/tmp/samples
R=/tmp/web-results
# A script that fails is run once more (a timing flake shows as a warning); two failures fail the shard.
t() {
  echo "::group::$*"
  node "tests/$1" "${@:2}" || { echo "::warning::tests/$1 failed once, running it again"; node "tests/$1" "${@:2}"; }
  echo "::endgroup::"
}

case "${1:?shard}" in
  1) # 157 s
    # The picture: a green screen, a 9:16 frame, a steadied camera, the people's background.
    t picture.cjs "$U" $S/green_screen.mp4 $S/classroom.mp4 $S/walk.mp4 $S/speech_two.mp4 $R/picture   # 107
    # Settings: language, light / dark / the device's, the color palette; kept after a reload.
    t settings.cjs "$U" $R/settings   # 2
    # A video shared to the installed app (Web Share Target) or opened with it.
    t share.cjs "$U" $S/walk.mp4 $R/share   # 1
    # The analysis stats panel: tiles and charts that update, more details, an (i), Copy (the report).
    t stats.cjs "$U" $S/walk.mp4 $R/stats   # 22
    # The page is cross-origin isolated by the service worker (threads for WebAssembly): one reload on the first visit, none after.
    t coi.cjs "http://localhost:4173/?backend=wasm" $R/coi   # 8
    # Playback over cuts close together (one a frame apart, one to the end of the video): the clock keeps moving, no jumping again and again.
    t cutskip.cjs "$U" $S/speech_two.mp4 $R/cutskip   # 25
    ;;
  2) # 146 s
    # A deep voice on a clip with speech.
    (export VOICE=1; t e2e.cjs "$U" $S/speech_two.mp4 $R/voice)   # 43
    # The ready screen: the video first, the blur's settings, "blur and save" without the editor.
    t ready.cjs "$U" $S/walk.mp4 $S/classroom.mp4 $R/ready   # 39
    # Speakers found from their voices (the sample's two halves).
    t speakers.cjs "$U" $S/speech_two.mp4 $R/speakers   # 8
    # The speech model's flow with no download: faster / better quality kept, a stuck recognition ends with an error and a retry, Cancel ends the worker, a template asks before downloading.
    t subsflow.cjs "$U" $S/speech_two.mp4 $R/subsflow   # 40
    # Subtitles on a phone (390 px): the one button on the first screen, the looks as drawn cards, one tap applies one, a subtitle edited where it is, 44 px targets. No model.
    t subsmobile.cjs "$U" $S/speech_two.mp4 $R/subsmobile   # 8
    # The editor's panels on a phone (390 px), as the app's: each group one compact panel of a bounded height with its tools as chips, the video in view, nothing sideways, no empty "more options".
    t panels.cjs "$U" $S/speech_two.mp4 $R/panels   # 140
    # An SRT imported over subtitles that have bleeped words asks first; keeping changes nothing; replacing is undone with its words and bleeps.
    t subsimport.cjs "$U" $S/speech_two.mp4 $R/subsimport   # 8
    # Where it was filmed and on what phone don't go out with the export.
    t privacy.cjs "$U" $S/speech_two.mp4 $R/privacy   # 10
    # The keyboard: groups by number, play / pause, a frame step, undo and redo.
    t keys.cjs "$U" $S/walk.mp4 $R/keys.png   # 6
    ;;
  3) # 177 s
    # Subtitle design: styles, fonts (one imported with Hebrew, one without), effects, burned into the export.
    t style.cjs "$U" $S/speech_two.mp4 ../app/src/main/assets/fonts/heebo.ttf /usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf $R/style   # 33
    # Face detection on the CPU path, the editor, the export: desktop and a phone.
    t e2e.cjs "$U" $S/walk.mp4 $R/desktop   # 20
    t e2e.cjs "$U" $S/walk.mp4 $R/phone phone   # 22
    # Sounds imported from the device: kept, placed, mixed into the export, deleted.
    t mysounds.cjs "$U" $S/walk.mp4 $R/mysounds   # 7
    # Templates (a built-in look applied, edited and restored, copied and deleted) and two videos
    # at once as a batch kept in a folder of projects. The speech model is blocked, not downloaded.
    t templates.cjs "$U" $S/speech_two.mp4 $S/walk.mp4 $R/templates   # 95
    ;;
  4) # 165 s
    # The sound's cleanup, MP3 (from the editor and the home screen), a lower size and frame rate.
    t sound.cjs "$U" $S/speech_two.mp4 $S/speech_en.mp4 $S/classroom60.mp4 $R/sound   # 19
    # A sound step that fails never exports the original voice: the editor's export stays blocked, a batch item fails.
    t soundfail.cjs "$U" $S/speech_two.mp4 $R/soundfail   # 15
    # Editing: a blur by hand (followed, split, deleted, ended), cuts by hand and of pauses.
    t edit.cjs "$U" $S/speech_two.mp4 $R/edit   # 19
    # A tool from the home screen; faces found later; the editor kept across the export.
    t tools.cjs "$U" $S/walk.mp4 $R/tools   # 17
    # No faces found: wider detection offered, a blur by hand in the editor.
    t nofaces.cjs "$U" $R/nofaces   # 14
    # Subtitles as a list (from an SRT) and their translation; projects kept, opened, renamed, deleted.
    t project.cjs "$U" $S/speech_two.mp4 $R/project   # 12
    # The landing page (/home/): it loads whole, its parts work, its links lead to the editor.
    t home.cjs "http://localhost:4173/" $R/home   # 24
    # A template renamed where it is picked; no face detection with no face blur (one video, a batch); the home screen's several videos apart; the template editor's subtitle preview moving; a template's font; a template saved from a tool's video.
    t usernotes.cjs "$U" $S/speech_two.mp4 $S/walk.mp4 $R/usernotes   # 60
    ;;
  subs) # Whisper's model downloads from Hugging Face: its own job, only when subtitle code changes. The small one (whisper-base, 77 MB on the CPU) is forced: the phone path, and quick. The page is isolated here (threads), as on the site.
    t subs.cjs "http://localhost:4173/?backend=wasm&subsmodel=base" $S/speech_en.mp4 $R/subs   # 60
    # The same clip on one thread (as before the page was isolated): the two recognition times are in the log.
    t subs.cjs "http://localhost:4173/?backend=wasm&subsmodel=base&threads=1" $S/speech_en.mp4 $R/subs1
    ;;
  *) echo "unknown shard: $1" >&2; exit 2 ;;
esac
