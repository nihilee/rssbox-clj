(ns rssbox-clj.immune-fetcher
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [rssbox-clj.config :as config]
            [rssbox-clj.processor :as proc]
            [rssbox-clj.db :as db]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            ;; [关键] 引入原有的 fetcher
            [rssbox-clj.fetcher :as fetcher]))

;; --- State ---
(defonce immune-feed-data (atom {}))

;; --- Config (独立的 Query) ---
(def default-immune-query
  (str/join " OR "
            ["\"Tumor Microenvironment\""
             "\"Immune Checkpoint Inhibitor\""
             "\"CAR-T\""
             "\"T cell exhaustion\""
             "\"Cancer Immunotherapy\""]))

(def search-query (config/get-config :immune-openalex-query default-immune-query))

;; --- 核心处理流程 ---
(defn process-work [raw-work tag]
  ;; [复用] 直接调用 fetcher/extract-info
  (when-let [paper (fetcher/extract-info raw-work)]
    (let [db-url (:url paper)
          cached (db/get-immune-cache db-url)] ;; [隔离] 查 Immune 专属 DB

      (if cached
        nil
        (cond
          (str/blank? (:abstract paper)) nil

          :else
          ;; [隔离] 调用 Immune 专属审稿函数 (需要在 processor.clj 里定义)
          (let [review (proc/review-immune-abstract {:title (:title paper)
                                                     :abstract (:abstract paper)
                                                     :journal (:journal paper)
                                                     :score (:score paper)
                                                     :institution (:institution paper)
                                                     :topic (:topic paper)
                                                     :authors (:authors paper)
                                                     :percentile (:percentile paper)
                                                     :cited_by (:cited_by paper)})]
            (if (and review (:recommend review))
              ;; [复用] 直接调用 fetcher/generate-html
              (let [html (fetcher/generate-html review paper (if (= tag "Fresh") "New" "Classic") true)
                    cn-title (str "⭐ " (:title_cn review))]
                (db/save-immune-cache! db-url cn-title html) ;; [隔离] 存入 Immune DB
                (log/info "[IMMUNE RECOMMEND] " (:id paper))
                true)

              (do
                ;; [复用] 直接调用 fetcher/generate-html
                (let [html (fetcher/generate-html review paper tag false)
                      plain-title (str "📄 " (:title paper))]
                  (db/save-immune-cache! db-url plain-title html) ;; [隔离] 存入 Immune DB
                  (log/info "[IMMUNE FILTER] Saved:" (:id paper) "| Reason:" (:reason review)))
                true))))))))

(defn update-feed []
  (log/info ">>> Immune Cycle Start...")
  (try
    (let [today (java.time.LocalDate/now)
          lookback-days (config/get-config :lookback-days 3)

          ;; [复用] 直接调用 fetcher/fetch-works，但传入 Immune 的 search-query
          fresh-works (fetcher/fetch-works search-query
                                           (.toString (.minusDays today lookback-days))
                                           "publication_date:desc"
                                           15)
          classic-works (fetcher/fetch-works search-query
                                             (.toString (.minusYears today 3))
                                             "cited_by_count:desc"
                                             10)

          all-works (concat (map #(assoc % :tag "Fresh") fresh-works)
                            (map #(assoc % :tag "Classic") classic-works))

          new-works (filter #(nil? (db/get-immune-cache (or (:doi %) (:id %)))) all-works)]

      (if (empty? new-works)
        (log/info "No new immune papers to process.")
        (doseq [work new-works]
          (process-work work (:tag work))))

      (let [recent-items (db/get-recent-immune-articles 50)]
        (reset! immune-feed-data
                {:version "https://jsonfeed.org/version/1.1"
                 :title "Tumor Immunology Radar"
                 :home_page_url "https://openalex.org/"
                 :feed_url (str (config/get-config :public-url) "/immune-articles")
                 :items recent-items}))
      (log/info ">>> Immune Cycle End."))
    (catch Exception e
      (log/error e "Immune Feed Update Error"))))

(defn start-scheduler! []
  (future
    (loop []
      (update-feed)
      (log/info "Immune Scheduler sleeping for 4 hours...")
      (Thread/sleep (* 4 60 60 1000))
      (recur))))

(defn get-feed [] @immune-feed-data)
