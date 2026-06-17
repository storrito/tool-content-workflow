#!/usr/bin/env bb

(ns render-subtitles
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [content-workflow.common :refer :all]))

(defn -main
  [& _]
  (when-not (fs/exists? transcribed-params-path)
    (die! (str "Missing transcribed params. Run ./improve-transcript.bb first: " transcribed-params-path)))
  (when (fs/exists? frames-dir)
    (fs/delete-tree frames-dir))
  (let [render (shortform-subtitles-path)]
    (p/shell {:inherit true} render transcribed-params-path))
  (when-not (fs/exists? first-frame-path)
    (die! (str "Missing first subtitle frame: " first-frame-path)))
  (println "Wrote" frames-dir))

(apply -main *command-line-args*)
