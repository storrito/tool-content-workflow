(ns content-workflow.env
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def dotenv-paths [".env" ".env.local"])

(defn strip-inline-comment
  [value]
  (let [idx (loop [i 0]
              (cond
                (>= i (count value)) nil
                (and (= \# (.charAt value i))
                     (or (zero? i)
                         (Character/isWhitespace (.charAt value (dec i))))) i
                :else (recur (inc i))))]
    (str/trim (if idx (subs value 0 idx) value))))

(defn unquote-value
  [value]
  (let [value (str/trim value)]
    (cond
      (and (>= (count value) 2)
           (= \" (first value))
           (= \" (last value)))
      (-> (subs value 1 (dec (count value)))
          (str/replace "\\n" "\n")
          (str/replace "\\r" "\r")
          (str/replace "\\t" "\t")
          (str/replace "\\\"" "\"")
          (str/replace "\\\\" "\\"))

      (and (>= (count value) 2)
           (= \' (first value))
           (= \' (last value)))
      (subs value 1 (dec (count value)))

      :else
      (strip-inline-comment value))))

(defn parse-line
  [line]
  (let [line (str/trim line)]
    (when-not (or (str/blank? line) (str/starts-with? line "#"))
      (let [line (str/replace-first line #"^export\s+" "")
            [_ key value] (re-matches #"([^=]+)=(.*)" line)]
        (when (and key (re-matches #"[A-Za-z_][A-Za-z0-9_]*" (str/trim key)))
          [(str/trim key) (unquote-value value)])))))

(defn read-dotenv-file
  [path]
  (if (fs/exists? path)
    (->> (slurp path)
         str/split-lines
         (keep parse-line)
         (into {}))
    {}))

(defn read-dotenv
  []
  (apply merge (map read-dotenv-file dotenv-paths)))

(defonce dotenv-values
  (delay (read-dotenv)))

(defn env
  [k]
  (or (System/getenv k)
      (get @dotenv-values k)))
