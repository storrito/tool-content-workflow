#!/usr/bin/env bb

(require '[babashka.process :as p])

(def output-path "output.mp4")

(defn -main
  [& _]
  (println "Running transcribe.bb")
  (p/shell {:inherit true} "./transcribe.bb")
  (println "Running improve-transcript.bb")
  (p/shell {:inherit true} "./improve-transcript.bb")
  (println "Running tiktok-caption.bb")
  (p/shell {:inherit true} "./tiktok-caption.bb")
  (println "Running youtube-shorts-caption.bb")
  (p/shell {:inherit true} "./youtube-shorts-caption.bb")
  (println "Running render-subtitles.bb")
  (p/shell {:inherit true} "./render-subtitles.bb")
  (println "Running overlay-video.bb")
  (p/shell {:inherit true} "./overlay-video.bb")
  (println "Wrote" output-path))

(apply -main *command-line-args*)
