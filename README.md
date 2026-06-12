# Content subtitle workflow

Convention-based local workflow for smartphone videos. `input.mp4` is expected to be a vertical 9:16 video. If the phone stored the orientation as rotation metadata, the final overlay step bakes that rotation into the pixels and normalizes the video to `1080x1920`:

1. transcribe `input.mp4` with [`storrito/tool-speech-to-text`](https://github.com/storrito/tool-speech-to-text)
2. merge the transcript into `params_transcribed.edn`
3. generate `tiktok-caption.txt` with `pi` using the transcript as context
4. render animated transparent subtitle frames with [`storrito/tool-shortform-subtitles`](https://github.com/storrito/tool-shortform-subtitles)
5. overlay the frames onto a physical `1080x1920` MP4 with FFmpeg running in Docker, without relying on rotation metadata

## Requirements

- Babashka (`bb`)
- Docker
- Git for first-time tool checkout
- `pi` on the host for TikTok caption generation

The first run clones the two tool repositories into `tools/`. The transcription tool's first Docker build downloads several GB of model/runtime data. FFmpeg/FFprobe are run through the Docker image `jrottenberg/ffmpeg:7.1-ubuntu`, so the host does not need a local FFmpeg install.

## Directory convention

Put the video input in this project's root folder:

```text
content-workflow/
  input.mp4
```

`params.edn` is optional. If it is missing, the workflow uses the default params:

```clojure
{:template :caption-clip-wipe}
```

Run the full workflow from the project root:

```bash
./workflow.bb
```

Or rerun a single step while debugging:

```bash
./transcribe.bb
./tiktok-caption.bb
./render-subtitles.bb
./overlay-video.bb
```

## Outputs

The workflow writes files in the project root:

```text
content-workflow/
  input.mp4
  params.edn              # optional user input, never modified
  params_transcribed.edn  # generated params + :segments, :words, and :duration
  tiktok-caption.txt      # generated TikTok post caption
  work/                   # freshly generated intermediate files
    transcript.edn
    tiktok-caption-prompt.md
    output_with_rotation_metadata.mp4
  frames/                 # freshly generated subtitle frames
    frame_000001.png
    frame_000002.png
    ...
  output.mp4
```

`output.mp4` is always overwritten.

## Scripts

- `workflow.bb` runs all steps in order.
- `transcribe.bb` writes `work/transcript.edn` and `params_transcribed.edn`.
- `tiktok-caption.bb` reads `params_transcribed.edn` and asks `pi` to write `tiktok-caption.txt`.
- `render-subtitles.bb` reads `params_transcribed.edn` and writes `frames/`.
- `overlay-video.bb` reads `input.mp4` and `frames/`, then writes `output.mp4` with a 6 Mbit/s video bitrate limit.

## Params

Supported optional workflow params:

```clojure
{:template :caption-clip-wipe ; or :caption-emoji-pop
 :product-name "Storrito"}   ; optional, used by tiktok-caption.bb
```

Any subtitle-template params accepted by `tool-shortform-subtitles` can also live in the same map, for example:

```clojure
{:template :caption-emoji-pop
 :word-emoji {"schedule" "📅"
              "video" "🎬"}}
```

Every `transcribe.bb` run recreates `work/`, regenerates the transcript from `input.mp4`, and writes the composed subtitle params into `params_transcribed.edn`. `params.edn` is never modified. Every `render-subtitles.bb` run recreates `frames/`.
