# Content subtitle workflow

Convention-based local workflow for smartphone videos. `input.mp4` is expected to be a vertical 9:16 video. If the phone stored the orientation as rotation metadata, the final overlay step bakes that rotation into the pixels and normalizes the video to `1080x1920`:

1. transcribe `input.mp4` with [`storrito/tool-speech-to-text`](https://github.com/storrito/tool-speech-to-text)
2. improve/correct the transcript with `pi`, add subtitle metadata like `:highlight? true`, and write `params_transcribed.edn`
3. generate `tiktok-caption.txt` with `pi` using the transcript as context
4. generate `youtube-shorts-caption.txt` and `youtube-shorts-title.txt` with `pi` using the transcript and `youtube-shorts-base-caption.txt` as context
5. generate `pinterest-description.txt` and `pinterest-title.txt` with `pi` using the transcript as context
6. render animated transparent subtitle frames with [`storrito/tool-shortform-subtitles`](https://github.com/storrito/tool-shortform-subtitles)
7. overlay the frames onto a physical `1080x1920` MP4 with FFmpeg running in Docker, without relying on rotation metadata

## Requirements

- [Babashka (`bb`)](https://github.com/babashka/babashka#quickstart)
- [Docker](https://docs.docker.com/get-started/)
- [Git](https://docs.github.com/en/get-started/git-basics) for first-time tool checkout
- [pi](https://github.com/earendil-works/pi/tree/main/packages/coding-agent#quick-start) on the host for caption generation

The first run clones the two tool repositories into `tools/`. Later runs update them with `git pull --ff-only` before using them. The transcription tool's first Docker build downloads several GB of model/runtime data. FFmpeg/FFprobe are run through the Docker image `jrottenberg/ffmpeg:7.1-ubuntu`, so the host does not need a local FFmpeg install.

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

`youtube-shorts-base-caption.txt` is required by `youtube-shorts-caption.bb`. It contains the base caption/style that pi adapts for the current YouTube Shorts video.

Run the full workflow from the project root:

```bash
./workflow.bb
```

Or rerun a single step while debugging:

```bash
./transcribe.bb
./improve-transcript.bb
./tiktok-caption.bb
./youtube-shorts-caption.bb
./pinterest-caption.bb
./render-subtitles.bb
./overlay-video.bb
```

## Internal web UI

The repository also includes a small internal web UI for teammates who should not need to run the workflow from a terminal. It runs on Babashka with its built-in `http-kit` server, Hiccup rendering, Basic Auth, and HTMX polling.

Set credentials and start the server:

```bash
CONTENT_WORKFLOW_USER=marketing \
CONTENT_WORKFLOW_PASSWORD='change-me' \
./server.bb
```

Alternatively, copy `.env.example` to `.env`, edit the values, and run `./server.bb`. `.env` and `.env.local` are git-ignored; real environment variables override values from those files.

Then open `http://localhost:8080`, upload `input.mp4`, fill in the form, and wait for the progress page to show the download links.

The web UI runs one job at a time and uses the same root-level workflow conventions as the scripts. Starting a new job replaces the previous generated input/output files.

## bundle.social publishing

The web UI can also connect social accounts and publish the generated `output.mp4` via [bundle.social](https://bundle.social/). Configure Bundle credentials either as environment variables or in `.env` before starting `server.bb`:

```bash
BUNDLE_SOCIAL_API_KEY=pk_live_...
BUNDLE_SOCIAL_TEAM_ID=team_...
```

Open `/social-accounts` to add/manage social accounts through Bundle's hosted portal. Localhost is fine for this redirect flow; no public URL is required.

After a workflow succeeds, the progress page shows the rendered video and a "Publish generated video" form. It only shows connected social accounts, lets you edit platform-specific titles/captions/descriptions, uploads `output.mp4` to Bundle, and publishes to all connected platforms shown in the form. `/posted-videos` lists the latest Bundle posts for the configured team, including status, accounts, captions/descriptions, errors, and public links when available.

You can also publish from the terminal. The CLI reads the same `.env` file, so this is enough after configuring `.env`:

```bash
./publish-to-bundle.bb --platforms TIKTOK,YOUTUBE
```

Or pass credentials through the environment:

```bash
BUNDLE_SOCIAL_API_KEY=pk_live_... \
BUNDLE_SOCIAL_TEAM_ID=team_... \
./publish-to-bundle.bb --platforms TIKTOK,YOUTUBE
```

For Pinterest, provide a board name either with `--pinterest-board` or `BUNDLE_SOCIAL_PINTEREST_BOARD`.

## Instagram Story publishing through the Storrito API

The web UI can schedule the generated `output.mp4` as an Instagram Story through a local Storrito API server. Create/copy an API credential from Storrito's API Credentials page and store it in `.env.local`:

```bash
STORRITO_API_BASE=http://7414818a-0c58-4865-aa8b-3b43cc96aa93.localhost:8080/api/v1
STORRITO_API_TOKEN=<credential-id>:<credential-secret>
STORRITO_INSTAGRAM_USERNAME=<connected-instagram-username>
# optional Link sticker
STORRITO_INSTAGRAM_STORY_LINK=https://example.com
```

Open `/storrito-api` in this workflow UI to verify the API configuration, list connected Instagram accounts, and check any existing `storyPostUuid` status without scheduling a new story.

After a workflow succeeds, the progress page shows a "Publish Instagram Story via Storrito API" form. The content workflow server exposes `output.mp4` through an unguessable local URL so the Storrito dev server can fetch it. In `storrito-server-2`, dev mode allows localhost/private URLs for this API flow; production still blocks private URLs.

If your local Storrito server already uses port 8080, start this content workflow UI on another port, for example `PORT=8091 ./server.bb`; the generated video URL uses the current request origin automatically.

## Outputs

The workflow writes files in the project root:

```text
content-workflow/
  input.mp4
  params.edn                   # optional user input, never modified
  params_transcribed.edn       # generated params + :segments, :words, and :duration
  youtube-shorts-base-caption.txt # required user input for YouTube Shorts captions
  work/                        # freshly generated intermediate files
    transcript.edn
    improve-transcript-prompt.md
    transcript-improved.edn
    tiktok-caption-prompt.md
    tiktok-caption.txt
    youtube-shorts-caption-prompt.md
    youtube-shorts-caption.txt
    youtube-shorts-title.txt
    pinterest-caption-prompt.md
    pinterest-description.txt
    pinterest-title.txt
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
- `server.bb` starts the internal upload/progress/download web UI.
- `publish-to-bundle.bb` uploads `output.mp4` to bundle.social and creates a post from the generated captions.
- `transcribe.bb` writes `work/transcript.edn`.
- `improve-transcript.bb` reads `work/transcript.edn`, asks `pi` to correct the transcript/add subtitle metadata, writes `work/transcript-improved.edn`, and writes `params_transcribed.edn`.
- `tiktok-caption.bb` reads `params_transcribed.edn` and asks `pi` to write `work/tiktok-caption.txt`.
- `youtube-shorts-caption.bb` reads `params_transcribed.edn` and `youtube-shorts-base-caption.txt`, then asks `pi` to write `work/youtube-shorts-caption.txt` and `work/youtube-shorts-title.txt`.
- `pinterest-caption.bb` reads `params_transcribed.edn`, then asks `pi` to write `work/pinterest-description.txt` and `work/pinterest-title.txt` using natural Pinterest keywords instead of hashtags.
- `render-subtitles.bb` reads `params_transcribed.edn` and writes `frames/`.
- `overlay-video.bb` reads `input.mp4` and `frames/`, then writes `output.mp4` with a 6 Mbit/s video bitrate limit and the configured speed-up.

## Params

Supported optional workflow params:

```clojure
{:template :caption-clip-wipe ; or :caption-emoji-pop
 :product-name "Storrito"    ; optional, used by caption scripts
 :pi-thinking :high          ; optional, used by pi calls
 :speed-up 1.12}             ; optional, used by overlay-video.bb
```

Any subtitle-template params accepted by `tool-shortform-subtitles` can also live in the same map, for example:

```clojure
{:template :caption-emoji-pop
 :word-emoji {"schedule" "📅"
              "video" "🎬"}}
```

Every `transcribe.bb` run recreates `work/` and regenerates the raw transcript from `input.mp4`. `improve-transcript.bb` writes the corrected transcript to `work/transcript-improved.edn` and writes the composed subtitle params into `params_transcribed.edn`. `params.edn` is never modified. Every `render-subtitles.bb` run recreates `frames/`.

`overlay-video.bb` speeds up the final video and subtitle frames by `:speed-up`. The default is `1.12`, which makes slow speech a little tighter without adding an extra video encoding step before overlaying.

## Storrito team instructions

Storrito team members can copy the shared Storrito defaults into the project root with:

```bash
./storrito-team-config.bb
```

This copies the files from `storrito-config/` without overwriting existing local config files. The workflow itself never invokes this helper.

## License

MIT License. See [LICENSE](LICENSE).
