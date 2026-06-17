(ns content-workflow.common
  (:require [babashka.fs :as fs]
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
(def work-dir "work")
(def transcript-path (str (fs/path work-dir "transcript.edn")))
(def frames-dir "frames")
(def output-path "output.mp4")
(def intermediate-output-path (str (fs/path work-dir "output_with_rotation_metadata.mp4")))
(def frame-pattern "frames/frame_%06d.png")
(def first-frame-path "frames/frame_000001.png")

(defn die!
  [message]
  (binding [*out* *err*]
    (println message))
  (System/exit 1))

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
  (when-let [parent (fs/parent (fs/path path))]
    (fs/create-dirs parent))
  (spit path (with-out-str (pprint/pprint value))))

(defn clone-if-missing!
  [path repo-url]
  (when-not (fs/exists? path)
    (fs/create-dirs (str (fs/parent (fs/path path))))
    (p/shell {:inherit true} "git" "clone" "--depth" "1" repo-url path)))

(defn speech-to-text-path
  []
  (let [dir (str (fs/path (absolute-path "tools") "tool-speech-to-text"))
        script (str (fs/path dir "transcribe.bb"))]
    (clone-if-missing! dir speech-to-text-repo)
    (when-not (fs/exists? script)
      (die! (str "Missing transcribe script: " script)))
    script))

(defn shortform-subtitles-path
  []
  (let [dir (str (fs/path (absolute-path "tools") "tool-shortform-subtitles"))
        script (str (fs/path dir "render"))]
    (clone-if-missing! dir shortform-subtitles-repo)
    (when-not (fs/exists? script)
      (die! (str "Missing render script: " script)))
    script))

(defn uid []
  (-> (p/shell {:out :string :err :string} "id" "-u")
      :out
      str/trim))

(defn gid []
  (-> (p/shell {:out :string :err :string} "id" "-g")
      :out
      str/trim))

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
  (let [raw (-> (apply p/shell
                       {:out :string :err :string}
                       (docker-media-cmd "ffprobe"
                                         ["-v" "error"
                                          "-show_entries" "format=duration"
                                          "-of" "default=noprint_wrappers=1:nokey=1"
                                          input-path]))
                :out
                str/trim)]
    (try
      (Double/parseDouble raw)
      (catch Exception _
        (die! (str "Could not read video duration with ffprobe: " raw))))))

(defn audio?
  []
  (seq (-> (apply p/shell
                  {:out :string :err :string}
                  (docker-media-cmd "ffprobe"
                                    ["-v" "error"
                                     "-select_streams" "a"
                                     "-show_entries" "stream=index"
                                     "-of" "csv=p=0"
                                     input-path]))
           :out
           str/trim)))
