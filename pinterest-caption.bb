#!/usr/bin/env bb

(ns pinterest-caption
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-workflow.common :refer :all]))

(def pinterest-description-path (str (fs/path work-dir "pinterest-description.txt")))
(def pinterest-title-path (str (fs/path work-dir "pinterest-title.txt")))
(def pinterest-caption-prompt-path (str (fs/path work-dir "pinterest-caption-prompt.md")))

(defn read-transcribed-params
  []
  (when-not (fs/exists? transcribed-params-path)
    (die! (str "Missing transcribed params. Run ./improve-transcript.bb first: " transcribed-params-path)))
  (let [params (edn/read-string (slurp transcribed-params-path))]
    (when-not (map? params)
      (die! (str "Transcribed params EDN must be a map: " transcribed-params-path)))
    params))

(defn transcript-text
  [transcript]
  (->> (or (:segments transcript) (:words transcript))
       (map :text)
       (remove str/blank?)
       (str/join " ")))

(defn prompt
  [params]
  (str "You are writing Pinterest Pin metadata for the video file `output.mp4`.\n\n"
       "Use the transcript as context. Write the final Pinterest description/caption to `" pinterest-description-path "` "
       "and the final Pinterest title to `" pinterest-title-path "`.\n\n"
       "Requirements:\n"
       "- Write only `" pinterest-description-path "` and `" pinterest-title-path "`; do not modify other files.\n"
       "- Make both the title and description suitable for Pinterest.\n"
       "- Write a clear, searchable Pin title for `" pinterest-title-path "`. Keep it under 100 characters.\n"
       "- Write a useful Pin description/caption for `" pinterest-description-path "`. Keep it under 800 characters.\n"
       "- Use natural Pinterest search keywords based on the transcript.\n"
       "- Keep the description to 2 to 4 short sentences.\n"
       "- Do not include hashtags. Pinterest accepts hashtags in descriptions, but natural keyword phrases are preferred because hashtag searches are treated like regular searches without the #.\n"
       (when (:product-name params)
         (str "- Mention the product name '" (:product-name params) "' naturally.\n"))
       "- Keep it natural and useful, not clickbait.\n"
       "- Do not invent claims that are not supported by the transcript.\n"
       "- Do not mention that a transcript was provided.\n\n"
       "Transcript:\n\n"
       (transcript-text params)
       "\n"))

(defn -main
  [& _]
  (let [params (read-transcribed-params)
        caption-prompt (prompt params)]
    (fs/create-dirs work-dir)
    (spit pinterest-caption-prompt-path caption-prompt)
    (when (fs/exists? pinterest-description-path)
      (fs/delete pinterest-description-path))
    (when (fs/exists? pinterest-title-path)
      (fs/delete pinterest-title-path))
    (println "Asking pi to write Pinterest description and title")
    (flush)
    (p/shell {:out :inherit :err :inherit :in ""}
             "pi" "--print" "--no-session" "--thinking" (pi-thinking params)
             (str "@" pinterest-caption-prompt-path))
    (when-not (fs/exists? pinterest-description-path)
      (die! (str "pi did not write " pinterest-description-path)))
    (when-not (fs/exists? pinterest-title-path)
      (die! (str "pi did not write " pinterest-title-path)))
    (println "Wrote" pinterest-description-path)
    (println "Wrote" pinterest-title-path)))

(apply -main *command-line-args*)
