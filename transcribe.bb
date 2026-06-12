#!/usr/bin/env bb

(ns transcribe
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [content-workflow.common :refer :all]))

(defn transcribe!
  []
  (when (fs/exists? work-dir)
    (fs/delete-tree work-dir))
  (fs/create-dirs work-dir)
  (let [transcribe (speech-to-text-path)]
    (p/shell {:inherit true} transcribe "--output" transcript-path input-path))
  (or (read-transcript)
      (die! (str "Transcription did not write: " transcript-path))))

(defn params-with-transcript
  [params transcript]
  (-> params
      (dissoc :fit :cpu :cpu? :tools-dir :speech-to-text-dir :shortform-subtitles-dir)
      (merge transcript)
      (assoc :template (keyword (validate-template (:template params)))
             :duration (video-duration-seconds))))

(defn -main
  [& _]
  (when-not (fs/exists? input-path)
    (die! (str "Expected input video by convention: " input-path)))
  (let [params (read-params)
        transcript (transcribe!)
        transcribed-params (params-with-transcript params transcript)]
    (write-edn! transcribed-params-path transcribed-params)
    (println "Wrote" transcript-path)
    (println "Wrote" transcribed-params-path)))

(apply -main *command-line-args*)
