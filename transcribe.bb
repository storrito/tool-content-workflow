#!/usr/bin/env bb

(ns transcribe
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [content-workflow.common :refer :all]))

(defn read-transcript
  []
  (when (fs/exists? transcript-path)
    (let [transcript (edn/read-string (slurp transcript-path))]
      (when-not (map? transcript)
        (die! (str "Transcript EDN must be a map: " transcript-path)))
      transcript)))

(defn transcribe!
  []
  (when (fs/exists? work-dir)
    (fs/delete-tree work-dir))
  (fs/create-dirs work-dir)
  (let [transcribe (speech-to-text-path)]
    (p/shell {:inherit true} transcribe "--output" transcript-path input-path))
  (or (read-transcript)
      (die! (str "Transcription did not write: " transcript-path))))

(defn -main
  [& _]
  (when-not (fs/exists? input-path)
    (die! (str "Expected input video by convention: " input-path)))
  (transcribe!)
  (println "Wrote" transcript-path))

(apply -main *command-line-args*)
