#!/usr/bin/env bb

(require '[babashka.process :as p])

(def output-path "output.mp4")

(defn -main
  [& _]
  (p/shell {:inherit true} "./transcribe.bb")
  (p/shell {:inherit true} "./render-subtitles.bb")
  (p/shell {:inherit true} "./overlay-video.bb")
  (println "Wrote" output-path))

(apply -main *command-line-args*)
