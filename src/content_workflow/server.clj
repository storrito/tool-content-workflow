(ns content-workflow.server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [content-workflow.bundle-social :as bundle]
            [content-workflow.env :as dotenv]
            [content-workflow.publish :as publish]
            [content-workflow.storrito-api :as storrito-api]
            [hiccup.core :as h]
            [org.httpkit.server :as http]))

(def input-path "input.mp4")
(def params-path "params.edn")
(def transcribed-params-path "params_transcribed.edn")
(def youtube-base-caption-path "youtube-shorts-base-caption.txt")
(def output-path "output.mp4")
(def work-dir "work")
(def frames-dir "frames")
(def server-log-path "server-workflow.log")
(def max-request-body (* 1024 1024 1024))
(defonce public-media-token
  (str (java.util.UUID/randomUUID)))

(declare public-output-url public-media-request?)

(defonce state
  (atom {:status :idle}))

(def generated-paths
  [input-path
   params-path
   transcribed-params-path
   youtube-base-caption-path
   output-path
   work-dir
   frames-dir])

(def downloads
  {"/download/output.mp4" {:path output-path
                            :content-type "video/mp4"
                            :filename "output.mp4"}
   "/download/tiktok-caption.txt" {:path "work/tiktok-caption.txt"
                                    :content-type "text/plain; charset=utf-8"
                                    :filename "tiktok-caption.txt"}
   "/download/youtube-shorts-caption.txt" {:path "work/youtube-shorts-caption.txt"
                                            :content-type "text/plain; charset=utf-8"
                                            :filename "youtube-shorts-caption.txt"}
   "/download/youtube-shorts-title.txt" {:path "work/youtube-shorts-title.txt"
                                          :content-type "text/plain; charset=utf-8"
                                          :filename "youtube-shorts-title.txt"}
   "/download/pinterest-description.txt" {:path "work/pinterest-description.txt"
                                           :content-type "text/plain; charset=utf-8"
                                           :filename "pinterest-description.txt"}
   "/download/pinterest-title.txt" {:path "work/pinterest-title.txt"
                                     :content-type "text/plain; charset=utf-8"
                                     :filename "pinterest-title.txt"}})

(def social-platform-options
  [["TIKTOK" "TikTok"]
   ["YOUTUBE" "YouTube Shorts"]
   ["INSTAGRAM" "Instagram Reel"]
   ["FACEBOOK" "Facebook Reel"]
   ["PINTEREST" "Pinterest"]
   ["LINKEDIN" "LinkedIn"]
   ["TWITTER" "X / Twitter"]
   ["THREADS" "Threads"]
   ["BLUESKY" "Bluesky"]
   ["MASTODON" "Mastodon"]])

(def default-connect-platforms
  ["TIKTOK" "YOUTUBE" "INSTAGRAM" "FACEBOOK" "PINTEREST" "LINKEDIN"])

(defn now []
  (str (java.time.Instant/now)))

(defn env
  [k]
  (dotenv/env k))

(defn read-edn-file
  [path]
  (when (.exists (io/file path))
    (edn/read-string (slurp path))))

(defn read-default-params
  []
  (or (read-edn-file params-path)
      (read-edn-file "storrito-config/params.edn")
      {}))

(defn read-default-youtube-base-caption
  []
  (or (when (.exists (io/file youtube-base-caption-path))
        (slurp youtube-base-caption-path))
      (when (.exists (io/file "storrito-config/youtube-shorts-base-caption.txt"))
        (slurp "storrito-config/youtube-shorts-base-caption.txt"))
      ""))

(defn write-edn!
  [path value]
  (spit path (with-out-str (pprint/pprint value))))

(defn delete-path!
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (if (.isDirectory file)
        (doseq [child (reverse (file-seq file))]
          (io/delete-file child true))
        (io/delete-file file true)))))

(defn response
  ([status body]
   (response status {"content-type" "text/html; charset=utf-8"} body))
  ([status headers body]
   {:status status
    :headers headers
    :body body}))

(defn redirect
  [location]
  {:status 303
   :headers {"location" location}
   :body ""})

(defn nav
  []
  [:nav
   [:a {:href "/"} "New upload"]
   [:a {:href "/jobs/current"} "Current job"]
   [:a {:href "/social-accounts"} "Social accounts"]
   [:a {:href "/posted-videos"} "Posted videos"]
   [:a {:href "/storrito-api"} "Storrito API"]])

(defn page
  [title body]
  (str "<!doctype html>"
       (h/html
        [:html
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title title]
          [:script {:src "https://unpkg.com/htmx.org@2.0.4"}]
          [:style "
body { font-family: system-ui, sans-serif; line-height: 1.45; margin: 2rem auto; max-width: 64rem; padding: 0 1rem; }
nav { border-bottom: 1px solid #ddd; margin-bottom: 1.5rem; padding-bottom: 0.75rem; }
nav a { margin-right: 1rem; }
label { display: block; font-weight: 700; margin-top: 1rem; }
input, select, textarea, button { box-sizing: border-box; font: inherit; max-width: 100%; }
input[type=file], input[type=text], input[type=number], select, textarea { width: 100%; }
textarea { min-height: 8rem; }
button { margin-top: 1rem; padding: 0.6rem 1rem; }
button.primary-button { background: #0b5fff; border: 0; border-radius: 0.6rem; color: white; cursor: pointer; display: block; font-weight: 800; font-size: 1.1rem; padding: 1rem 1.25rem; width: 100%; }
button.primary-button:hover { background: #004bd1; }
video { background: #000; border-radius: 0.5rem; max-height: 70vh; max-width: 100%; }
pre { background: #111; border-radius: 0.5rem; color: #eee; overflow: auto; padding: 1rem; white-space: pre-wrap; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #ddd; padding: 0.5rem; text-align: left; vertical-align: top; }
.inline-options label { display: inline-block; font-weight: 400; margin: 0.4rem 1rem 0 0; }
.status, .panel { border: 1px solid #ddd; border-radius: 0.5rem; padding: 1rem; }
.panel { margin-top: 1rem; }
.platform-section { border-top: 1px solid #ddd; margin-top: 1rem; padding-top: 1rem; }
.downloads a { display: block; margin: 0.4rem 0; }
.error { color: #b00020; font-weight: 700; }
.muted { color: #666; }
"]]
         [:body
          (nav)
          body]])))

(defn form-page
  []
  (let [params (read-default-params)
        product-name (:product-name params)
        template (name (or (:template params) :caption-clip-wipe))
        speed-up (or (:speed-up params) 1.12)
        youtube-base-caption (read-default-youtube-base-caption)]
    (page "Content workflow"
          [:main
           [:h1 "Content workflow"]
           [:p "Upload a vertical smartphone video and generate subtitles plus platform captions."]
           (when (= :running (:status @state))
             [:p [:a {:href "/jobs/current"} "A job is running. Show progress."]])
           [:form {:id "job-form"}
            [:label {:for "input"} "Input video"]
            [:input {:id "input" :name "input" :type "file" :accept "video/mp4,video/*" :required true}]
            [:label {:for "product-name"} "Product name"]
            [:input {:id "product-name" :name "product-name" :type "text" :value (or product-name "")}]
            [:label {:for "template"} "Subtitle template"]
            [:select {:id "template" :name "template"}
             [:option (cond-> {:value "caption-clip-wipe"}
                       (= "caption-clip-wipe" template) (assoc :selected true))
              "caption-clip-wipe"]
             [:option (cond-> {:value "caption-emoji-pop"}
                       (= "caption-emoji-pop" template) (assoc :selected true))
              "caption-emoji-pop"]]
            [:label {:for "speed-up"} "Speed-up"]
            [:input {:id "speed-up" :name "speed-up" :type "number" :step "0.01" :min "0.5" :max "2" :value (str speed-up)}]
            [:label {:for "youtube-shorts-base-caption"} "YouTube Shorts base caption"]
            [:textarea {:id "youtube-shorts-base-caption" :name "youtube-shorts-base-caption" :required true}
             youtube-base-caption]
            [:button {:id "submit" :type "submit"} "Run workflow"]]
           [:script "
const form = document.getElementById('job-form');
const button = document.getElementById('submit');
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = document.getElementById('input');
  if (!input.files.length) return;
  button.disabled = true;
  button.textContent = 'Uploading video...';
  try {
    let response = await fetch('/upload/input.mp4', {method: 'PUT', body: input.files[0]});
    if (!response.ok) throw new Error(await response.text());
    button.textContent = 'Starting workflow...';
    const params = new URLSearchParams(new FormData(form));
    params.delete('input');
    response = await fetch('/jobs', {method: 'POST', body: params});
    if (!response.ok) throw new Error(await response.text());
    window.location = '/jobs/current';
  } catch (error) {
    alert(error.message);
    button.disabled = false;
    button.textContent = 'Run workflow';
  }
});
"]])))

(defn url-decode
  [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn parse-form-pairs
  [request]
  (->> (str/split (slurp (:body request)) #"&")
       (remove str/blank?)
       (mapv #(let [[k v] (str/split % #"=" 2)]
                [(url-decode k) (url-decode (or v ""))]))))

(defn parse-form
  [request]
  (into {} (parse-form-pairs request)))

(defn form-values
  [pairs key]
  (->> pairs
       (filter #(= key (first %)))
       (map second)
       (remove str/blank?)
       vec))

(defn request-origin
  [request]
  (let [headers (:headers request)
        proto (or (get headers "x-forwarded-proto")
                  (some-> (:scheme request) name)
                  "http")
        host (or (get headers "x-forwarded-host")
                 (get headers "host"))]
    (when (str/blank? host)
      (throw (ex-info "Cannot determine request host for redirect URL." {})))
    (str proto "://" host)))

(defn parse-speed-up
  [s fallback]
  (if (str/blank? (str s))
    fallback
    (Double/parseDouble s)))

(defn params-from-request
  [params]
  (cond-> {:template (keyword (get params "template" "caption-clip-wipe"))
           :speed-up (parse-speed-up (get params "speed-up") 1.12)}
    (not (str/blank? (get params "product-name")))
    (assoc :product-name (str/trim (get params "product-name")))))

(defn prepare-job!
  [params]
  (let [youtube-base-caption (str/trim (get params "youtube-shorts-base-caption" ""))]
    (when-not (.exists (io/file input-path))
      (throw (ex-info "Missing input video." {})))
    (when (str/blank? youtube-base-caption)
      (throw (ex-info "Missing YouTube Shorts base caption." {})))
    (write-edn! params-path (params-from-request params))
    (spit youtube-base-caption-path youtube-base-caption)))

(defn running?
  []
  (= :running (:status @state)))

(defn upload-input!
  [request]
  (if (running?)
    (response 409 "A job is already running.")
    (let [upload-path (str input-path ".upload")]
      (delete-path! upload-path)
      (with-open [in (:body request)
                  out (io/output-stream upload-path)]
        (io/copy in out))
      (doseq [path generated-paths]
        (when-not (= input-path path)
          (delete-path! path)))
      (delete-path! server-log-path)
      (java.nio.file.Files/move (.toPath (io/file upload-path))
                                (.toPath (io/file input-path))
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (response 200 "Uploaded."))))

(defn begin-job!
  []
  (locking state
    (when-not (running?)
      (reset! state {:status :running
                     :started-at (now)
                     :finished-at nil
                     :exit nil
                     :error nil})
      true)))

(defn finish-job!
  [status data]
  (swap! state merge data {:status status
                           :finished-at (now)}))

(defn run-workflow!
  []
  (future
    (try
      (spit server-log-path "")
      (let [process (-> (ProcessBuilder. ["./workflow.bb"])
                        (.redirectErrorStream true)
                        (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo (io/file server-log-path)))
                        (.start))
            exit (.waitFor process)]
        (finish-job! (if (zero? exit) :success :failed) {:exit exit}))
      (catch Exception e
        (spit server-log-path (str "Server error: " (.getMessage e) "\n") :append true)
        (finish-job! :failed {:error (.getMessage e)})))))

(defn submit-job
  [request]
  (if-not (begin-job!)
    (response 409 (page "Job already running" [:main [:h1 "Job already running"] [:p [:a {:href "/jobs/current"} "Show progress"]]]))
    (try
      (prepare-job! (parse-form request))
      (run-workflow!)
      (redirect "/jobs/current")
      (catch Exception e
        (finish-job! :failed {:error (.getMessage e)})
        (response 400 (page "Upload failed" [:main [:h1 "Upload failed"] [:p.error (.getMessage e)] [:p [:a {:href "/"} "Back"]]]))))))

(defn tail-log
  []
  (if (.exists (io/file server-log-path))
    (let [lines (str/split-lines (slurp server-log-path))]
      (str/join "\n" (take-last 120 lines)))
    ""))

(defn download-link
  [href label]
  (when (.exists (io/file (:path (downloads href))))
    [:a {:href href} label]))

(defn bundle-config-warning
  []
  (when-let [missing (seq (bundle/missing-config))]
    [:p.error "Bundle publishing is not configured. Set " (str/join ", " missing) "."]))

(defn platform-checkboxes
  ([default-selected]
   (platform-checkboxes social-platform-options default-selected))
  ([options default-selected]
   [:div.inline-options
    (for [[value label] options]
      [:label {:for (str "platform-" value)}
       [:input (cond-> {:id (str "platform-" value)
                        :name "platform"
                        :type "checkbox"
                        :value value}
                 (contains? default-selected value) (assoc :checked true))]
       " " label])]))

(defn social-account
  [row]
  (or (:socialAccount row) row))

(defn account-display-name
  [account]
  (or (:displayName account)
      (:username account)
      (:userDisplayName account)
      (:userUsername account)
      (:externalId account)
      (:id account)
      "connected"))

(defn channel-display-name
  [channel]
  (or (:name channel)
      (:username channel)
      (:address channel)
      (:id channel)
      "selected channel"))

(defn selected-channel
  [account]
  (let [external-id (:externalId account)
        channels (:channels account)]
    (or (when (seq channels)
          (some #(when (or (= (:id %) external-id)
                           (= (:externalId %) external-id))
                   %)
                channels))
        (first channels))))

(defn publish-targets-from-team
  [team]
  (let [accounts-by-type (->> (:socialAccounts team)
                              (map social-account)
                              (filter :type)
                              (group-by #(publish/normalize-platform (:type %))))]
    (vec
     (for [[value label] social-platform-options
           :let [accounts (seq (get accounts-by-type value))]
           :when accounts]
       {:platform value
        :label label
        :accounts (for [account accounts]
                    {:account account
                     :channel (selected-channel account)})}))))

(defn publish-platform-options-from-team
  [team]
  (vec
   (for [{:keys [platform label accounts]} (publish-targets-from-team team)]
     [platform (str label " — " (str/join ", " (map (comp account-display-name :account) accounts)))])))

(defn connected-publish-platforms!
  []
  (->> (bundle/get-team!)
       publish-targets-from-team
       (map :platform)
       set))

(def publish-copy-fields
  {"tiktok-caption" :tiktok-caption
   "youtube-title" :youtube-title
   "youtube-description" :youtube-description
   "pinterest-title" :pinterest-title
   "pinterest-description" :pinterest-description
   "instagram-caption" :instagram-caption
   "facebook-caption" :facebook-caption
   "linkedin-title" :linkedin-title
   "linkedin-text" :linkedin-text
   "twitter-text" :twitter-text
   "threads-text" :threads-text
   "bluesky-text" :bluesky-text
   "mastodon-text" :mastodon-text})

(defn copy-overrides-from-params
  [params]
  (into {}
        (for [[form-key copy-key] publish-copy-fields]
          [copy-key (get params form-key)])))

(defn copy-value
  [copy key fallback]
  (or (publish/copy-text copy key) fallback ""))

(defn default-caption
  [copy]
  (or (publish/copy-text copy :tiktok-caption)
      (publish/copy-text copy :youtube-description)
      (publish/copy-text copy :pinterest-description)
      ""))

(defn text-input
  [id label value]
  [:div
   [:label {:for id} label]
   [:input {:id id :name id :type "text" :value (or value "")}]])

(defn textarea-input
  [id label value]
  [:div
   [:label {:for id} label]
   [:textarea {:id id :name id} (or value "")]])

(defn platform-label
  [platform]
  (or (some (fn [[value label]] (when (= value platform) label)) social-platform-options)
      platform))

(defn publish-target-title
  [{:keys [account channel]}]
  (if channel
    (channel-display-name channel)
    (account-display-name account)))

(defn platform-heading
  [platform targets]
  (str (platform-label platform)
       " - "
       (str/join ", " (map publish-target-title targets))))

(defn publish-target-details
  [targets]
  (when (some :channel targets)
    [:p.muted
     (str/join
      " · "
      (for [{:keys [account channel]} targets]
        (if channel
          (str "Account: " (account-display-name account)
               " / Channel: " (channel-display-name channel))
          (str "Account: " (account-display-name account)))))]))

(defn platform-editor
  [platform copy targets]
  (let [caption (default-caption copy)
        youtube-title (copy-value copy :youtube-title (publish/default-title copy))
        youtube-description (copy-value copy :youtube-description caption)
        fields (case platform
                 "TIKTOK" [(textarea-input "tiktok-caption" "TikTok caption" (copy-value copy :tiktok-caption caption))
                           [:div
                            [:label {:for "tiktok-privacy"} "TikTok privacy"]
                            [:select {:id "tiktok-privacy" :name "tiktok-privacy"}
                             [:option {:value "PUBLIC_TO_EVERYONE" :selected true} "PUBLIC_TO_EVERYONE"]
                             [:option {:value "MUTUAL_FOLLOW_FRIENDS"} "MUTUAL_FOLLOW_FRIENDS"]
                             [:option {:value "FOLLOWER_OF_CREATOR"} "FOLLOWER_OF_CREATOR"]
                             [:option {:value "SELF_ONLY"} "SELF_ONLY"]]]]
                 "YOUTUBE" [(text-input "youtube-title" "YouTube title" youtube-title)
                            (textarea-input "youtube-description" "YouTube description" youtube-description)
                            [:div
                             [:label {:for "youtube-privacy"} "YouTube privacy"]
                             [:select {:id "youtube-privacy" :name "youtube-privacy"}
                              [:option {:value "PUBLIC" :selected true} "PUBLIC"]
                              [:option {:value "UNLISTED"} "UNLISTED"]
                              [:option {:value "PRIVATE"} "PRIVATE"]]]]
                 "PINTEREST" [(text-input "pinterest-title" "Pinterest title" (copy-value copy :pinterest-title youtube-title))
                              (textarea-input "pinterest-description" "Pinterest description" (copy-value copy :pinterest-description youtube-description))
                              [:div
                               [:label {:for "pinterest-board"} "Pinterest board name"]
                               [:input {:id "pinterest-board"
                                        :name "pinterest-board"
                                        :type "text"
                                        :placeholder "Required for Pinterest"
                                        :value (or (env "BUNDLE_SOCIAL_PINTEREST_BOARD") "")}]]]
                 "INSTAGRAM" [(textarea-input "instagram-caption" "Instagram Reel caption" (copy-value copy :instagram-caption caption))]
                 "FACEBOOK" [(textarea-input "facebook-caption" "Facebook Reel caption" (copy-value copy :facebook-caption caption))]
                 "LINKEDIN" [(text-input "linkedin-title" "LinkedIn media title" (copy-value copy :linkedin-title youtube-title))
                             (textarea-input "linkedin-text" "LinkedIn text" (copy-value copy :linkedin-text caption))
                             [:div
                              [:label {:for "linkedin-privacy"} "LinkedIn visibility"]
                              [:select {:id "linkedin-privacy" :name "linkedin-privacy"}
                               [:option {:value "PUBLIC" :selected true} "PUBLIC"]
                               [:option {:value "CONNECTIONS"} "CONNECTIONS"]
                               [:option {:value "LOGGED_IN"} "LOGGED_IN"]
                               [:option {:value "CONTAINER"} "CONTAINER"]]]]
                 "TWITTER" [(textarea-input "twitter-text" "X / Twitter text" (copy-value copy :twitter-text caption))
                            [:div
                             [:label {:for "twitter-reply-settings"} "X / Twitter reply settings"]
                             [:select {:id "twitter-reply-settings" :name "twitter-reply-settings"}
                              [:option {:value "EVERYONE" :selected true} "EVERYONE"]
                              [:option {:value "FOLLOWING"} "FOLLOWING"]
                              [:option {:value "MENTIONED_USERS"} "MENTIONED_USERS"]
                              [:option {:value "SUBSCRIBERS"} "SUBSCRIBERS"]
                              [:option {:value "VERIFIED"} "VERIFIED"]]]]
                 "THREADS" [(textarea-input "threads-text" "Threads text" (copy-value copy :threads-text caption))]
                 "BLUESKY" [(textarea-input "bluesky-text" "Bluesky text" (copy-value copy :bluesky-text caption))]
                 "MASTODON" [(textarea-input "mastodon-text" "Mastodon text" (copy-value copy :mastodon-text caption))
                             [:div
                              [:label {:for "mastodon-privacy"} "Mastodon visibility"]
                              [:select {:id "mastodon-privacy" :name "mastodon-privacy"}
                               [:option {:value "PUBLIC" :selected true} "PUBLIC"]
                               [:option {:value "UNLISTED"} "UNLISTED"]
                               [:option {:value "PRIVATE"} "PRIVATE"]
                               [:option {:value "DIRECT"} "DIRECT"]]]]
                 [])]
    (into [:section.platform-section
           [:h3 (platform-heading platform targets)]
           (publish-target-details targets)
           [:input {:type "hidden" :name "platform" :value platform}]]
          fields)))

(defn video-preview
  []
  (when (.exists (io/file output-path))
    [:div.panel
     [:h2 "Rendered video"]
     [:video {:controls true
              :preload "metadata"
              :src "/media/output.mp4"}]]))

(defn publish-form
  []
  [:div.panel
   [:h2 "Publish generated video"]
   (if-let [warning (bundle-config-warning)]
     warning
     (try
       (let [team (bundle/get-team!)
             targets (publish-targets-from-team team)
             copy (publish/generated-copy)]
         (if (seq targets)
           [:form {:method "post" :action "/publish"}
            [:p "Review/edit platform-specific titles, captions, and descriptions. This will publish to all connected platforms below."]
            (for [{:keys [platform accounts]} targets]
              (platform-editor platform copy accounts))
            [:button {:type "submit" :class "primary-button"} "Publish to all connected platforms"]]
           [:div
            [:p.muted "No connected social accounts supported by this publishing form yet."]
            [:p [:a {:href "/social-accounts"} "Connect social accounts"]]]))
       (catch Exception e
         [:div
          [:p.error "Could not load connected social accounts: " (.getMessage e)]
          [:p [:a {:href "/social-accounts"} "Open social accounts"]]])))])

(defn storrito-api-config-warning
  []
  (when-let [missing (seq (storrito-api/missing-config))]
    [:p.error "Storrito API publishing is not configured. Set " (str/join ", " missing) "."]))

(defn instagram-user-select
  [users selected]
  (if (seq users)
    [:div
     [:label {:for "storrito-instagram-username"} "Instagram account"]
     [:select {:id "storrito-instagram-username" :name "instagram-username"}
      (for [{:keys [instagramUsername instagramId]} users]
        [:option (cond-> {:value instagramUsername}
                   (= instagramUsername selected) (assoc :selected true))
         (str instagramUsername " (" instagramId ")")])]]
    (text-input "storrito-instagram-username" "Instagram username" selected)))

(defn storrito-api-story-form
  []
  [:div.panel
   [:h2 "Publish Instagram Story via Storrito API"]
   [:p.muted "Schedules the rendered video through the local Storrito API. The video is exposed through a temporary unguessable local URL so the Storrito dev server can fetch it."]
   (if-let [warning (storrito-api-config-warning)]
     [:div
      warning
      [:p "Create an API credential in Storrito, then set " [:code "STORRITO_API_TOKEN"] " in " [:code ".env.local"] "."]]
     (try
       (let [users (storrito-api/list-instagram-users!)
             selected (or (storrito-api/default-instagram-username)
                          (:instagramUsername (first users)))]
         [:form {:method "post" :action "/publish/storrito-instagram-story"}
          (instagram-user-select users selected)
          (text-input "storrito-story-link" "Link sticker URL (optional)" (env "STORRITO_INSTAGRAM_STORY_LINK"))
          [:button {:type "submit" :class "primary-button"} "Publish via Storrito API"]])
       (catch Exception e
         [:div
          [:p.error "Could not load Storrito API Instagram accounts: " (.getMessage e)]
          [:p "Check " [:code "STORRITO_API_BASE"] " and " [:code "STORRITO_API_TOKEN"] "."]])))])

(defn progress-fragment
  []
  (let [{:keys [status started-at finished-at exit error]} @state
        running (= :running status)]
    (h/html
     [:div (cond-> {:class "status"}
             running (assoc :hx-get "/jobs/current/progress"
                            :hx-trigger "every 2s"
                            :hx-swap "outerHTML"))
      [:p [:strong "Status: "] (name status)]
      (when started-at [:p [:strong "Started: "] started-at])
      (when finished-at [:p [:strong "Finished: "] finished-at])
      (when exit [:p [:strong "Exit code: "] (str exit)])
      (when error [:p.error error])
      (when (= :success status)
        [:div
         [:div.downloads
          [:h2 "Downloads"]
          (download-link "/download/output.mp4" "output.mp4")
          (download-link "/download/tiktok-caption.txt" "tiktok-caption.txt")
          (download-link "/download/youtube-shorts-caption.txt" "youtube-shorts-caption.txt")
          (download-link "/download/youtube-shorts-title.txt" "youtube-shorts-title.txt")
          (download-link "/download/pinterest-description.txt" "pinterest-description.txt")
          (download-link "/download/pinterest-title.txt" "pinterest-title.txt")]
         (video-preview)
         (publish-form)
         (storrito-api-story-form)])
      [:h2 "Log"]
      [:pre (tail-log)]])))

(defn status-page
  []
  (page "Workflow progress"
        [:main
         [:h1 "Workflow progress"]
         [:p [:a {:href "/"} "New upload"]]
         [:div {:hx-get "/jobs/current/progress" :hx-trigger "load" :hx-swap "outerHTML"}
          "Loading..."]]))

(defn social-account-row
  [row]
  (let [account (or (:socialAccount row) row)
        channel (selected-channel account)]
    [:tr
     [:td (or (:type account) "")]
     [:td (or (:displayName account) (:username account) (:userDisplayName account) "")]
     [:td (or (:username account) (:userUsername account) "")]
     [:td (if channel
            (channel-display-name channel)
            "Account itself / no channel selected")]]))

(defn social-accounts-page
  []
  (page "Social accounts"
        [:main
         [:h1 "Social accounts"]
         [:p "Connect and manage social accounts through bundle.social's hosted portal."]
         (if-let [warning (bundle-config-warning)]
           warning
           (try
             (let [team (bundle/get-team!)
                   accounts (:socialAccounts team)]
               [:div
                [:p [:strong "Bundle team: "] (:name team) " " [:span.muted (:id team)]]
                [:h2 "Connected accounts"]
                (if (seq accounts)
                  [:table
                   [:thead
                    [:tr [:th "Platform"] [:th "Name"] [:th "Username"] [:th "Selected channel"]]]
                   [:tbody
                    (for [account accounts]
                      (social-account-row account))]]
                  [:p.muted "No social accounts connected yet."])
                [:h2 "Add/manage accounts"]
                [:form {:method "post" :action "/social-accounts/connect"}
                 [:p "Choose the platforms to show in the hosted Bundle portal."]
                 (platform-checkboxes (set default-connect-platforms))
                 [:button {:type "submit"} "Open Bundle connect portal"]]])
             (catch Exception e
               [:div
                [:p.error (.getMessage e)]
                [:p "Check " [:code "BUNDLE_SOCIAL_API_KEY"] " and " [:code "BUNDLE_SOCIAL_TEAM_ID"] "."]])))]))

(defn connect-social-accounts
  [request]
  (try
    (let [pairs (parse-form-pairs request)
          platforms (or (seq (form-values pairs "platform"))
                        default-connect-platforms)
          link (bundle/create-portal-link! {:redirectUrl (str (request-origin request) "/social-accounts")
                                            :socialAccountTypes platforms
                                            :disableAutoLogin true
                                            :expiresIn 60
                                            :language "en"})]
      (redirect (:url link)))
    (catch Exception e
      (response 400 (page "Could not create connect link"
                          [:main
                           [:h1 "Could not create connect link"]
                           [:p.error (.getMessage e)]
                           [:p [:a {:href "/social-accounts"} "Back to social accounts"]]])))))

(defn submit-publish
  [request]
  (if (running?)
    (response 409 (page "Job running" [:main [:h1 "Job running"] [:p "Wait for the workflow to finish before publishing."]]))
    (try
      (let [pairs (parse-form-pairs request)
            params (into {} pairs)
            platforms (mapv publish/normalize-platform (form-values pairs "platform"))
            connected-platforms (connected-publish-platforms!)
            unsupported (remove connected-platforms platforms)]
        (when-not (seq platforms)
          (throw (ex-info "Select at least one platform." {})))
        (when (seq unsupported)
          (throw (ex-info (str "Selected platform is not connected: " (str/join ", " unsupported))
                          {:platforms unsupported})))
        (publish/publish-generated! {:platforms platforms
                                     :copy (copy-overrides-from-params params)
                                     :youtube-privacy (get params "youtube-privacy")
                                     :tiktok-privacy (get params "tiktok-privacy")
                                     :linkedin-privacy (get params "linkedin-privacy")
                                     :mastodon-privacy (get params "mastodon-privacy")
                                     :twitter-reply-settings (get params "twitter-reply-settings")
                                     :pinterest-board (get params "pinterest-board")})
        (redirect "/posted-videos"))
      (catch Exception e
        (response 400 (page "Publish failed"
                            [:main
                             [:h1 "Publish failed"]
                             [:p.error (.getMessage e)]
                             [:p [:a {:href "/jobs/current"} "Back to current job"]]]))))))

(defn submit-storrito-instagram-story
  [request]
  (if (running?)
    (response 409 (page "Job running" [:main [:h1 "Job running"] [:p "Wait for the workflow to finish before publishing."]]))
    (try
      (let [params (parse-form request)
            result (storrito-api/publish-output-story! {:video-url (public-output-url request)
                                                        :instagram-username (get params "instagram-username")
                                                        :link-url (get params "storrito-story-link")})
            story-post-uuid (:storyPostUuid result)]
        (response 200 (page "Instagram Story scheduled"
                            [:main
                             [:h1 "Instagram Story scheduled"]
                             [:p [:strong "Story post UUID: "] [:code story-post-uuid]]
                             [:p [:strong "Status: "] (:status result)]
                             [:form {:method "post" :action "/publish/storrito-instagram-story/status"}
                              [:input {:type "hidden" :name "story-post-uuid" :value story-post-uuid}]
                              [:button {:type "submit"} "Refresh status"]]
                             [:p [:a {:href "/jobs/current"} "Back to current job"]]])))
      (catch Exception e
        (response 400 (page "Storrito API publish failed"
                            [:main
                             [:h1 "Storrito API publish failed"]
                             [:p.error (.getMessage e)]
                             [:p [:a {:href "/jobs/current"} "Back to current job"]]]))))))

(defn submit-storrito-instagram-story-status
  [request]
  (try
    (let [params (parse-form request)
          story-post-uuid (get params "story-post-uuid")
          result (storrito-api/status-instagram-story! story-post-uuid)]
      (response 200 (page "Instagram Story status"
                          [:main
                           [:h1 "Instagram Story status"]
                           [:p [:strong "Story post UUID: "] [:code story-post-uuid]]
                           [:p [:strong "Status: "] (:status result)]
                           (when-let [error (:errorMessage result)]
                             [:p.error error])
                           [:form {:method "post" :action "/publish/storrito-instagram-story/status"}
                            [:input {:type "hidden" :name "story-post-uuid" :value story-post-uuid}]
                            [:button {:type "submit"} "Refresh status"]]
                           [:p [:a {:href "/jobs/current"} "Back to current job"]]])))
    (catch Exception e
      (response 400 (page "Could not load Instagram Story status"
                          [:main
                           [:h1 "Could not load Instagram Story status"]
                           [:p.error (.getMessage e)]
                           [:p [:a {:href "/jobs/current"} "Back to current job"]]])))))

(defn storrito-api-token-summary
  []
  (if-let [token (some-> (storrito-api/api-token) str/trim not-empty)]
    (str (subs token 0 (min 8 (count token))) "…")
    "not set"))

(defn storrito-api-users-table
  [users]
  (if (seq users)
    [:table
     [:thead
      [:tr [:th "Instagram username"] [:th "Instagram ID"]]]
     [:tbody
      (for [{:keys [instagramUsername instagramId]} users]
        [:tr
         [:td instagramUsername]
         [:td instagramId]])]]
    [:p.muted "No connected Instagram accounts returned by the Storrito API."]))

(defn storrito-api-status-panel
  []
  [:div.panel
   [:h2 "Connection status"]
   [:p [:strong "API base: "] [:code (storrito-api/api-base)]]
   [:p [:strong "Token: "] (storrito-api-token-summary)]
   [:p [:strong "Default Instagram username: "]
    (or (storrito-api/default-instagram-username) [:span.muted "not set"])]
   (if-let [warning (storrito-api-config-warning)]
     warning
     (try
       (let [users (storrito-api/list-instagram-users!)]
         [:div
          [:p [:strong "Status: "] [:span {:style "color: #147a00; font-weight: 700;"} "OK"]]
          [:p.muted "The API token works and the list-instagram-users procedure responded."]
          [:h3 "Connected Instagram accounts"]
          (storrito-api-users-table users)])
       (catch Exception e
         [:div
          [:p.error "Status: API request failed"]
          [:p.error (.getMessage e)]
          [:p "Check " [:code "STORRITO_API_BASE"] " and " [:code "STORRITO_API_TOKEN"] "."]])))])

(defn storrito-api-story-status-form
  ([]
   (storrito-api-story-status-form nil))
  ([story-post-uuid]
   [:div.panel
    [:h2 "Check Instagram Story post status"]
    [:p.muted "Enter any storyPostUuid returned by schedule-instagram-story. This only checks status; it does not schedule or publish anything."]
    [:form {:method "post" :action "/storrito-api/status"}
     [:label {:for "story-post-uuid"} "Story post UUID"]
     [:input {:id "story-post-uuid"
              :name "story-post-uuid"
              :type "text"
              :required true
              :value (or story-post-uuid "")}]
     [:button {:type "submit"} "Check status"]]]))

(defn storrito-api-page
  []
  (page "Storrito API"
        [:main
         [:h1 "Storrito API"]
         [:p "Use this page to verify the local Storrito API configuration and inspect story post status without scheduling a new story."]
         (storrito-api-status-panel)
         (storrito-api-story-status-form)]))

(defn submit-storrito-api-status
  [request]
  (let [params (parse-form request)
        story-post-uuid (get params "story-post-uuid")]
    (try
      (let [result (storrito-api/status-instagram-story! story-post-uuid)]
        (response 200 (page "Instagram Story status"
                            [:main
                             [:h1 "Instagram Story status"]
                             [:p [:strong "Story post UUID: "] [:code story-post-uuid]]
                             [:p [:strong "Status: "] (:status result)]
                             (when-let [error (:errorMessage result)]
                               [:p.error error])
                             (storrito-api-story-status-form story-post-uuid)
                             [:p [:a {:href "/storrito-api"} "Back to Storrito API"]]])))
      (catch Exception e
        (response 400 (page "Could not load Instagram Story status"
                            [:main
                             [:h1 "Could not load Instagram Story status"]
                             [:p.error (.getMessage e)]
                             (storrito-api-story-status-form story-post-uuid)
                             [:p [:a {:href "/storrito-api"} "Back to Storrito API"]]]))))))

(defn post-platforms
  [post]
  (->> (:data post)
       (filter (comp some? val))
       (map (comp name key))
       sort))

(defn post-accounts
  [post]
  (->> (:socialAccounts post)
       (map #(or (:socialAccount %) %))
       (map #(str (:type %) " " (or (:displayName %) (:username %) (:userDisplayName %) "")))
       (remove str/blank?)))

(defn post-text-preview
  [post]
  (let [data (:data post)]
    (or (get-in data [:YOUTUBE :description])
        (get-in data [:YOUTUBE :text])
        (get-in data [:TIKTOK :text])
        (get-in data [:INSTAGRAM :text])
        (get-in data [:FACEBOOK :text])
        (get-in data [:PINTEREST :description])
        (get-in data [:PINTEREST :text])
        "")))

(defn post-permalinks
  [post]
  (for [[platform data] (:externalData post)
        :let [url (:permalink data)]
        :when (not (str/blank? (str url)))]
    [:a {:href url :target "_blank" :rel "noopener"} (name platform)]))

(defn post-error-text
  [post]
  (or (:error post)
      (when-let [errors (:errors post)]
        (str/join "\n" (for [[platform error] errors
                            :when (not (str/blank? (str error)))]
                        (str (name platform) ": " error))))))

(defn post-row
  [post]
  [:tr
   [:td [:strong (:title post)]
    [:br]
    [:span.muted (:id post)]]
   [:td (name (:status post))]
   [:td (str/join ", " (post-platforms post))]
   [:td (str/join ", " (post-accounts post))]
   [:td (or (:postedDate post) (:postDate post) "")]
   [:td [:div (post-text-preview post)]
    (when-let [error (post-error-text post)]
      [:pre.error error])]
   [:td (interpose " " (post-permalinks post))]])

(defn posted-videos-page
  []
  (page "Posted videos"
        [:main
         [:h1 "Posted videos"]
         (if-let [warning (bundle-config-warning)]
           warning
           (try
             (let [{:keys [items total]} (bundle/list-posts!)]
               [:div
                [:p "Showing latest " (count items) " of " total " Bundle posts for team " [:code (bundle/team-id)] "."]
                (if (seq items)
                  [:table
                   [:thead
                    [:tr
                     [:th "Title"]
                     [:th "Status"]
                     [:th "Platforms"]
                     [:th "Accounts"]
                     [:th "Date"]
                     [:th "Caption / description"]
                     [:th "Links"]]]
                   [:tbody
                    (for [post items]
                      (post-row post))]]
                  [:p.muted "No Bundle posts found yet."])])
             (catch Exception e
               [:div
                [:p.error (.getMessage e)]
                [:p "Check your Bundle configuration or try again later."]])))]))

(defn media-output-response
  []
  (let [file (io/file output-path)]
    (if (and (.exists file) (.isFile file))
      {:status 200
       :headers {"content-type" "video/mp4"
                 "content-length" (str (.length file))
                 "cache-control" "no-store"}
       :body file}
      (response 404 "Not found"))))

(defn public-media-request?
  [request]
  (and (= "/public/output.mp4" (:uri request))
       (= (str "token=" public-media-token) (:query-string request))))

(defn public-output-base-url
  [request]
  (str/replace (or (some-> (env "CONTENT_WORKFLOW_PUBLIC_BASE_URL") str/trim not-empty)
                   (request-origin request))
               #"/+$" ""))

(defn public-output-url
  [request]
  (str (public-output-base-url request) "/public/output.mp4?token=" public-media-token))

(defn download-response
  [uri]
  (let [{:keys [path content-type filename]} (downloads uri)
        file (io/file path)]
    (if (and path (.exists file) (.isFile file))
      {:status 200
       :headers {"content-type" content-type
                 "content-length" (str (.length file))
                 "content-disposition" (str "attachment; filename=\"" filename "\"")}
       :body file}
      (response 404 "Not found"))))

(defn decode-basic-auth
  [header]
  (when (and header (str/starts-with? header "Basic "))
    (try
      (String. (.decode (java.util.Base64/getDecoder) (subs header 6)) java.nio.charset.StandardCharsets/UTF_8)
      (catch Exception _ nil))))

(defn valid-credentials?
  [request]
  (let [expected-user (env "CONTENT_WORKFLOW_USER")
        expected-password (env "CONTENT_WORKFLOW_PASSWORD")
        decoded (decode-basic-auth (get-in request [:headers "authorization"]))
        [user password] (when decoded (str/split decoded #":" 2))]
    (and expected-user
         expected-password
         (= expected-user user)
         (= expected-password password))))

(defn wrap-basic-auth
  [handler]
  (fn [request]
    (if (or (public-media-request? request)
            (valid-credentials? request))
      (handler request)
      {:status 401
       :headers {"www-authenticate" "Basic realm=\"Content workflow\""
                 "content-type" "text/plain; charset=utf-8"}
       :body "Authentication required."})))

(defn handler
  [request]
  (case [(:request-method request) (:uri request)]
    [:get "/"] (response 200 (form-page))
    [:put "/upload/input.mp4"] (upload-input! request)
    [:post "/jobs"] (submit-job request)
    [:get "/jobs/current"] (response 200 (status-page))
    [:get "/jobs/current/progress"] (response 200 (progress-fragment))
    [:get "/media/output.mp4"] (media-output-response)
    [:get "/public/output.mp4"] (media-output-response)
    [:get "/social-accounts"] (response 200 (social-accounts-page))
    [:post "/social-accounts/connect"] (connect-social-accounts request)
    [:get "/storrito-api"] (response 200 (storrito-api-page))
    [:post "/storrito-api/status"] (submit-storrito-api-status request)
    [:post "/publish"] (submit-publish request)
    [:post "/publish/storrito-instagram-story"] (submit-storrito-instagram-story request)
    [:post "/publish/storrito-instagram-story/status"] (submit-storrito-instagram-story-status request)
    [:get "/posted-videos"] (response 200 (posted-videos-page))
    (if (contains? downloads (:uri request))
      (download-response (:uri request))
      (response 404 "Not found"))))

(def app
  (wrap-basic-auth handler))

(defn require-auth-env! []
  (when-not (and (env "CONTENT_WORKFLOW_USER") (env "CONTENT_WORKFLOW_PASSWORD"))
    (binding [*out* *err*]
      (println "Set CONTENT_WORKFLOW_USER and CONTENT_WORKFLOW_PASSWORD before starting the server."))
    (System/exit 1)))

(defn -main
  [& _]
  (require-auth-env!)
  (let [port (Long/parseLong (or (env "PORT") "8080"))]
    (http/run-server app {:port port
                          :max-body max-request-body})
    (println "Content workflow server listening on" (str "http://localhost:" port))
    @(promise)))
