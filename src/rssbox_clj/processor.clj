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

;; --- 升级版审稿人 Prompt ---
(def reviewer-sys-prompt
  "你是一个生物信息学和肿瘤学领域的资深科研专家。
   请根据提供的文章信息判断其是否值得阅读。
   
   你的任务：
   判断是否推荐 (recommend)。
   如果推荐，请将摘要完整翻译成中文 (abstract_cn)。
   提取核心创新点 (reason)。

   输出 JSON 格式：
   {
     \"recommend\": boolean,
     \"title_cn\": \"中文标题\",
     \"reason\": \"一句话推荐理由 (例如：引用百分位高达95，DeepMind最新发布的基因组模型)\",
     \"abstract_cn\": \"完整的中文摘要翻译，保持学术严谨性\",
     \"tags\": [\"关键词1\", \"关键词2\"]
   }
   
   如果 recommend 为 false，其他字段可以为空字符串。
   ")

(defn review-abstract [{:keys [title abstract journal score institution topic authors percentile]}]
  (try
    (let [user-content (format
                        "标题：%s\n作者：%s\n机构：%s\n领域主题 (Topic)：%s\n期刊/来源：%s (2yr Score: %.1f)\n引用百分位 (Percentile)：%.1f (满分100)\n摘要：%s"
                        title
                        (or authors "Unknown")
                        (or institution "Unknown")
                        (or topic "Unknown")
                        journal
                        (float (or score 0.0))
                        (float (or percentile 0.0))
                        abstract)

          body {:model model
                :messages [{:role "system" :content reviewer-sys-prompt}
                           {:role "user" :content user-content}]
                :temperature 1.0 ;; 降低温度，减少幻觉
                :response_format {:type "json_object"}}

          resp (http/post api-url
                          {:headers {"Authorization" (str "Bearer " api-key)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string body)
                           :socket-timeout 60000
                           :conn-timeout 10000})

          raw-content (-> (json/parse-string (:body resp) true) :choices first :message :content)]

      (json/parse-string raw-content true))

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