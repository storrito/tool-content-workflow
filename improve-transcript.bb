#!/usr/bin/env bb

(ns improve-transcript
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-workflow.common :refer :all]))

(def improved-transcript-path (str (fs/path work-dir "transcript-improved.edn")))
(def improve-transcript-prompt-path (str (fs/path work-dir "improve-transcript-prompt.md")))

(defn read-transcript
  []
  (when-not (fs/exists? transcript-path)
    (die! (str "Missing transcript. Run ./transcribe.bb first: " transcript-path)))
  (let [transcript (edn/read-string (slurp transcript-path))]
    (when-not (map? transcript)
      (die! (str "Transcript EDN must be a map: " transcript-path)))
    transcript))

(defn read-improved-transcript
  []
  (when-not (fs/exists? improved-transcript-path)
    (die! (str "pi did not write " improved-transcript-path)))
  (let [transcript (edn/read-string (slurp improved-transcript-path))]
    (when-not (map? transcript)
      (die! (str "Improved transcript EDN must be a map: " improved-transcript-path)))
    (when-not (seq (:words transcript))
      (die! (str "Improved transcript EDN must contain :words: " improved-transcript-path)))
    transcript))

(defn params-with-transcript
  [params transcript]
  (-> params
      (dissoc :fit :cpu :cpu? :tools-dir :speech-to-text-dir :shortform-subtitles-dir)
      (merge transcript)
      (assoc :template (keyword (validate-template (:template params)))
             :duration (video-duration-seconds))))

(defn word-end
  [word]
  (+ (:t word) (:d word)))

(defn covered-original-words
  [original-words improved-word]
  (let [start (:t improved-word)
        end (word-end improved-word)]
    (->> original-words
         (filter #(and (>= (:t %) start)
                       (<= (word-end %) end)))
         vec)))

(defn print-transcript-change-summary!
  [transcript improved-transcript]
  (let [original-words (:words transcript)
        original-by-start (into {} (map (juxt :t identity) original-words))
        improved-words (:words improved-transcript)
        merges (->> improved-words
                    (keep (fn [word]
                            (let [covered (covered-original-words original-words word)]
                              (when (> (count covered) 1)
                                {:from (str/join " " (map :text covered))
                                 :to (:text word)
                                 :t (:t word)}))))
                    vec)
        corrections (->> improved-words
                         (keep (fn [word]
                                 (let [original (get original-by-start (:t word))]
                                   (when (and original
                                              (= (:d original) (:d word))
                                              (not= (:text original) (:text word)))
                                     {:from (:text original)
                                      :to (:text word)
                                      :t (:t word)}))))
                         vec)
        highlights (->> improved-words
                        (filter :highlight?)
                        (map #(select-keys % [:t :text]))
                        vec)]
    (println "Transcript improvement summary:")
    (if (and (empty? merges) (empty? corrections) (empty? highlights))
      (println "- No word-level corrections, merges, or highlights detected.")
      (do
        (doseq [{:keys [from to t]} merges]
          (println "- merged" (pr-str from) "->" (pr-str to) "at" t))
        (doseq [{:keys [from to t]} corrections]
          (println "- corrected" (pr-str from) "->" (pr-str to) "at" t))
        (doseq [{:keys [text t]} highlights]
          (println "- highlighted" (pr-str text) "at" t))))))

(defn prompt
  [params transcript-edn]
  (str "You are improving a speech-to-text transcript for a short-form video workflow.\n\n"
       "Write the improved transcript as valid Clojure EDN to `" improved-transcript-path "`.\n\n"
       "Requirements:\n"
       "- Write only `" improved-transcript-path "`; do not modify other files.\n"
       "- The file must contain one EDN map with top-level :segments and :words vectors.\n"
       "- Preserve :t and :d timing values exactly unless you merge adjacent word entries. They are milliseconds and must stay numeric.\n"
       "- You may merge adjacent :words entries when they are obviously parts of one mis-transcribed word, e.g. `para` + `keyed` should become `Parakeet`.\n"
       "- When merging word entries, use the first word's :t and set :d to cover the full original time span from the first word start to the last merged word end.\n"
       "- Minimize the textual diff between `work/transcript.edn` and `work/transcript-improved.edn`.\n"
       "- Preserve the original EDN formatting as much as possible; do not rewrite the file in a compact single-line format.\n"
       "- Correct obvious transcription mistakes in :text.\n"
       "- Preserve punctuation/capitalization in a readable way.\n"
       "- If a :product-name is present in params, make sure the product name is spelled exactly like that in all transcript text.\n"
       "- You may add :highlight? true to a small number of important word maps for subtitle templates that support highlighted words.\n"
       "- Do not remove words or segments unless they are obvious transcription artifacts.\n"
       "- Do not invent content that is not supported by the transcript.\n\n"
       "Params:\n\n"
       (pr-str params)
       "\n\nTranscript EDN:\n\n"
       transcript-edn
       "\n"))

(defn -main
  [& _]
  (let [params (read-params)
        transcript (read-transcript)
        transcript-edn (slurp transcript-path)
        improve-prompt (prompt params transcript-edn)]
    (fs/create-dirs work-dir)
    (spit improve-transcript-prompt-path improve-prompt)
    (when (fs/exists? improved-transcript-path)
      (fs/delete improved-transcript-path))
    (p/shell {:inherit true}
             "pi" "--print" "--no-session" "--thinking" (pi-thinking params)
             (str "@" improve-transcript-prompt-path))
    (let [improved-transcript (read-improved-transcript)
          transcribed-params (params-with-transcript params improved-transcript)]
      (print-transcript-change-summary! transcript improved-transcript)
      (write-edn! improved-transcript-path improved-transcript)
      (write-edn! transcribed-params-path transcribed-params)
      (println "Wrote" improved-transcript-path)
      (println "Wrote" transcribed-params-path))))

(apply -main *command-line-args*)
