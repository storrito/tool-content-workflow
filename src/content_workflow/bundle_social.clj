(ns content-workflow.bundle-social
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [content-workflow.env :as dotenv])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def default-api-base "https://api.bundle.social")
(def default-platforms
  ["TIKTOK" "YOUTUBE" "INSTAGRAM" "FACEBOOK" "PINTEREST" "LINKEDIN"
   "TWITTER" "THREADS" "BLUESKY" "MASTODON" "REDDIT" "DISCORD" "SLACK"
   "GOOGLE_BUSINESS"])

(defn env
  [k]
  (dotenv/env k))

(defn api-base
  []
  (str/replace (or (env "BUNDLE_SOCIAL_API_BASE") default-api-base) #"/+$" ""))

(defn api-key
  []
  (env "BUNDLE_SOCIAL_API_KEY"))

(defn team-id
  []
  (env "BUNDLE_SOCIAL_TEAM_ID"))

(defn configured?
  []
  (and (not (str/blank? (api-key)))
       (not (str/blank? (team-id)))))

(defn missing-config
  []
  (cond-> []
    (str/blank? (api-key)) (conj "BUNDLE_SOCIAL_API_KEY")
    (str/blank? (team-id)) (conj "BUNDLE_SOCIAL_TEAM_ID")))

(defn require-config!
  []
  (when-let [missing (seq (missing-config))]
    (throw (ex-info (str "Missing Bundle config: " (str/join ", " missing))
                    {:missing missing}))))

(defn url-encode
  [value]
  (URLEncoder/encode (str value) "UTF-8"))

(defn query-string
  [params]
  (let [pairs (for [[k v] params
                    :when (some? v)
                    v (if (and (sequential? v) (not (string? v))) v [v])
                    :when (not (str/blank? (str v)))]
                (str (url-encode (name k)) "=" (url-encode v)))]
    (when (seq pairs)
      (str "?" (str/join "&" pairs)))))

(defn parse-json
  [body]
  (when-not (str/blank? (str body))
    (try
      (json/parse-string body true)
      (catch Exception _
        nil))))

(defn api-request!
  ([method path]
   (api-request! method path {}))
  ([method path {:keys [query body headers]}]
   (when (str/blank? (api-key))
     (throw (ex-info "Missing BUNDLE_SOCIAL_API_KEY" {:missing ["BUNDLE_SOCIAL_API_KEY"]})))
   (let [url (str (api-base) path (query-string query))
         request (cond-> {:method (keyword (str/lower-case (name method)))
                          :uri url
                          :headers (merge {"accept" "application/json"
                                           "x-api-key" (api-key)}
                                          headers)
                          :throw false}
                   body (-> (assoc :body (json/generate-string body))
                            (assoc-in [:headers "content-type"] "application/json")))
         response (http/request request)
         status (:status response)
         parsed (parse-json (:body response))]
     (if (<= 200 status 299)
       parsed
       (throw (ex-info (str "Bundle API request failed: " status " " (or (:message parsed) (:body response)))
                       {:status status
                        :method method
                        :path path
                        :response parsed
                        :body (:body response)}))))))

(defn get-team!
  ([]
   (get-team! (team-id)))
  ([team-id]
   (require-config!)
   (api-request! :get (str "/api/v1/team/" team-id))))

(defn list-teams!
  ([]
   (list-teams! {}))
  ([query]
   (api-request! :get "/api/v1/team/" {:query (merge {:offset 0 :limit 50} query)})))

(defn create-portal-link!
  [{:keys [teamId redirectUrl socialAccountTypes]
    :as payload}]
  (require-config!)
  (api-request! :post "/api/v1/social-account/create-portal-link"
                {:body (merge {:teamId (or teamId (team-id))
                               :socialAccountTypes (or (seq socialAccountTypes)
                                                       ["TIKTOK" "YOUTUBE" "INSTAGRAM" "FACEBOOK" "PINTEREST"])}
                              (select-keys payload [:redirectUrl
                                                    :serverUrl
                                                    :disableAutoLogin
                                                    :forceBrowserOAuth
                                                    :instagramConnectionMethod
                                                    :withBusinessScope
                                                    :expiresIn
                                                    :logoUrl
                                                    :userLogoUrl
                                                    :userName
                                                    :goBackButtonText
                                                    :hidePoweredBy
                                                    :hideGoBackButton
                                                    :hideUserLogo
                                                    :hideUserName
                                                    :hideLanguageSwitcher
                                                    :showModalOnConnectSuccess
                                                    :language
                                                    :maxSocialAccountsConnected]))}))

(defn init-upload!
  [{:keys [teamId fileName mimeType]}]
  (require-config!)
  (api-request! :post "/api/v1/upload/init"
                {:body {:teamId (or teamId (team-id))
                        :fileName fileName
                        :mimeType mimeType}}))

(defn finalize-upload!
  [{:keys [teamId path]}]
  (require-config!)
  (api-request! :post "/api/v1/upload/finalize"
                {:body {:teamId (or teamId (team-id))
                        :path path}}))

(defn put-file-to-signed-url!
  [{:keys [url file-path mime-type]}]
  (let [file (io/file file-path)]
    (when-not (.exists file)
      (throw (ex-info (str "Missing upload file: " file-path) {:file-path file-path})))
    (let [client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder (URI/create url))
                      (.header "content-type" mime-type)
                      (.PUT (HttpRequest$BodyPublishers/ofFile (.toPath file)))
                      (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (when-not (<= 200 status 299)
        (throw (ex-info (str "Signed upload failed: " status " " (.body response))
                        {:status status
                         :body (.body response)})))
      {:status status})))

(defn upload-video!
  [{:keys [teamId file-path file-name mime-type]
    :or {file-path "output.mp4"
         file-name "output.mp4"
         mime-type "video/mp4"}}]
  (let [init (init-upload! {:teamId teamId
                            :fileName file-name
                            :mimeType mime-type})]
    (put-file-to-signed-url! {:url (:url init)
                              :file-path file-path
                              :mime-type mime-type})
    (finalize-upload! {:teamId teamId
                       :path (:path init)})))

(defn create-post!
  [payload]
  (require-config!)
  (api-request! :post "/api/v1/post/" {:body payload}))

(defn list-posts!
  ([]
   (list-posts! {}))
  ([query]
   (require-config!)
   (api-request! :get "/api/v1/post/"
                 {:query (merge {:teamId (team-id)
                                  :orderBy "createdAt"
                                  :order "DESC"
                                  :offset 0
                                  :limit 50}
                                 query)})))

(defn get-post!
  [id]
  (require-config!)
  (api-request! :get (str "/api/v1/post/" id)))

(defn get-post-by-reference-key!
  [reference-key]
  (require-config!)
  (api-request! :get (str "/api/v1/post/reference-key/" (url-encode reference-key))))
