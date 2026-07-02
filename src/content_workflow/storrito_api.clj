(ns content-workflow.storrito-api
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [content-workflow.env :as dotenv]
            [content-workflow.publish :as publish]))

(def default-local-api-base "http://7414818a-0c58-4865-aa8b-3b43cc96aa93.localhost:8080/api/v1")

(defn env
  [k]
  (dotenv/env k))

(defn non-blank
  [value]
  (when-not (str/blank? (str value))
    (str/trim (str value))))

(defn api-base
  []
  (str/replace (or (non-blank (env "STORRITO_API_BASE"))
                   default-local-api-base)
               #"/+$" ""))

(defn api-token
  []
  (env "STORRITO_API_TOKEN"))

(defn default-instagram-username
  []
  (env "STORRITO_INSTAGRAM_USERNAME"))

(defn missing-config
  []
  (cond-> []
    (str/blank? (api-base)) (conj "STORRITO_API_BASE")
    (str/blank? (api-token)) (conj "STORRITO_API_TOKEN")))

(defn require-config!
  []
  (when-let [missing (seq (missing-config))]
    (throw (ex-info (str "Missing Storrito API config: " (str/join ", " missing))
                    {:missing missing}))))

(defn parse-json
  [body]
  (when-not (str/blank? (str body))
    (json/parse-string body true)))

(defn api-request!
  [procedure payload]
  (require-config!)
  (let [response (http/post (str (api-base) "/" procedure)
                            {:headers {"authorization" (str "Bearer " (api-token))
                                       "content-type" "application/json"
                                       "accept" "application/json"}
                             :body (json/generate-string payload)
                             :throw false})
        status (:status response)
        parsed (parse-json (:body response))]
    (if (<= 200 status 299)
      parsed
      (throw (ex-info (str "Storrito API request failed: " status " "
                           (or (:errorMessage parsed) (:body response)))
                      {:status status
                       :procedure procedure
                       :response parsed
                       :body (:body response)})))))

(defn list-instagram-users!
  []
  (:instagramUsers (api-request! "list-instagram-users" {})))

(defn status-instagram-story!
  [story-post-uuid]
  (api-request! "status-instagram-story" {:storyPostUuid story-post-uuid}))

(defn html-escape
  [value]
  (str/escape (str value)
              {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&#39;"}))

(defn story-html
  [{:keys [video-url link-url link-text]}]
  (str "<insta-story src=\"" (html-escape video-url) "\">"
       (when-let [link-url (non-blank link-url)]
         (str "<insta-link url=\"" (html-escape link-url) "\""
              " text=\"" (html-escape (or (non-blank link-text) "Learn more")) "\""
              " style=\"position:absolute;left:270px;top:1450px\"></insta-link>"))
       "</insta-story>"))

(defn schedule-instagram-story!
  [{:keys [video-url instagram-username story-post-uuid link-url link-text date]}]
  (let [story-post-uuid (or (non-blank story-post-uuid)
                            (str (java.util.UUID/randomUUID)))
        instagram-username (or (non-blank instagram-username)
                               (non-blank (default-instagram-username)))]
    (when-not instagram-username
      (throw (ex-info "Missing Instagram username. Set STORRITO_INSTAGRAM_USERNAME or choose one in the UI."
                      {:missing ["STORRITO_INSTAGRAM_USERNAME"]})))
    (when-not (non-blank video-url)
      (throw (ex-info "Missing video URL for Storrito API story." {})))
    (api-request! "schedule-instagram-story"
                  (cond-> {:html (story-html {:video-url video-url
                                              :link-url link-url
                                              :link-text link-text})
                           :instagramUsername instagram-username
                           :storyPostUuid story-post-uuid}
                    (non-blank date) (assoc :date date)))))

(defn publish-output-story!
  [{:keys [video-url] :as opts}]
  (publish/require-file! publish/output-path)
  (schedule-instagram-story! (assoc opts :video-url video-url)))
