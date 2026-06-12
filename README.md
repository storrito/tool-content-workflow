# Content subtitle workflow

Convention-based local workflow for smartphone videos. `input.mp4` is expected to be a vertical 9:16 video. If the phone stored the orientation as rotation metadata, the final overlay step bakes that rotation into the pixels and normalizes the video to `1080x1920`:

1. transcribe `input.mp4` with [`storrito/tool-speech-to-text`](https://github.com/storrito/tool-speech-to-text)
2. merge the transcript into `params.edn`
3. render animated transparent subtitle frames with [`storrito/tool-shortform-subtitles`](https://github.com/storrito/tool-shortform-subtitles)
4. overlay the frames onto a physical `1080x1920` MP4 with FFmpeg running in Docker, without relying on rotation metadata

## Requirements

- Babashka (`bb`)
- Docker
- Git for first-time tool checkout

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

Run from the project root:

```bash
./subtitle-video.bb
```

## Outputs

The workflow writes files in the project root:

```text
content-workflow/
  input.mp4
  params.edn              # optional user input, never modified
  params_transcribed.edn  # generated params + :segments, :words, and :duration
  frames/                 # freshly generated subtitle frames
    frame_000001.png
    frame_000002.png
    ...
  output.mp4
```

`output.mp4` is always overwritten.

## Params

Supported optional workflow params:

```clojure
{:template :caption-clip-wipe} ; or :caption-emoji-pop
```

Any subtitle-template params accepted by `tool-shortform-subtitles` can also live in the same map, for example:

```clojure
{:template :caption-emoji-pop
 :word-emoji {"schedule" "📅"
              "video" "🎬"}}
```

Every run regenerates the transcript from `input.mp4` and writes it into `params_transcribed.edn`. `params.edn` is never modified. `frames/` is freshly generated on every run.
