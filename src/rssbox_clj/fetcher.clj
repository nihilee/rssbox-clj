(ns rssbox-clj.fetcher
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [rssbox-clj.config :as config]
            [rssbox-clj.processor :as proc]
            [rssbox-clj.db :as db]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; --- State ---
(defonce feed-data (atom {}))

;; --- Config ---
(def default-query
  (str/join " OR "
            ["\"Minimal Residual Disease\""
             "\"Measurable Residual Disease\""
             "\"ctDNA\""
             "\"circulating tumor DNA\""

             "\"Early Detection of Cancer\""
             "\"Multi-cancer early detection\""

             "(\"Immunotherapy\" AND \"Bioinformatics\")"
             "(\"Immune Checkpoint Inhibitors\" AND \"Machine Learning\")"
             "\"Liquid Biopsy\""]))

(def search-query (config/get-config :openalex-query default-query))
(def min-impact-score (config/get-config :min-impact-score 3.0))

;; --- 1. OpenAlex API (通用版) ---
(defn fetch-works [term from-date sort-type limit]
  (log/info "Searching OpenAlex:" term "| Since:" from-date "| Sort:" sort-type)
  (try
    (let [url "https://api.openalex.org/works"
          ;; [新增] 获取 API Key
          oa-api-key (config/get-config :openalex-api-key nil)

          resp (http/get url {:query-params {:search term
                                             :filter (str "from_publication_date:" from-date)
                                             :sort sort-type
                                             :per-page limit
                                             ;; mailto 是礼貌，API Key 是权限
                                             :mailto (config/get-config :ncbi-email)}

                              ;; [新增] 添加 API Key 认证
                              :headers (if oa-api-key
                                         {"Authorization" (str "Bearer " oa-api-key)}
                                         {})

                              :as :json
                              :socket-timeout 20000
                              :conn-timeout 5000})
          results (get-in (:body resp) [:results])]

      (log/info "Fetched" (count results) "works via" sort-type)
      results)
    (catch Exception e
      (log/error "OpenAlex Search Failed:" (.getMessage e))
      [])))

;; --- 2. 数据清洗 ---
(defn extract-info [work]
  (try
    (let [id (:id work)
          doi (:doi work)
          title (:title work)
          abstract (proc/reconstruct-abstract (:abstract_inverted_index work))

          loc (:primary_location work)
          source-name (get-in loc [:source :display_name] "Unknown Source")
          score (get-in loc [:source :2yr_mean_citedness] 0.0)

          ;; [新增] 提取 Topics (取主要的一个)
          topic (get-in work [:topics 0 :display_name] "Unknown Topic")

          ;; [新增] 提取作者 (前 3 位 + 最后 1 位通讯作者风格)
          authors-list (:authorships work)
          author-names (map #(get-in % [:author :display_name]) authors-list)
          authors-str (if (> (count author-names) 4)
                        (str (str/join ", " (take 3 author-names)) " ... " (last author-names))
                        (str/join ", " author-names))

          ;; [新增] 引用百分位 (非常重要的相对指标，0-100)
          ;; 新文章可能没有这个字段，默认为 0
          percentile (get-in work [:citation_normalized_percentile :value] 0)

          inst (try (-> work :authorships first :institutions first :display_name)
                    (catch Exception _ "Unknown Inst"))

          link (or doi id)]

      {:id id
       :title title
       :url link
       :abstract abstract
       :journal source-name
       :score score
       :type (:type work)
       :institution inst

       ;; 新增字段传递
       :topic topic
       :authors authors-str
       :percentile percentile
       :cited_by (:cited_by_count work)
       :date (:publication_date work)})
    (catch Exception e
      (log/warn "Extract info failed:" (.getMessage e))
      nil)))


;; --- 3. 生成 HTML ---
(defn generate-html [review paper tag]
  (format "
    <div style='font-family: sans-serif; line-height: 1.6; color: #333;'>
      <!-- 推荐卡片 -->
      <div style='background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 16px; margin-bottom: 20px;'>
        <h3 style='margin-top:0; color: #166534;'>🤖 AI 推荐 <span style='font-size:0.7em; color:#666; font-weight:normal;'>(%s)</span></h3>
        <p style='margin: 0 0 10px 0; font-weight: bold;'>%s</p>
        <div style='font-size: 0.85em; color: #555; display: flex; gap: 10px; flex-wrap: wrap;'>
          <span style='background: #e2e8f0; padding: 2px 6px; border-radius: 4px;'>%s</span>
          <span style='background: #fff7ed; border: 1px solid #ffedd5; padding: 2px 6px; border-radius: 4px;'>%s</span>
          <span>IF: <strong>%.1f</strong></span>
          <span>Cited: <strong>%d</strong></span>
        </div>
        <p style='font-size: 0.85em; color: #666; margin-top: 8px;'>Tags: %s</p>
      </div>

      <!-- 中文摘要 -->
      <div style='margin-bottom: 24px;'>
        <h4 style='border-left: 4px solid #2563eb; padding-left: 10px; margin-bottom: 12px;'>中文摘要</h4>
        <p style='text-align: justify;'>%s</p>
      </div>

      <hr style='border: 0; border-top: 1px solid #eee; margin: 20px 0;' />

      <!-- 原文摘要 -->
      <div style='color: #666; font-size: 0.95em;'>
        <h4 style='border-left: 4px solid #94a3b8; padding-left: 10px; margin-bottom: 12px; color: #64748b;'>Original Abstract</h4>
        <p style='text-align: justify;'>%s</p>
      </div>

      <p style='margin-top: 30px;'>
        <a href='%s' target='_blank' style='background: #2563eb; color: white; padding: 8px 16px; text-decoration: none; border-radius: 4px; display: inline-block;'>View Full Text</a>
      </p>
    </div>"
          tag ;; "最新情报" 或 "经典高引"
          (:reason review)
          (:journal paper)
          (:institution paper)
          (or (:score paper) 0.0)
          (or (:cited_by paper) 0) ;; 显示被引数
          (str/join ", " (:tags review))
          (:abstract_cn review)
          (:abstract paper)
          (:url paper)))

;; --- 4. 核心处理流程 ---
(defn process-work [raw-work tag]
  (when-let [paper (extract-info raw-work)]
    (let [db-url (:url paper)
          cached (db/get-cache db-url)]

      (if cached
        nil ;; 已处理

        ;; 新文章处理
        (let [score (:score paper)
              inst (:institution paper)
              is-top-inst (re-find #"(?i)(Google|DeepMind|Broad|Harvard|Stanford|MIT|MD Anderson|Memorial Sloan Kettering)" (str inst))
              is-preprint (= "preprint" (:type paper))]

          (cond
            (str/blank? (:abstract paper)) nil

            ;; 只有对于 "最新" 的文章才做硬过滤，对于 "经典" (按引用排序) 的文章，只要能排到前面，说明已经很有价值了，适当放宽分数限制
            (and (= tag "Fresh") (< score min-impact-score) (not is-top-inst) (not is-preprint))
            (do (db/save-cache! db-url (str "[SKIP] " (:title paper)) "Low Score") nil)

            :else
            (let [review (proc/review-abstract {:title (:title paper)
                                                :abstract (:abstract paper)
                                                :journal (:journal paper)
                                                :score score
                                                :institution inst
                                                ;; [新增] 传递新参数
                                                :topic (:topic paper)
                                                :authors (:authors paper)
                                                :percentile (:percentile paper)})]
              (if (and review (:recommend review))
                (let [html (generate-html review paper (if (= tag "Fresh") "New" "Classic"))
                      cn-title (str "⭐ " (:title_cn review))]
                  (db/save-cache! db-url cn-title html)
                  (log/info "Recommended (" tag "):" (:id paper))
                  true)
                (do
                  (db/save-cache! db-url (str "[SKIP] (AI) " (:title paper)) "AI Reject")
                  nil)))))))))

(defn update-feed []
  (log/info ">>> OpenAlex Hybrid Cycle Start...")
  (try
    (let [today (java.time.LocalDate/now)

          ;; 策略 A: 过去 3 天，按时间排序 (抓最新)
          fresh-works (fetch-works search-query
                                   (.toString (.minusDays today 3))
                                   "publication_date:desc"
                                   15)

          ;; 策略 B: 过去 3 年，按引用数排序 (抓经典/补漏)
          ;; 每天补 10 篇经典，慢慢填满你的数据库
          classic-works (fetch-works search-query
                                     (.toString (.minusYears today 3))
                                     "cited_by_count:desc"
                                     10)

          ;; 合并去重 (只保留 DB 里没有的)
          all-works (concat
                     (map #(assoc % :tag "Fresh") fresh-works)
                     (map #(assoc % :tag "Classic") classic-works))

          new-works (filter #(nil? (db/get-cache (or (:doi %) (:id %)))) all-works)]

      (if (empty? new-works)
        (log/info "No new papers to process.")
        (doseq [work new-works]
          (process-work work (:tag work))))

      ;; 更新 Feed
      (let [recent-items (db/get-recent-recommended-articles 50)]
        (reset! feed-data
                {:version "https://jsonfeed.org/version/1.1"
                 :title "AI & Cancer Early Detection Radar"
                 :home_page_url "https://openalex.org/"
                 :feed_url (str (config/get-config :public-url) "/articles")
                 :items recent-items}))
      (log/info ">>> OpenAlex Cycle End."))
    (catch Exception e
      (log/error e "Feed Update Error"))))

(defn start-scheduler! []
  (future
    (loop []
      (update-feed)
      (log/info "Scheduler sleeping for 4 hours...")
      (Thread/sleep (* 4 60 60 1000))
      (recur))))

(defn get-feed [] @feed-data)
