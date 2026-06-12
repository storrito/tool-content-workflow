#!/usr/bin/env bb

(ns overlay-video
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [content-workflow.common :refer :all]))

(def video-bitrate "6M")
(def video-maxrate "6M")
(def video-bufsize "12M")

(defn render-output-with-rotation-metadata!
  []
  (let [audio-args (when (audio?)
                     ["-c:a" "aac" "-b:a" "160k"])]
    (apply p/shell
           {:inherit true}
           (docker-media-cmd
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
                   "-b:v" video-bitrate
                   "-maxrate" video-maxrate
                   "-bufsize" video-bufsize
                   "-preset" "medium"]
                  (concat audio-args
                          ["-movflags" "+faststart"
                           intermediate-output-path]))))))

(defn strip-rotation-metadata!
  []
  (apply p/shell
         {:inherit true}
         (docker-media-cmd
          "ffmpeg"
          ["-hide_banner" "-y"
           "-display_rotation" "0"
           "-i" intermediate-output-path
           "-map" "0"
           "-c" "copy"
           "-map_metadata" "-1"
           "-movflags" "+faststart"
           output-path])))

(defn -main
  [& _]
  (when-not (fs/exists? input-path)
    (die! (str "Expected input video by convention: " input-path)))
  (when-not (fs/exists? first-frame-path)
    (die! (str "Missing first subtitle frame. Run ./render-subtitles.bb first: " first-frame-path)))
  (fs/create-dirs work-dir)
  (render-output-with-rotation-metadata!)
  (strip-rotation-metadata!)
  (println "Wrote" output-path))

(apply -main *command-line-args*)
