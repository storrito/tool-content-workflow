(ns content-workflow.server
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
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

(defn now []
  (str (java.time.Instant/now)))

(defn env
  [k]
  (System/getenv k))

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
body { font-family: system-ui, sans-serif; line-height: 1.45; margin: 2rem auto; max-width: 56rem; padding: 0 1rem; }
label { display: block; font-weight: 700; margin-top: 1rem; }
input, select, textarea, button { box-sizing: border-box; font: inherit; max-width: 100%; }
input[type=file], input[type=text], input[type=number], select, textarea { width: 100%; }
textarea { min-height: 12rem; }
button { margin-top: 1rem; padding: 0.6rem 1rem; }
pre { background: #111; border-radius: 0.5rem; color: #eee; overflow: auto; padding: 1rem; white-space: pre-wrap; }
.status { border: 1px solid #ddd; border-radius: 0.5rem; padding: 1rem; }
.downloads a { display: block; margin: 0.4rem 0; }
.error { color: #b00020; font-weight: 700; }
"]]
         [:body body]])))

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

(defn parse-form
  [request]
  (->> (str/split (slurp (:body request)) #"&")
       (remove str/blank?)
       (map #(let [[k v] (str/split % #"=" 2)]
               [(url-decode k) (url-decode (or v ""))]))
       (into {})))

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
        [:div.downloads
         [:h2 "Downloads"]
         (download-link "/download/output.mp4" "output.mp4")
         (download-link "/download/tiktok-caption.txt" "tiktok-caption.txt")
         (download-link "/download/youtube-shorts-caption.txt" "youtube-shorts-caption.txt")
         (download-link "/download/youtube-shorts-title.txt" "youtube-shorts-title.txt")
         (download-link "/download/pinterest-description.txt" "pinterest-description.txt")
         (download-link "/download/pinterest-title.txt" "pinterest-title.txt")])
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
    (if (valid-credentials? request)
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
