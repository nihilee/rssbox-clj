(ns rssbox-clj.pubmed
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [rssbox-clj.config :as config]
            [rssbox-clj.processor :as proc]
            [rssbox-clj.db :as db]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; --- State: RSS Feed 数据缓存 ---
(defonce pubmed-feed-data (atom {}))

;; --- Config ---
;; 建议在 secrets.edn 中配置 email，否则 NCBI 可能会限制速率
(def ncbi-email (config/get-config :ncbi-email "your_email@example.com"))
(def ncbi-tool "rssbox-clj")

;; --- 1. 网络请求 (带重试) ---
(defn safe-get [url params]
  (loop [retries 3]
    (let [res (try
                (http/get url {:query-params (merge params {:tool ncbi-tool :email ncbi-email})
                               :as :json
                               :socket-timeout 10000
                               :conn-timeout 5000})
                (catch Exception e
                  (if (zero? retries)
                    (do (log/error "HTTP Failed after retries:" url (.getMessage e)) nil)
                    :retry)))]
      (if (= res :retry)
        (do (Thread/sleep 2000) (recur (dec retries)))
        res))))

(defn fetch-pubmed-ids [term max-results]
  (log/info "Searching PubMed:" term)
  (if-let [resp (safe-get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
                          {:db "pubmed" :term term :retmode "json" :retmax max-results :sort "date"})]
    (get-in (:body resp) [:esearchresult :idlist])
    []))

(defn fetch-pubmed-details [pmids]
  (if (empty? pmids) []
      (if-let [resp (safe-get "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi"
                              {:db "pubmed" :id (str/join "," pmids) :retmode "json"})]
        (let [uids (get-in (:body resp) [:result :uids])]
          (map #(get-in (:body resp) [:result %]) uids))
        [])))

(defn fetch-openalex-metrics [pmids]
  (if (empty? pmids) {}
      (try
        (let [url "https://api.openalex.org/works"
              filter-str (str "pmid:" (str/join "|" pmids))
              resp (http/get url {:query-params {:filter filter-str :per-page 50}
                                  :as :json
                                  :socket-timeout 10000}) ;; OpenAlex 一般比较快
              results (:results (:body resp))]
          (into {}
                (for [work results
                      :let [pmid (-> work :ids :pmid (str/replace "https://pubmed.ncbi.nlm.nih.gov/" ""))
                            source (-> work :primary_location :source)
                            score (get source :2yr_mean_citedness 0.0)
                            journal-name (get source :display_name "Unknown")
                            inst (try (-> work :authorships first :institutions first :display_name) (catch Exception _ nil))]]
                  [pmid {:score score :journal journal-name :institution inst}])))
        (catch Exception e
          (log/warn "OpenAlex Fetch Failed:" (.getMessage e))
          {}))))

;; --- 2. HTML 生成 (沉浸式风格) ---
(defn generate-immersive-html [review original-abstract db-url journal score]
  (format "
    <div style='font-family: sans-serif; line-height: 1.6; color: #333;'>
      <!-- 1. 推荐卡片 -->
      <div style='background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 16px; margin-bottom: 20px;'>
        <h3 style='margin-top:0; color: #166534;'>🤖 AI 推荐理由</h3>
        <p style='margin: 0 0 10px 0; font-weight: bold;'>%s</p>
        <div style='font-size: 0.9em; color: #555;'>
          <span style='background: #e2e8f0; padding: 2px 6px; border-radius: 4px; margin-right: 5px;'>%s</span>
          <span>IF/Score: <strong>%.1f</strong></span>
        </div>
        <p style='font-size: 0.85em; color: #666; margin-top: 8px;'>Tags: %s</p>
      </div>

      <!-- 2. 中文摘要 -->
      <div style='margin-bottom: 24px;'>
        <h4 style='border-left: 4px solid #2563eb; padding-left: 10px; margin-bottom: 12px;'>中文摘要</h4>
        <p style='text-align: justify;'>%s</p>
      </div>

      <hr style='border: 0; border-top: 1px solid #eee; margin: 20px 0;' />

      <!-- 3. 英文原版 (弱化显示) -->
      <div style='color: #555; font-size: 0.95em;'>
        <h4 style='border-left: 4px solid #94a3b8; padding-left: 10px; margin-bottom: 12px; color: #64748b;'>Original Abstract</h4>
        <p style='text-align: justify;'>%s</p>
      </div>
      
      <p style='margin-top: 30px;'>
        <a href='%s' style='background: #2563eb; color: white; padding: 8px 16px; text-decoration: none; border-radius: 4px; display: inline-block;'>View on PubMed</a>
      </p>
    </div>"
          (:reason review)
          journal
          (or score 0.0)
          (str/join ", " (:tags review))
          (:abstract_cn review)
          original-abstract
          db-url))

;; --- 3. 单篇处理流程 ---
(defn process-paper [paper oa-data]
  (let [pmid (:uid paper)
        db-url (str "https://pubmed.ncbi.nlm.nih.gov/" pmid "/")
        ;; 检查数据库是否已存在（无论是否推荐）
        cached (db/get-cache db-url)]

    (if cached
      (do
        ;; (log/info "Skip processed:" pmid)
        nil) ;; 已处理过，跳过

      ;; 没处理过，开始干活
      (let [title (:title paper)
            ;; eSummary 的 abstract 经常为空，如果需要高质量，这里可以用 efetch 补充抓取
            ;; 暂时兜底，如果为空，让 LLM 尽可能只通过标题判断（或跳过）
            raw-abstract (or (get paper :sortfirstauthor) "Abstract not provided in summary.")

            oa-info (get oa-data pmid)
            score (:score oa-info)
            journal (or (:journal oa-info) (:source paper))
            institution (:institution oa-info)]

        (cond
          ;; A. 硬过滤：分数过低 -> 存为 [SKIP]
          (and score (< score config/pubmed-min-citedness))
          (do
            (db/save-cache! db-url (str "[SKIP] (Low Score " score ") " title) "Filtered by Score")
            nil)

          ;; B. AI 审稿
          :else
          (let [review (proc/review-abstract {:title title
                                              :abstract raw-abstract
                                              :journal journal
                                              :score score
                                              :institution institution})]
            (if (and review (:recommend review))
              ;; Case 1: 推荐 -> 生成沉浸式 HTML -> 存为 ⭐
              (let [html (generate-immersive-html review raw-abstract db-url journal score)
                    cn-title (str "⭐ " (:title_cn review))]
                (db/save-cache! db-url cn-title html)
                (log/info "Recommended:" pmid)
                true) ;; 返回 true 表示有更新

              ;; Case 2: 不推荐 -> 存为 [SKIP] -> 避免重复 AI
              (do
                (db/save-cache! db-url (str "[SKIP] (AI Reject) " title) "Filtered by AI")
                (log/info "AI Rejected:" pmid)
                nil))))))))

;; --- 4. 聚合任务 ---
(defn update-pubmed-feed []
  (log/info ">>> PubMed Cycle Start...")
  (try
    (let [;; 1. 获取最新 20 篇
          pmids (fetch-pubmed-ids config/pubmed-search-term 20)
          ;; 2. 数据库查重 (找出没在 DB 里的)
          new-pmids (filter #(nil? (db/get-cache (str "https://pubmed.ncbi.nlm.nih.gov/" % "/"))) pmids)]

      (if (empty? new-pmids)
        (log/info "No new papers to process.")

        (do
          (log/info "Processing" (count new-pmids) "new papers...")
          (let [details (fetch-pubmed-details new-pmids)
                oa-metrics (fetch-openalex-metrics new-pmids)]
            ;; 串行处理避免并发过高触发 LLM 限制
            (doseq [paper details]
              (process-paper paper oa-metrics)))))

      ;; 3. 无论是否有新文章，都重新生成 RSS (从 DB 读取最近的推荐)
      (let [recent-items (db/get-recent-recommended-articles 50)]
        (reset! pubmed-feed-data
                {:version "https://jsonfeed.org/version/1.1"
                 :title "AI Research Radar (Bioinfo/Onco)"
                 :home_page_url "https://pubmed.ncbi.nlm.nih.gov/"
                 :feed_url (str (config/get-config :public-url) "/articles")
                 :items recent-items}))

      (log/info ">>> PubMed Cycle End. Feed items:" (count (:items @pubmed-feed-data))))
    (catch Exception e
      (log/error e "PubMed Update Error"))))

(defn start-scheduler! []
  (future
    (loop []
      (update-pubmed-feed)
      ;; 6 小时运行一次，避免太频繁
      (log/info "PubMed scheduler sleeping for 6 hours...")
      (Thread/sleep (* 6 60 60 1000))
      (recur))))

(defn get-feed [] @pubmed-feed-data)
