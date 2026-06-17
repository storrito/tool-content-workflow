#!/usr/bin/env bb

(ns overlay-video
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [content-workflow.common :refer :all]))

(def video-bitrate "6M")
(def video-maxrate "6M")
(def video-bufsize "12M")
(def default-speed-up 1.12)

(defn parse-speed-up
  [params]
  (let [value (or (:speed-up params) default-speed-up)
        speed-up (cond
                   (number? value) (double value)
                   (string? value) (Double/parseDouble value)
                   :else (die! (str "Invalid :speed-up value: " value)))]
    (when-not (pos? speed-up)
      (die! (str ":speed-up must be positive: " speed-up)))
    speed-up))

(defn overlay-filter
  [speed-up has-audio?]
  (str (format "[0:v]setpts=PTS/%.6f,fps=%d,scale=%d:%d,setsar=1[base];"
               speed-up fps output-width output-height)
       (format "[1:v]setpts=PTS/%.6f[sub];" speed-up)
       "[base][sub]overlay=0:0:format=auto:eof_action=pass[v]"
       (when has-audio?
         (format ";[0:a]atempo=%.6f[a]" speed-up))))

(defn render-output-with-rotation-metadata!
  []
  (let [speed-up (parse-speed-up (read-params))
        has-audio? (audio?)
        audio-args (when has-audio?
                     ["-map" "[a]"
                      "-c:a" "aac"
                      "-b:a" "160k"])]
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
                   "-filter_complex" (overlay-filter speed-up has-audio?)
                   "-map" "[v]"
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
