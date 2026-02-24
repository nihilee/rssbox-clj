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
            [;; --- 第一组：领域词 AND 技术词 (这是最核心的过滤逻辑) ---
             ;; 逻辑：(液体活检/MRD/ctDNA/早筛) AND (AI/机器学习/生物信息/深度学习)
             ;; 注意：OpenAlex 支持括号嵌套，这样写可以捕获所有组合，比如 "ctDNA" + "Deep Learning"
             (str "("
                  "\"Minimal Residual Disease\" OR "
                  "\"Measurable Residual Disease\" OR "
                  "\"ctDNA\" OR "
                  "\"circulating tumor DNA\" OR "
                  "\"Liquid Biopsy\" OR "
                  "\"Early Detection of Cancer\" OR "
                  "\"Cancer Screening\""
                  ") AND ("
                  "\"Artificial Intelligence\" OR "
                  "\"Machine Learning\" OR "
                  "\"Deep Learning\" OR "
                  "\"Bioinformatics\" OR "
                  "\"Computational Biology\" OR "
                  "\"Multi-omics\" OR "
                  "\"Transformer\" OR "
                  "\"Large Language Model\""
                  ")")

             ;; --- 第二组：本身就具有强计算属性的专有名词 (直接放行) ---
             "\"Fragmentomics\""                ; 碎片组学 (cfDNA片段模式分析，纯计算驱动)
             "\"Multi-cancer early detection\"" ; MCED (通常依赖复杂分类器)
             "\"cfDNA methylation\""            ; 甲基化数据分析通常离不开Bioinfo
             "\"Methylation deconvolution\""    ; 甲基化反卷积 (纯算法)
             ]))

(def search-query (config/get-config :openalex-query default-query))
(def min-impact-score (config/get-config :min-impact-score 3.0))

;; --- 1. OpenAlex API ---
;; 定义我们需要的所有字段，避免拉取无关数据（如概念、参考文献列表等）
(def openalex-fields
  (str/join "," ["id"
                 "doi"
                 "title"
                 "abstract_inverted_index" ;; 必须有，用于重构摘要
                 "primary_location"         ;; 用于获取期刊信息
                 "topics"
                 "authorships"
                 "citation_normalized_percentile" ;; 用于获取百分位
                 "cited_by_count"                 ;; 用于获取引用数
                 "publication_date"
                 "type"]))

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
                                             :select openalex-fields
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
(defn clean-abstract-text [text]
  (if (str/blank? text)
    ""
    (-> text
        ;; 1. 去掉紧跟在单词前的冒号 (例如 ":The" -> "The")
        (str/replace #"(?<=^|\s):(\w)" "$1")
        ;; 2. 去掉单词中间奇怪的冒号 (防御性)
        (str/replace #"(\w):(\w)" "$1$2")
        ;; 3. 修复多余空格
        (str/replace #"\s+" " ")
        (str/trim))))

(defn extract-info [work]
  (try
    (let [id (:id work)
          doi (:doi work)
          title (:title work)

          ;; [修改] 先重构，再清洗
          raw-abstract (proc/reconstruct-abstract (:abstract_inverted_index work))
          abstract (clean-abstract-text raw-abstract)

          ;; 期刊信息
          loc (:primary_location work)
          source-name (get-in loc [:source :display_name] "Unknown Source")
          journal-score (or (get-in loc [:source :summary_stats :2yr_mean_citedness])
                            (get-in loc [:source :2yr_mean_citedness]))

          topic (get-in work [:topics 0 :display_name] "Unknown Topic")

          authors-list (:authorships work)
          author-names (map #(get-in % [:author :display_name]) authors-list)
          authors-str (if (> (count author-names) 4)
                        (str (str/join ", " (take 3 author-names)) " ... " (last author-names))
                        (str/join ", " author-names))

          cited-by (:cited_by_count work)
          percentile (get-in work [:citation_normalized_percentile :value])

          inst (try (-> work :authorships first :institutions first :display_name)
                    (catch Exception _ "Unknown Inst"))

          link (or doi id)]

      {:id id
       :title title
       :url link
       :abstract abstract
       :journal source-name
       :institution inst
       :topic topic
       :authors authors-str
       :type (:type work)
       :date (:publication_date work)
       :score journal-score
       :cited_by cited-by
       :percentile percentile})

    (catch Exception e
      (log/warn "Extract info failed:" (.getMessage e))
      nil)))

;; --- 3. 生成 HTML ---
(defn generate-html [review paper tag is-recommended]
  (let [journal-display (or (:journal paper) "Unknown Source")
        source-display (if (= "preprint" (:type paper)) "Preprint" journal-display)
        date-display (if (:date paper) (:date paper) "")

        ;; [优化] 指标显示逻辑
        score-display (if (:score paper) (format "IF: %.1f" (:score paper)) "")
        cited-display (if (:cited_by paper) (str "Cited: " (:cited_by paper)) "-")

        ;; 优先显示百分位
        perc-display (if (:percentile paper)
                       (format "Top %.1f%%" (- 100.0 (:percentile paper)))
                       "New")

        ;; 公共 CSS
        common-css "
        <style>
          .rssbox-card { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; max-width: 800px; background: #fff; }
          .rssbox-header h2 { font-size: 1.4rem; color: #111827; line-height: 1.3; margin-bottom: 12px; }
          
          /* [优化] Meta 分行 */
          .rssbox-meta { font-size: 0.9rem; color: #6b7280; margin-bottom: 16px; line-height: 1.6; }
          .meta-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
          
          .rssbox-btn { display: inline-block; background: #2563eb; color: #fff; padding: 8px 16px; border-radius: 6px; text-decoration: none; font-weight: 500; font-size: 0.9rem; }
          .rssbox-btn:hover { background: #1d4ed8; }
          
          .abstract-content { font-size: 1rem; color: #374151; line-height: 1.7; margin-top: 15px; }
          .abstract-content .en { margin-bottom: 12px; }
          .abstract-content .cn { color: #4b5563; margin-bottom: 20px; border-left: 3px solid #e5e7eb; padding-left: 12px; font-size: 0.95em; background: #f9fafb; padding-top:4px; padding-bottom:4px;}
          
          .box-recommend { background: #f0fdf4; border-left: 4px solid #16a34a; padding: 12px 16px; margin-bottom: 20px; border-radius: 0 4px 4px 0; }
          .text-rec-title { color: #15803d; font-weight: bold; margin: 0; }
          .text-rec-body { color: #14532d; margin: 8px 0 0 0; }
          .badge-rec { background:#dcfce7; color:#15803d; padding:2px 8px; border-radius:4px; font-size:0.85em; font-weight:normal; margin-left: auto; }

          .box-reject { background: #f3f4f6; border-left: 4px solid #9ca3af; padding: 12px 16px; margin-bottom: 20px; border-radius: 0 4px 4px 0; }
          .text-rej-title { color: #374151; font-weight: bold; margin: 0; }
          .text-rej-body { color: #4b5563; margin: 8px 0 0 0; }
          .badge-rej { background:#e5e7eb; color:#374151; padding:2px 8px; border-radius:4px; font-size:0.85em; font-weight:normal; margin-left: auto; }
        </style>"]

    (if is-recommended
      ;; --- A. 推荐样式 ---
      (format "%s
      <div class='rssbox-card'>
        <div class='rssbox-header'>
          <h2 style='margin-top:0;'>%s</h2>
          
          <div class='rssbox-meta'>
            <div class='meta-row'>
               <span>📅 %s</span> <span style='color:#e5e7eb'>|</span>
               <span>📰 <strong>%s</strong></span> <span style='color:#e5e7eb'>|</span>
               <span>🏷️ %s</span>
            </div>
            <div class='meta-row' style='margin-top:4px; font-size:0.85em;'>
               <span>✍️ %s</span> <span style='color:#e5e7eb'>|</span>
               <span>🏛️ %s</span>
            </div>
          </div>
        </div>
        
        <div class='box-recommend'>
          <div style='display:flex; align-items:center;'>
             <div class='text-rec-title'>🤖 AI 推荐 (%s)</div>
             <span class='badge-rec'>%s%s · <strong>%s</strong></span>
          </div>
          <p class='text-rec-body'>%s</p>
          <p class='text-rec-body' style='font-size:0.85em;'>🏷️ %s</p>
        </div>

        <div class='abstract-content'>%s</div> <!-- 双语内容 -->

        <p style='margin-top: 30px;'><a href='%s' target='_blank' class='rssbox-btn'>阅读全文</a></p>
      </div>"
              common-css
              (:title paper)
              date-display source-display tag
              (or (:authors paper) "Unknown")
              (or (:institution paper) "")
              tag
              (if (empty? score-display) "" (str score-display " · "))
              cited-display perc-display ;; 指标
              (:reason review) (str/join ", " (:tags review))
              (:immersive_html review)
              (:url paper))

      ;; --- B. 拒稿样式 ---
      (format "%s
      <div class='rssbox-card'>
        <div class='rssbox-header'>
          <h2 style='margin-top:0; color:#4b5563;'>%s</h2>
          
          <div class='rssbox-meta'>
            <div class='meta-row'>
               <span>📅 %s</span> <span style='color:#e5e7eb'>|</span>
               <span>📰 %s</span>
            </div>
            <div class='meta-row' style='margin-top:4px; font-size:0.85em;'>
               <span>✍️ %s</span>
            </div>
          </div>
        </div>

        <div class='box-reject'>
          <div style='display:flex; align-items:center;'>
             <div class='text-rej-title'>🤖 AI 过滤 (Filtered)</div>
             <span class='badge-rej'>%s%s</span>
          </div>
          <p class='text-rej-body'><strong>理由：</strong>%s</p>
        </div>

        <div class='abstract-content'>
           <p class='en'>%s</p>
        </div>

        <p style='margin-top: 30px;'><a href='%s' target='_blank' class='rssbox-btn' style='background-color:#6b7280;'>阅读全文</a></p>
      </div>"
              common-css
              (:title paper)
              date-display source-display
              (or (:authors paper) "Unknown")
              (if (empty? score-display) "" (str score-display " · "))
              cited-display
              (:reason review)
              (:abstract paper)
              (:url paper)))))


;; --- 4. 核心处理流程 ---
(defn process-work [raw-work tag]
  (when-let [paper (extract-info raw-work)]
    (let [db-url (:url paper)
          cached (db/get-cache db-url)]

      (if cached
        nil ;; 已处理

        ;; 新文章处理逻辑
        (cond
          (str/blank? (:abstract paper)) nil

          ;; --- 硬过滤逻辑 ---
          ;; (and (= tag "Fresh") (< score min-impact-score) ())
          ;; (do
          ;;   ;; 只打印日志，不存入 DB
          ;;   (log/info (format "[FILTERED] Low Score (%.1f < %.1f): %s" score min-impact-score (:title paper)))
          ;;   nil)

          :else
          ;; --- 进入 AI 审核流程 ---
          (let [review (proc/review-abstract {:title (:title paper)
                                              :abstract (:abstract paper)
                                              :journal (:journal paper)
                                              :score (:score paper)
                                              :institution (:institution paper)
                                              :topic (:topic paper)
                                              :authors (:authors paper)
                                              ;; [关键] 确保传入
                                              :percentile (:percentile paper)
                                              :cited_by (:cited_by paper)})]
            (if (and review (:recommend review))
              ;; --- Case A: 推荐 ---
              (let [html (generate-html review paper (if (= tag "Fresh") "New" "Classic") true)
                    cn-title (str "⭐ " (:title_cn review))]
                (db/save-cache! db-url cn-title html)
                (log/info "[RECOMMEND] " (:id paper))
                true)

              ;; --- Case B: 拒稿 ---
              (do
                (let [html (generate-html review paper tag false)
                      plain-title (str "📄 " (:title paper))]
                  (db/save-cache! db-url plain-title html)
                  (log/info "[AI FILTER] Saved:" (:id paper) "| Reason:" (:reason review)))
                true))))))))

(defn update-feed []
  (log/info ">>> OpenAlex Hybrid Cycle Start...")
  (try
    (let [today (java.time.LocalDate/now)
          lookback-days (config/get-config :lookback-days 3)

          ;; 策略 A: 过去 3 天，按时间排序 (抓最新)
          fresh-works (fetch-works search-query
                                   (.toString (.minusDays today lookback-days))
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
      (let [recent-items (db/get-recent-articles 50)]
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
