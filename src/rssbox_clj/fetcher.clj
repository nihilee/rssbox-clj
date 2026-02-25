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
            [;; --- 第一组：癌症场景 + 计算方法 (经典组合) ---
             "((\"Minimal Residual Disease\" OR \"ctDNA\" OR \"circulating tumor DNA\" OR \"Liquid Biopsy\" OR \"Early Detection of Cancer\" OR \"Cancer Screening\") AND (\"Artificial Intelligence\" OR \"Machine Learning\" OR \"Deep Learning\" OR \"Computational\"))"

             ;; --- 第二组：单细胞/空间转录组 + 算法框架 (呼应Prompt中"无需局限癌种"的前沿算法) ---
             "((\"scRNA-seq\" OR \"single-cell\" OR \"spatial transcriptomics\" OR \"spatial omics\") AND (\"deep learning framework\" OR \"computational pipeline\" OR \"Foundation Model\" OR \"Large Language Model\" OR \"algorithm\"))"

             ;; --- 第三组：明确声明自己是新工具/底层创新的黑话 ---
             "\"novel bioinformatics tool\""
             "\"new computational framework\""
             "\"Fragmentomics\""
             "\"Multi-cancer early detection\""
             "\"Methylation deconvolution\""]))

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

          ;; 先重构，再清洗
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

          ;; ==========================================
          ;; [修复点] 将 OpenAlex 的小数比例 (0~1) 转换为百分比 (0~100)
          ;; 防止 0.9875 被 (format "%.1f") 四舍五入变成 1.0
          ;; ==========================================
          raw-percentile (get-in work [:citation_normalized_percentile :value])
          percentile (when raw-percentile
                       (if (<= raw-percentile 1.0)
                         (* raw-percentile 100.0)
                         raw-percentile))

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

        ;; [修改点] 将界面展示的标签改得更严谨，直接体现 OpenAlex 的概念
        score-display (if (:score paper) (format "2yr Citedness: %.1f" (:score paper)) "")
        cited-display (if (:cited_by paper) (str "Cited by: " (:cited_by paper)) "-")
        perc-display (if (:percentile paper) (format "Top %.1f%%" (- 100.0 (:percentile paper))) "New")

        ;; 公共 CSS
        common-css "
        <style>
          .rssbox-card { font-family: -apple-system, sans-serif; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; max-width: 800px; background: #fff; }
          .rssbox-header h2 { font-size: 1.4rem; color: #111827; line-height: 1.3; margin-bottom: 12px; }
          .abstract-content .en { margin-bottom: 12px; color: #374151; line-height: 1.6; }
          .abstract-content .cn { color: #4b5563; margin-bottom: 20px; border-left: 3px solid #e5e7eb; padding-left: 12px; background: #f9fafb; padding-top:4px; padding-bottom:4px;}
          .box-recommend { background: #f0fdf4; border-left: 4px solid #16a34a; padding: 12px 16px; margin-bottom: 20px; }
          .box-reject { background: #f3f4f6; border-left: 4px solid #9ca3af; padding: 12px 16px; margin-bottom: 20px; }
          .metric-badge { display: inline-block; background: #e5e7eb; color: #374151; padding: 2px 8px; border-radius: 4px; font-size: 0.85em; margin-right: 8px; margin-bottom: 8px;}
        </style>"]

    (if is-recommended
      ;; ==========================================
      ;; --- A. 推荐样式 (绿色系) ---
      ;; ==========================================
      (format "%s
      <div class='rssbox-card'>
        <div class='rssbox-header'>
          <h2 style='margin-top:0;'>%s</h2>
          <div style='margin-bottom: 15px; color: #6b7280; font-size: 0.95em; line-height: 1.8;'>
             <div>📅 <strong>发表日期：</strong> %s</div>
             <div>📰 <strong>期刊来源：</strong> %s</div>
             <div>🏷️ <strong>文章分类：</strong> %s</div>
             <div>✍️ <strong>作者列表：</strong> %s</div>
             <div>🏛️ <strong>所属机构：</strong> %s</div>
          </div>
          <div style='margin-bottom: 20px;'>
             <!-- [修改点] 将指标变成小 Badge，更加醒目专业 -->
             %s %s %s
          </div>
        </div>

        <div class='box-recommend'>
          <div style='margin-bottom: 8px;'>
             <span style='float: right; color:#15803d; font-size: 0.85em;'>🤖 AI Review</span>
          </div>
          <p style='color: #14532d; margin: 0;'>%s</p>
          <p style='color: #14532d; margin: 8px 0 0 0; font-size: 0.85em;'>🏷️ %s</p>
        </div>

        <div class='abstract-content'>%s</div>

        <p style='margin-top: 30px;'><a href='%s' target='_blank' style='display:inline-block; background:#2563eb; color:#fff; padding:8px 16px; border-radius:6px; text-decoration:none;'>阅读全文</a></p>
      </div>"
              common-css
              (:title paper)
              date-display source-display tag
              (or (:authors paper) "Unknown")
              (or (:institution paper) "")
              (if (empty? score-display) "" (str "<span class='metric-badge'>📊 " score-display "</span>"))
              (if (empty? cited-display) "" (str "<span class='metric-badge'>🔥 " cited-display "</span>"))
              (if (empty? perc-display) "" (str "<span class='metric-badge'>📈 " perc-display "</span>"))
              (:reason review) (str/join ", " (:tags review))
              (:immersive_html review)
              (:url paper))

      ;; ==========================================
      ;; --- B. 拒稿样式 (灰色系) ---
      ;; ==========================================
      (format "%s
      <div class='rssbox-card'>
        <div class='rssbox-header'>
          <h2 style='margin-top:0; color:#4b5563;'>%s</h2>
          <div style='margin-bottom: 15px; color: #6b7280; font-size: 0.95em; line-height: 1.8;'>
             <div>📅 <strong>发表日期：</strong> %s</div>
             <div>📰 <strong>期刊来源：</strong> %s</div>
             <div>🏷️ <strong>文章分类：</strong> %s</div>
             <div>✍️ <strong>作者列表：</strong> %s</div>
             <div>🏛️ <strong>所属机构：</strong> %s</div>
          </div>
          <div style='margin-bottom: 20px;'>
             %s %s %s
          </div>
        </div>

        <div class='box-reject'>
          <div style='margin-bottom: 8px;'>
             <span style='float: right; color:#374151; font-size: 0.85em;'>🤖 AI Review</span>
          </div>
          <p style='color: #4b5563; margin: 0;'>%s</p>
          <p style='color: #4b5563; margin: 8px 0 0 0; font-size: 0.85em;'>🏷️ %s</p>
        </div>

        <div class='abstract-content'>%s</div>

        <p style='margin-top: 30px;'><a href='%s' target='_blank' style='display:inline-block; background:#6b7280; color:#fff; padding:8px 16px; border-radius:6px; text-decoration:none;'>阅读全文</a></p>
      </div>"
              common-css
              (:title paper)
              date-display source-display tag
              (or (:authors paper) "Unknown")
              (or (:institution paper) "")
              (if (empty? score-display) "" (str "<span class='metric-badge'>📊 " score-display "</span>"))
              (if (empty? cited-display) "" (str "<span class='metric-badge'>🔥 " cited-display "</span>"))
              (if (empty? perc-display) "" (str "<span class='metric-badge'>📈 " perc-display "</span>"))
              (:reason review) (str/join ", " (:tags review))
              (:immersive_html review)
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
                                              :percentile (:percentile paper)
                                              :cited_by (:cited_by paper)})]
            ;; [修复点] 严格区分 API 失败 (nil) 和 明确拒稿 (false)
            (cond
              (nil? review)
              (log/warn "[API SKIP] Review failed or timeout, will retry next cycle:" (:id paper))

              (:recommend review)
              ;; --- Case A: 推荐 ---
              (let [html (generate-html review paper (if (= tag "Fresh") "New" "Classic") true)
                    cn-title (str "⭐ " (:title_cn review))]
                (db/save-cache! db-url cn-title html)
                (log/info "[RECOMMEND] " (:id paper))
                true)

              :else
              ;; --- Case B: 明确拒稿 ---
              (let [html (generate-html review paper tag false)
                    plain-title (str "📄 " (or (:title_cn review) (:title paper)))]
                (db/save-cache! db-url plain-title html)
                (log/info "[AI FILTER] Saved:" (:id paper) "| Reason:" (:reason review))
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

          ;; 策略 B: 过去 3 年经典文章盲盒 (随机补漏)
          ;; 每天随机抽取 20 篇引用量 > 50 的经典文章，慢慢丰富数据库
          classic-works (try
                          (let [url "https://api.openalex.org/works"
                                resp (http/get url
                                               {:query-params {:search search-query
                                                               :filter (str "from_publication_date:" (.toString (.minusYears today 3))
                                                                            ",cited_by_count:>50") ;; 必须是高引用
                                                               :sample 20 ;; [核心魔法] OpenAlex 会从符合条件的文章池里随机抽 10 篇
                                                               :select openalex-fields
                                                               :mailto (config/get-config :ncbi-email)}
                                                :as :json
                                                :socket-timeout 20000
                                                :conn-timeout 5000})]
                            (log/info "Fetched 20 classic random samples.")
                            (get-in (:body resp) [:results]))
                          (catch Exception e
                            (log/warn "Classic fetch failed:" (.getMessage e))
                            []))


          ;; 合并去重 (只保留 DB 里没有的)
          all-works (concat
                     (map #(assoc % :tag "Fresh") fresh-works)
                     (map #(assoc % :tag "Classic") classic-works))

          new-works (filter #(nil? (db/get-cache (or (:doi %) (:id %)))) all-works)]

      (if (empty? new-works)
        (log/info "No new papers to process.")
        (doseq [work new-works]
          (process-work work (:tag work))
          (Thread/sleep 2500)))

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
