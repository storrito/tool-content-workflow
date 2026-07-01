(ns content-workflow.publish
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [content-workflow.bundle-social :as bundle]))

(def output-path "output.mp4")
(def tiktok-caption-path "work/tiktok-caption.txt")
(def youtube-title-path "work/youtube-shorts-title.txt")
(def youtube-caption-path "work/youtube-shorts-caption.txt")
(def pinterest-title-path "work/pinterest-title.txt")
(def pinterest-description-path "work/pinterest-description.txt")

(def default-platforms ["TIKTOK" "YOUTUBE"])

(defn now-iso
  []
  (str (java.time.Instant/now)))

(defn read-text
  [path]
  (when (fs/exists? path)
    (str/trim (slurp path))))

(defn require-file!
  [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "Missing required file: " path) {:path path}))))

(defn non-blank
  [value]
  (when-not (str/blank? (str value))
    (str/trim (str value))))

(defn split-csv
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)))

(defn normalize-platform
  [platform]
  (-> platform str str/trim str/upper-case (str/replace #"[- ]" "_")))

(defn generated-copy
  []
  {:tiktok-caption (read-text tiktok-caption-path)
   :youtube-title (read-text youtube-title-path)
   :youtube-description (read-text youtube-caption-path)
   :pinterest-title (read-text pinterest-title-path)
   :pinterest-description (read-text pinterest-description-path)})

(defn default-title
  [copy]
  (or (non-blank (:youtube-title copy))
      (non-blank (:pinterest-title copy))
      "Generated short video"))

(defn copy-text
  [copy key]
  (non-blank (get copy key)))

(defn merge-copy-overrides
  [base overrides]
  (merge base
         (into {}
               (for [[key value] overrides
                     :let [value (non-blank value)]
                     :when value]
                 [key value]))))

(defn platform-data
  [platform upload-id copy opts]
  (let [base-caption (or (copy-text copy :tiktok-caption)
                         (copy-text copy :youtube-description)
                         (copy-text copy :pinterest-description)
                         "")
        tiktok-caption (or (copy-text copy :tiktok-caption) base-caption)
        youtube-title (or (copy-text copy :youtube-title) (default-title copy))
        youtube-description (or (copy-text copy :youtube-description) base-caption)
        pinterest-title (or (copy-text copy :pinterest-title) (default-title copy))
        pinterest-description (or (copy-text copy :pinterest-description) base-caption)
        instagram-caption (or (copy-text copy :instagram-caption) base-caption)
        facebook-caption (or (copy-text copy :facebook-caption) base-caption)
        linkedin-title (or (copy-text copy :linkedin-title) youtube-title)
        linkedin-text (or (copy-text copy :linkedin-text) base-caption)
        twitter-text (or (copy-text copy :twitter-text) base-caption)
        threads-text (or (copy-text copy :threads-text) base-caption)
        bluesky-text (or (copy-text copy :bluesky-text) base-caption)
        mastodon-text (or (copy-text copy :mastodon-text) base-caption)
        upload-ids [upload-id]]
    (case platform
      "TIKTOK" {:type "VIDEO"
                :text tiktok-caption
                :uploadIds upload-ids
                :privacy (non-blank (:tiktok-privacy opts))}
      "YOUTUBE" {:type "SHORT"
                 :text youtube-title
                 :description youtube-description
                 :uploadIds upload-ids
                 :privacy (or (non-blank (:youtube-privacy opts)) "PRIVATE")
                 :madeForKids false}
      "PINTEREST" (let [board-name (or (non-blank (:pinterest-board opts))
                                       (non-blank (bundle/env "BUNDLE_SOCIAL_PINTEREST_BOARD")))]
                    (when-not board-name
                      (throw (ex-info "Pinterest publishing requires a board name. Set BUNDLE_SOCIAL_PINTEREST_BOARD or pass --pinterest-board."
                                      {:platform platform})))
                    {:text pinterest-title
                     :description pinterest-description
                     :boardName board-name
                     :uploadIds upload-ids})
      "INSTAGRAM" {:type "REEL"
                   :text instagram-caption
                   :uploadIds upload-ids
                   :shareToFeed true}
      "FACEBOOK" {:type "REEL"
                  :text facebook-caption
                  :uploadIds upload-ids}
      "LINKEDIN" {:text linkedin-text
                  :uploadIds upload-ids
                  :mediaTitle linkedin-title}
      "TWITTER" {:text twitter-text
                 :uploadIds upload-ids}
      "THREADS" {:text threads-text
                 :uploadIds upload-ids}
      "BLUESKY" {:text bluesky-text
                 :uploadIds upload-ids}
      "MASTODON" {:text mastodon-text
                  :uploadIds upload-ids}
      (throw (ex-info (str "Unsupported platform for generated video publishing: " platform)
                      {:platform platform})))))

(defn remove-nil-values
  [m]
  (into {} (remove (comp nil? val) m)))

(defn post-payload
  [{:keys [team-id platforms upload-id copy title post-date status reference-key]
    :or {status "SCHEDULED"}
    :as opts}]
  (let [platforms (->> (or (seq platforms) default-platforms)
                       (map normalize-platform)
                       distinct
                       vec)
        copy (or copy (generated-copy))]
    {:teamId (or team-id (bundle/team-id))
     :title (or (non-blank title) (default-title copy))
     :referenceKey (or (non-blank reference-key)
                       (str "content-workflow:" (System/currentTimeMillis)))
     :postDate (or (non-blank post-date) (now-iso))
     :status status
     :socialAccountTypes platforms
     :data (into {}
                 (for [platform platforms]
                   [(keyword platform)
                    (remove-nil-values (platform-data platform upload-id copy opts))]))}))

(defn publish-generated!
  [{:keys [video-path file-name team-id platforms]
    :or {video-path output-path
         file-name "output.mp4"}
    :as opts}]
  (bundle/require-config!)
  (require-file! video-path)
  (let [copy (merge-copy-overrides (generated-copy) (:copy opts))
        upload (bundle/upload-video! {:teamId (or team-id (bundle/team-id))
                                      :file-path video-path
                                      :file-name file-name
                                      :mime-type "video/mp4"})
        payload (post-payload (assoc opts
                                     :team-id (or team-id (bundle/team-id))
                                     :platforms platforms
                                     :upload-id (:id upload)
                                     :copy copy))
        post (bundle/create-post! payload)]
    {:upload upload
     :post post
     :payload payload}))

(defn usage
  []
  (str "Publish generated output.mp4 through bundle.social.\n\n"
       "Required env or .env values:\n"
       "  BUNDLE_SOCIAL_API_KEY=...\n"
       "  BUNDLE_SOCIAL_TEAM_ID=...\n\n"
       "Usage:\n"
       "  ./publish-to-bundle.bb --platforms TIKTOK,YOUTUBE\n\n"
       "Options:\n"
       "  --platforms CSV          Platforms to publish, e.g. TIKTOK,YOUTUBE,PINTEREST\n"
       "  --video PATH             Video path, default output.mp4\n"
       "  --title TEXT             Bundle post title override\n"
       "  --post-date ISO          ISO timestamp, default now\n"
       "  --reference-key TEXT     Bundle referenceKey override\n"
       "  --youtube-privacy VALUE  PRIVATE, UNLISTED, PUBLIC. Default PRIVATE\n"
       "  --tiktok-privacy VALUE   Optional TikTok privacy value\n"
       "  --pinterest-board NAME   Required when publishing to Pinterest unless env is set\n"
       "  --help                   Show this help\n"))

(defn parse-args
  [args]
  (loop [args args
         opts {}]
    (if-let [arg (first args)]
      (case arg
        ("-h" "--help") (assoc opts :help true)
        "--platforms" (recur (nnext args) (assoc opts :platforms (split-csv (second args))))
        "--video" (recur (nnext args) (assoc opts :video-path (second args)))
        "--title" (recur (nnext args) (assoc opts :title (second args)))
        "--post-date" (recur (nnext args) (assoc opts :post-date (second args)))
        "--reference-key" (recur (nnext args) (assoc opts :reference-key (second args)))
        "--youtube-privacy" (recur (nnext args) (assoc opts :youtube-privacy (second args)))
        "--tiktok-privacy" (recur (nnext args) (assoc opts :tiktok-privacy (second args)))
        "--pinterest-board" (recur (nnext args) (assoc opts :pinterest-board (second args)))
        (throw (ex-info (str "Unknown option: " arg) {:arg arg})))
      opts)))

(defn -main
  [& args]
  (let [opts (parse-args args)]
    (if (:help opts)
      (println (usage))
      (try
        (let [result (publish-generated! opts)]
          (println (json/generate-string result {:pretty true})))
        (catch Exception e
          (binding [*out* *err*]
            (println (.getMessage e))
            (when-let [data (ex-data e)]
              (println (json/generate-string data {:pretty true}))))
          (System/exit 1))))))
