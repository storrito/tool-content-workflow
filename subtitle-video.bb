#!/usr/bin/env bb

(ns subtitle-video
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

(def fps 30)
(def output-width 1080)
(def output-height 1920)
(def speech-to-text-repo "https://github.com/storrito/tool-speech-to-text.git")
(def shortform-subtitles-repo "https://github.com/storrito/tool-shortform-subtitles.git")
(def default-ffmpeg-image "jrottenberg/ffmpeg:7.1-ubuntu")
(def params-path "params.edn")
(def input-path "input.mp4")
(def transcribed-params-path "params_transcribed.edn")
(def frames-dir "frames")
(def output-path "output.mp4")
(def intermediate-output-path ".output_with_rotation_metadata.mp4")
(def frame-pattern "frames/frame_%06d.png")
(def first-frame-path "frames/frame_000001.png")

(def usage
  (str "Usage:\n"
       "  ./subtitle-video.bb\n\n"
       "Conventions:\n"
       "  params file: params.edn in the project root, optional and never modified\n"
       "  input video: input.mp4 in the project root\n"
       "  transcribed: params_transcribed.edn in the project root\n"
       "  output:      output.mp4 in the project root\n\n"
       "params.edn is optional. When omitted, the default params are:\n"
       "  {:template :caption-clip-wipe}\n\n"
       "Optional params keys:\n"
       "  :template     :caption-clip-wipe or :caption-emoji-pop\n"))

(defn die!
  [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

(defn sh-result
  [cmd]
  (apply p/shell {:out :string :err :string :continue true} cmd))

(defn sh-out
  [cmd]
  (let [{:keys [exit out err]} (sh-result cmd)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:command cmd :exit exit :stderr err})))
    (str/trim out)))

(defn sh!
  [cmd]
  (println "+" (str/join " " cmd))
  (let [{:keys [exit]} (apply p/shell {:inherit true :continue true} cmd)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed" {:command cmd :exit exit})))))

(defn executable-exists?
  [cmd]
  (zero? (:exit (sh-result ["bash" "-lc" "command -v \"$1\" >/dev/null 2>&1" "bash" cmd]))))

(defn ensure-command!
  [cmd hint]
  (when-not (executable-exists? cmd)
    (die! (str "Missing command: " cmd ". " hint))))

(defn absolute-path
  [path]
  (-> path fs/path fs/absolutize fs/normalize str))

(defn value-name
  [value]
  (cond
    (nil? value) nil
    (or (keyword? value) (symbol? value)) (name value)
    (string? value) value
    :else (str value)))

(defn validate-template
  [template]
  (let [template (or (value-name template) "caption-clip-wipe")]
    (when-not (re-matches #"[a-z0-9][a-z0-9-]*" template)
      (die! (str "Invalid :template value: " template)))
    template))

(defn parse-cli
  [argv]
  (try
    (let [{:keys [opts args]} (cli/parse-args argv
                                             {:spec {:help {:alias :h :coerce :boolean}}
                                              :restrict true
                                              :no-keyword-opts true})]
      (when (:help opts)
        (println usage)
        (System/exit 0))
      (when (seq args)
        (die! (str "This workflow is convention-based and does not accept arguments.\n\n" usage))))
    (catch Exception e
      (die! (str (.getMessage e) "\n\n" usage)))))

(defn read-params
  []
  (if (fs/exists? params-path)
    (let [params (edn/read-string (slurp params-path))]
      (when-not (map? params)
        (die! (str "Params EDN must be a map: " params-path)))
      params)
    {}))

(defn write-edn!
  [path value]
  (fs/create-dirs (str (fs/parent (fs/path path))))
  (spit path (with-out-str (pprint/pprint value))))

(defn clone-if-missing!
  [path repo-url]
  (when-not (fs/exists? path)
    (ensure-command! "git" "Install Git first.")
    (fs/create-dirs (str (fs/parent (fs/path path))))
    (sh! ["git" "clone" "--depth" "1" repo-url path])))

(defn tool-paths
  []
  (let [tools-dir (absolute-path "tools")
        speech-dir (str (fs/path tools-dir "tool-speech-to-text"))
        subtitles-dir (str (fs/path tools-dir "tool-shortform-subtitles"))]
    (clone-if-missing! speech-dir speech-to-text-repo)
    (clone-if-missing! subtitles-dir shortform-subtitles-repo)
    (let [transcribe (str (fs/path speech-dir "transcribe.bb"))
          render (str (fs/path subtitles-dir "render"))]
      (when-not (fs/exists? transcribe)
        (die! (str "Missing transcribe script: " transcribe)))
      (when-not (fs/exists? render)
        (die! (str "Missing render script: " render)))
      {:transcribe transcribe
       :render render})))

(defn uid []
  (sh-out ["id" "-u"]))

(defn gid []
  (sh-out ["id" "-g"]))

(defn docker-media-cmd
  [executable args]
  (into ["docker" "run" "--rm"
         "--user" (str (uid) ":" (gid))
         "-v" (str (absolute-path ".") ":/work")
         "-w" "/work"
         "--entrypoint" "/bin/sh"
         default-ffmpeg-image
         "-c" (str "exec " executable " \"$@\"")
         executable]
        args))

(defn video-duration-seconds
  []
  (let [raw (sh-out (docker-media-cmd "ffprobe"
                                      ["-v" "error"
                                       "-show_entries" "format=duration"
                                       "-of" "default=noprint_wrappers=1:nokey=1"
                                       input-path]))]
    (try
      (Double/parseDouble raw)
      (catch Exception _
        (die! (str "Could not read video duration with ffprobe: " raw))))))

(defn audio?
  []
  (seq (sh-out (docker-media-cmd "ffprobe"
                                 ["-v" "error"
                                  "-select_streams" "a"
                                  "-show_entries" "stream=index"
                                  "-of" "csv=p=0"
                                  input-path]))))

(defn read-transcript
  [transcript-path]
  (when (fs/exists? transcript-path)
    (let [transcript (edn/read-string (slurp transcript-path))]
      (when-not (map? transcript)
        (die! (str "Transcript EDN must be a map: " transcript-path)))
      transcript)))

(defn transcribe!
  [tools]
  (ensure-command! "docker" "The speech-to-text tool runs through Docker.")
  (let [tmp-dir (fs/create-temp-dir {:prefix "subtitle-video-"})
        transcript-path (str (fs/path tmp-dir "transcript.edn"))]
    (try
      (sh! [(:transcribe tools) "--output" transcript-path input-path])
      (or (read-transcript transcript-path)
          (die! (str "Transcription did not write: " transcript-path)))
      (finally
        (fs/delete-tree tmp-dir)))))

(defn params-with-transcript
  [tools params]
  (let [duration (video-duration-seconds)
        transcript (transcribe! tools)
        merged (-> params
                   (dissoc :fit :cpu :cpu? :tools-dir :speech-to-text-dir :shortform-subtitles-dir)
                   (merge transcript)
                   (assoc :template (keyword (validate-template (:template params)))
                          :duration duration))]
    (write-edn! transcribed-params-path merged)
    (println "Wrote" transcribed-params-path)
    merged))

(defn render!
  [tools params-path]
  (ensure-command! "docker" "The subtitle renderer runs through Docker.")
  (sh! [(:render tools) params-path]))

(defn overlay-filter
  []
  (format "[0:v]fps=%d,scale=%d:%d,setsar=1[base];[base][1:v]overlay=0:0:format=auto:eof_action=pass[v]"
          fps output-width output-height))

(defn render-output-with-rotation-metadata!
  []
  (let [audio-args (when (audio?)
                     ["-c:a" "aac" "-b:a" "160k"])]
    (sh! (docker-media-cmd
          "ffmpeg"
          (into ["-hide_banner" "-y"
                 "-autorotate"
                 "-i" input-path
                 "-framerate" (str fps)
                 "-start_number" "1"
                 "-i" frame-pattern
                 "-filter_complex" (overlay-filter)
                 "-map" "[v]"
                 "-map" "0:a?"
                 "-c:v" "libx264"
                 "-pix_fmt" "yuv420p"
                 "-crf" "18"
                 "-preset" "medium"]
                (concat audio-args
                        ["-movflags" "+faststart"
                         intermediate-output-path]))))))

(defn strip-rotation-metadata!
  []
  (sh! (docker-media-cmd
        "ffmpeg"
        ["-hide_banner" "-y"
         "-display_rotation" "0"
         "-i" intermediate-output-path
         "-map" "0"
         "-c" "copy"
         "-map_metadata" "-1"
         "-movflags" "+faststart"
         output-path])))

(defn overlay!
  []
  (when (fs/exists? intermediate-output-path)
    (fs/delete intermediate-output-path))
  (try
    (render-output-with-rotation-metadata!)
    (strip-rotation-metadata!)
    (finally
      (when (fs/exists? intermediate-output-path)
        (fs/delete intermediate-output-path)))))

(defn workflow!
  []
  (ensure-command! "docker" "This workflow runs FFmpeg and the two subtitle tools through Docker.")
  (let [params (read-params)]
    (when-not (fs/exists? input-path)
      (die! (str "Expected input video by convention: " input-path)))
    (let [tools (tool-paths)
          _ (params-with-transcript tools params)]
      (println "Input:" input-path)
      (println "Params:" params-path)
      (println "Transcribed params:" transcribed-params-path)
      (println "Frames:" frames-dir)
      (println "Output:" output-path)
      (when (fs/exists? frames-dir)
        (fs/delete-tree frames-dir))
      (render! tools transcribed-params-path)
      (when-not (fs/exists? first-frame-path)
        (die! (str "Missing first subtitle frame: " first-frame-path)))
      (overlay!)
      (println "Wrote" output-path))))

(parse-cli *command-line-args*)
(workflow!)
