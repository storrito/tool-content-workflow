#!/usr/bin/env bb

(ns tiktok-caption
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-workflow.common :refer :all]))

(def tiktok-caption-path "tiktok-caption.txt")
(def tiktok-caption-prompt-path (str (fs/path work-dir "tiktok-caption-prompt.md")))

(defn read-transcribed-params
  []
  (when-not (fs/exists? transcribed-params-path)
    (die! (str "Missing transcribed params. Run ./transcribe.bb first: " transcribed-params-path)))
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
  (str "You are writing a TikTok caption for the video file `output.mp4`.\n\n"
       "Use the transcript as context. Write the final caption to `" tiktok-caption-path "`.\n\n"
       "Requirements:\n"
       "- Write only `" tiktok-caption-path "`; do not modify other files.\n"
       "- Make it suitable for TikTok.\n"
       "- Write 2 to 4 short sentences plus relevant hashtags.\n"
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
    (spit tiktok-caption-prompt-path caption-prompt)
    (when (fs/exists? tiktok-caption-path)
      (fs/delete tiktok-caption-path))
    (p/shell {:inherit true} "pi" "--print" "--no-session" (str "@" tiktok-caption-prompt-path))
    (when-not (fs/exists? tiktok-caption-path)
      (die! (str "pi did not write " tiktok-caption-path)))
    (println "Wrote" tiktok-caption-path)))

(apply -main *command-line-args*)
