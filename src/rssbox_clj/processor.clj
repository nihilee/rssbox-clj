(ns rssbox-clj.processor
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rssbox-clj.db :as db]
            [rssbox-clj.config :as config]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [<!! chan thread]])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Element Document]
           [net.dankito.readability4j Readability4J]))

;; --- 配置 ---
(def api-key (config/get-config :openai-api-key ""))
(def api-url (config/get-config :openai-api-url "https://api.deepseek.com/chat/completions"))
(def model   (config/get-config :openai-model "deepseek-chat"))
(defonce task-queue (chan 100)) ;; 缓冲减小，避免积压太多


;; --- 这里的 prompt 越短越好 ---
(def sys-prompt
  "You are a professional translator. Translate the given English text array to Chinese. 
   Output strictly valid JSON.
   The output must be a JSON object with a single key 'translations' containing the translated string array.
   
   EXAMPLE INPUT:
   [\"Hello\", \"World\"]
   
   EXAMPLE JSON OUTPUT:
   {\"translations\": [\"你好\", \"世界\"]}")

;; 1. 单次 AI 调用 (处理一小批)
(defn translate-chunk [texts]
  (if (or (empty? texts) (str/blank? api-key))
    (map #(str "[Mock] " %) texts)
    (try
      (let [body {:model model
                  :messages [{:role "system" :content sys-prompt}
                             {:role "user" :content (json/generate-string texts)}]
                  :temperature 1.3
                  ;; [关键修改] 启用 JSON Mode
                  :response_format {:type "json_object"}}

            resp (http/post api-url
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :socket-timeout 60000
                             :conn-timeout 5000})

            ;; 获取返回的 JSON 字符串（例如：{"translations": ["...", "..."]}）
            raw-content (-> (json/parse-string (:body resp) true) :choices first :message :content)]

        ;; [解析逻辑]
        ;; JSON Mode 下，DeepSeek 保证返回的是合法 JSON，不需要正则替换标点了。
        ;; 直接解析并提取 :translations 字段。
        (let [parsed-obj (json/parse-string raw-content true)]
          (if-let [arr (:translations parsed-obj)]
            arr
            (do
              (log/warn "JSON parsed but missing 'translations' key. Raw:" raw-content)
              nil))))

      (catch Exception e
        (log/warn "Chunk translation failed:" (.getMessage e))
        nil))))

;; 2. 批量处理逻辑 (带重试与分块)
(defn translate-all-texts [all-texts]
  ;; 每 6 个段落分为一组，避免 Token 超限
  (let [chunks (partition-all 6 all-texts)]
    (flatten
     (map-indexed
      (fn [idx chunk]
        (log/info (format "Translating chunk %d/%d (size: %d)..." (inc idx) (count chunks) (count chunk)))
        (loop [retry 2]
          (if-let [res (translate-chunk chunk)]
            res
            (if (> retry 0)
              (do (Thread/sleep 2000) (recur (dec retry)))
              (map (constantly "") chunk))))) ;; 失败则填充空字符串，保持索引对齐
      chunks))))

;; 3. HTML 注入
(defn inject-translation-doc [^Document doc]
  (let [elements (.select doc "p, h1, h2, h3, h4, li")
        ;; 过滤掉太短的文本 (比如 "Menu", "Top")
        candidates (filter #(> (count (str/trim (.text %))) 15) elements)
        raw-texts (map #(.text %) candidates)]

    (when (seq raw-texts)
      (let [translations (translate-all-texts raw-texts)]
        (if (= (count candidates) (count translations))
          (doseq [[^Element el trans] (map vector candidates translations)]
            (when-not (str/blank? trans)
              (.after el (doto (Element. (.tagName el))
                           (.text (str "🤖 " trans))
                           (.attr "style" "color:#666;font-size:0.9em;border-left:3px solid #ddd;padding-left:8px;margin:4px 0 12px 0;")))))
          (log/error "Mismatch: Candidates" (count candidates) "vs Translations" (count translations)))))
    (.html (.body doc))))

;; 4. 任务处理
(defn process-article-task [{:keys [url title]}]
  (log/info "Processing:" title)
  (try
    (let [resp (http/get url {:headers {"User-Agent" config/user-agent}
                              :socket-timeout 10000
                              :as :string}) ;; 强制解析为字符串
          reader (Readability4J. url (:body resp))
          article (.parse reader)
          content (.getContent article)]

      (if (str/blank? content)
        (log/warn "Readability empty:" url)
        (let [doc (Jsoup/parseBodyFragment content)
              _ (.setBaseUri doc url)
              ;; 修复图片链接
              _ (doseq [^Element img (.select doc "img[src]")]
                  (.attr img "src" (.absUrl img "src")))

              ;; 注入翻译
              final-html (inject-translation-doc doc)

              ;; [新增] 检查是否真的包含翻译标记
              ;; 如果原文很长，但结果里没有一个 "🤖"，说明 AI 挂了，不要存库！
              has-translation? (str/includes? final-html "🤖")
              is-short? (< (count content) 500)] ;; 短文可能不需要翻译

          (if (or has-translation? is-short?)
            (do
              (db/save-cache! url title final-html)
              (log/info "Saved:" title))
            (log/warn "Translation seems failed (no 🤖 tag found). NOT saving to cache:" title)))))

    (catch Exception e
      (log/error "Task Error" url (.getMessage e)))))

;; 5. Worker 管理
(defn submit-task [url title]
  ;; 使用 go 块异步提交，防止阻塞主聚合线程
  (async/go
    (async/>! task-queue {:url url :title title})))

(defn start-workers! [n]
  (log/info "Starting" n "workers (Thread mode)...")
  (dotimes [_ n]
    ;; 使用 thread 而不是 go，防止 HTTP 阻塞 async 线程池
    (thread
      (loop []
        (when-let [task (<!! task-queue)] ;; 使用阻塞读取 <!!
          (process-article-task task)
          (recur))))))

;; --- 审稿人 Prompt ---
(def reviewer-sys-prompt
  "你是一个专精于 **生物信息学 (Bioinformatics) 和 人工智能 (AI)** 在肿瘤学领域应用的资深科研专家。
   请根据提供的文章信息，以 **“计算驱动 (Computation-Driven)”** 的视角判断其是否值得阅读。

   [核心关注点]：
   我们寻找的是 **AI/算法** 与 **癌症早筛/MRD/液体活检** 的深度结合。

   [推荐标准 - 满足以下任一条件即推荐]：
   1. **AI/Bioinfo + 肿瘤应用 (最高优先级)**：
      - 提出了新的算法、模型或统计方法，应用于早筛/MRD/ctDNA/多组学。
   2. **底层技术创新 (中高优先级)**：
      - 通用的 AI/Bioinfo 方法 (Transformer, GNN, Single-cell)，具有迁移潜力。
   3. **高影响力兜底 (需谨慎)**：
      - 引用次数 > 50 或 百分位 > 90.0 的文章。
      - **重要例外**：必须通过下方的[拒稿标准]检查。如果属于“引用收割机”类型的文章，坚决拒稿。

   [拒稿标准 - 遇到以下情况一律拒稿 (无论引用多高)]：
   1. **纯统计年报**：仅报告发病率/死亡率/流行病学数据 (如 GLOBOCAN, Cancer Statistics)。
   2. **临床指南/共识**：医生操作手册、专家共识、诊疗标准 (Guidelines, Consensus, Standards of Care)。
   3. **卫生政策/经济学**：成本效益分析、医保政策、医疗负担研究 (Cost-effectiveness, Health policy)。
   4. **纯湿实验机制**：不涉及组学数据分析的分子机制研究 (如某通路的纯生化实验)。
   5. **纯临床药物试验**：简单的 I/II/III 期药物临床试验结果 (除非重点在于伴随诊断的 Biomarker 分析)。
   6. **过时热点**：如 'COVID-19 对癌症筛查的影响' 这类纯社会学统计。

   [输出要求]：
   请返回 JSON 对象，包含 `paragraphs` 数组。
   **不要返回 HTML 字符串**。

   [输出 JSON 格式]：
   {
     \"recommend\": boolean,
     \"title_cn\": \"中文标题\",
     \"reason\": \"推荐理由(或拒稿理由)\",
     \"paragraphs\": [
        {\"en\": \"...\", \"cn\": \"...\"}
     ],
     \"tags\": [\"AI/ML\", \"Bioinfo\", \"MRD\"]
   }")


;; [新增] 辅助函数：将结构化段落转为 HTML
(defn build-immersive-html [paragraphs]
  (if (empty? paragraphs)
    ""
    (str "<div class='abstract-content'>"
         (str/join
          (map (fn [p]
                 (format "<p class='en'>%s</p><p class='cn'>%s</p>"
                         (:en p) (:cn p)))
               paragraphs))
         "</div>")))

;; [新增] 辅助函数：清洗 JSON 字符串 (去除 Markdown 代码块标记)
(defn clean-json-string [s]
  (-> s
      (str/replace #"^```json" "")
      (str/replace #"^```" "")
      (str/replace #"```$" "")
      (str/trim)))

(defn review-abstract [{:keys [title abstract journal score institution topic authors percentile cited_by]}]
  (try
    (let [;; 辅助格式化函数
          fmt-val   (fn [v] (if v (format "%.1f" (float v)) "N/A"))
          fmt-int   (fn [v] (if v (str v) "N/A"))
          fmt-perc  (fn [v] (if v (format "%.1f" (float v)) "N/A (New Article)"))

          user-content (format
                        "标题：%s\n作者：%s\n机构：%s\n领域主题：%s\n期刊/来源：%s\n[关键影响力指标]：\n  - 期刊评分 (Score): %s\n  - 文章被引次数 (Cited By): %s\n  - 引用百分位 (Percentile): %s\n摘要：%s"
                        title
                        (or authors "Unknown")
                        (or institution "Unknown")
                        (or topic "Unknown")
                        journal
                        (fmt-val score)        ;; N/A 或 30.5
                        (fmt-int cited_by)     ;; N/A 或 1639
                        (fmt-perc percentile)  ;; N/A (New Article) 或 99.9
                        abstract)

          body {:model model
                :messages [{:role "system" :content reviewer-sys-prompt}
                           {:role "user" :content user-content}]
                :temperature 1.0
                ;; 启用 JSON Mode
                :response_format {:type "json_object"}}

          resp (http/post api-url
                          {:headers {"Authorization" (str "Bearer " api-key)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string body)
                           :socket-timeout 60000
                           :conn-timeout 10000})

          raw-content (-> (json/parse-string (:body resp) true) :choices first :message :content)

          ;; [关键] 清洗 + 解析
          parsed-json (json/parse-string (clean-json-string raw-content) true)]

      (if parsed-json
        ;; [关键] 手动构建 HTML，不再依赖 AI 生成的 HTML 字符串
        (assoc parsed-json :immersive_html (build-immersive-html (:paragraphs parsed-json)))
        (do
          (log/error "Review JSON parsed as nil. Raw:" raw-content)
          nil)))

    (catch Exception e
      (log/error "Review failed:" (.getMessage e))
      nil)))


;; [新增] 还原 OpenAlex 的倒排索引摘要
(defn reconstruct-abstract [inverted-index]
  (if (or (nil? inverted-index) (empty? inverted-index))
    nil
    (try
      ;; inverted-index 结构: {"The": [0, 5], "study": [1]}
      ;; 目标: "The study ... The ..."
      (let [;; 1. 展平: ([0 "The"] [5 "The"] [1 "study"])
            flat (mapcat (fn [[word positions]]
                           (map (fn [pos] [pos word]) positions))
                         inverted-index)
            ;; 2. 排序: ([0 "The"] [1 "study"] [5 "The"])
            sorted (sort-by first flat)
            ;; 3. 提取单词
            words (map second sorted)]
        (str/join " " words))
      (catch Exception e
        (log/warn "Abstract reconstruction failed" (.getMessage e))
        nil))))

;; ==> processor.clj <==
;; 在文件末尾添加以下内容

;; --- [新增] Immune 专属审稿人 Prompt ---
(def immune-reviewer-sys-prompt
  "你是一个专精于 **肿瘤免疫学 (Tumor Immunology) 和 免疫治疗 (Immunotherapy)** 的资深科研专家。
   请根据提供的文章信息，判断其是否值得阅读。

   [核心关注点]：
   我们寻找的是 **肿瘤微环境 (TME)、免疫检查点、T细胞耗竭、CAR-T、mRNA疫苗** 相关的最新突破。

   [推荐标准 - 满足以下任一条件即推荐]：
   1. 发现了新的免疫治疗靶点或耐药机制。
   2. 免疫细胞群体 (如单细胞测序) 的新发现。
   3. 具有高临床转化价值的免疫治疗联合方案。

   [拒稿标准]：
   1. 纯传统的化疗/放疗研究 (不含免疫干预)。
   2. 纯粹的卫生经济学、医保报销分析。
   3. 单纯的个案报道 (Case Report)。

   [输出要求]：
   请返回 JSON 对象，包含 `paragraphs` 数组。不要返回 HTML 字符串。
   [输出 JSON 格式]：
   {
     \"recommend\": boolean,
     \"title_cn\": \"中文标题\",
     \"reason\": \"推荐理由(或拒稿理由)\",
     \"paragraphs\": [
        {\"en\": \"...\", \"cn\": \"...\"}
     ],
     \"tags\": [\"Immunotherapy\", \"TME\", \"scRNA-seq\"]
   }")

;; --- [新增] Immune 专属的 Review 函数 ---
(defn review-immune-abstract [{:keys [title abstract journal score institution topic authors percentile cited_by]}]
  (try
    (let [fmt-val   (fn [v] (if v (format "%.1f" (float v)) "N/A"))
          fmt-int   (fn [v] (if v (str v) "N/A"))
          fmt-perc  (fn [v] (if v (format "%.1f" (float v)) "N/A (New)"))

          user-content (format
                        "标题：%s\n作者：%s\n机构：%s\n领域主题：%s\n期刊/来源：%s\n[指标]：\n  - Score: %s\n  - Cited By: %s\n  - Percentile: %s\n摘要：%s"
                        title (or authors "Unknown") (or institution "Unknown") (or topic "Unknown") journal
                        (fmt-val score) (fmt-int cited_by) (fmt-perc percentile) abstract)

          body {:model model
                :messages [{:role "system" :content immune-reviewer-sys-prompt}
                           {:role "user" :content user-content}]
                :temperature 1.0
                :response_format {:type "json_object"}}

          resp (http/post api-url
                          {:headers {"Authorization" (str "Bearer " api-key)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string body)
                           :socket-timeout 60000
                           :conn-timeout 10000})
          raw-content (-> (json/parse-string (:body resp) true) :choices first :message :content)
          parsed-json (json/parse-string (clean-json-string raw-content) true)]

      (if parsed-json
        (assoc parsed-json :immersive_html (build-immersive-html (:paragraphs parsed-json)))
        nil))
    (catch Exception e
      (log/error "Immune Review failed:" (.getMessage e))
      nil)))
