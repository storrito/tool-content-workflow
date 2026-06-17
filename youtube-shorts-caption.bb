#!/usr/bin/env bb

(ns youtube-shorts-caption
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-workflow.common :refer :all]))

(def youtube-shorts-base-caption-path "youtube-shorts-base-caption.txt")
(def youtube-shorts-caption-path (str (fs/path work-dir "youtube-shorts-caption.txt")))
(def youtube-shorts-title-path (str (fs/path work-dir "youtube-shorts-title.txt")))
(def youtube-shorts-caption-prompt-path (str (fs/path work-dir "youtube-shorts-caption-prompt.md")))

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

(defn read-base-caption
  []
  (when-not (fs/exists? youtube-shorts-base-caption-path)
    (die! (str "Missing YouTube Shorts base caption: " youtube-shorts-base-caption-path)))
  (str/trim (slurp youtube-shorts-base-caption-path)))

(defn prompt
  [params base-caption]
  (str "You are writing YouTube Shorts metadata for the video file `output.mp4`.\n\n"
       "Use the transcript as context and use the base caption as a starting point/style guide. "
       "Write the final caption to `" youtube-shorts-caption-path "` and the final title to `" youtube-shorts-title-path "`.\n\n"
       "Requirements:\n"
       "- Write only `" youtube-shorts-caption-path "` and `" youtube-shorts-title-path "`; do not modify other files.\n"
       "- Make both the title and caption suitable for YouTube Shorts.\n"
       "- Write a clear, searchable title for `" youtube-shorts-title-path "`.\n"
       "- Keep the title concise and avoid clickbait.\n"
       "- YouTube Shorts allows a longer caption than TikTok, so write a useful caption with 3 to 6 short sentences plus relevant hashtags.\n"
       (when (:product-name params)
         (str "- Mention the product name '" (:product-name params) "' naturally.\n"))
       "- Preserve useful information from the base caption, but adapt it to the current video's transcript.\n"
       "- Keep the first sentence hook-like because it appears first in YouTube.\n"
       "- Do not invent claims that are not supported by the transcript or base caption.\n"
       "- Do not mention that a transcript or base caption was provided.\n\n"
       "Base caption:\n\n"
       base-caption
       "\n\nTranscript:\n\n"
       (transcript-text params)
       "\n"))

(defn -main
  [& _]
  (let [params (read-transcribed-params)
        base-caption (read-base-caption)
        caption-prompt (prompt params base-caption)]
    (fs/create-dirs work-dir)
    (spit youtube-shorts-caption-prompt-path caption-prompt)
    (when (fs/exists? youtube-shorts-caption-path)
      (fs/delete youtube-shorts-caption-path))
    (when (fs/exists? youtube-shorts-title-path)
      (fs/delete youtube-shorts-title-path))
    (p/shell {:inherit true}
             "pi" "--print" "--no-session" "--thinking" (pi-thinking params)
             (str "@" youtube-shorts-caption-prompt-path))
    (when-not (fs/exists? youtube-shorts-caption-path)
      (die! (str "pi did not write " youtube-shorts-caption-path)))
    (when-not (fs/exists? youtube-shorts-title-path)
      (die! (str "pi did not write " youtube-shorts-title-path)))
    (println "Wrote" youtube-shorts-caption-path)
    (println "Wrote" youtube-shorts-title-path)))

(apply -main *command-line-args*)
