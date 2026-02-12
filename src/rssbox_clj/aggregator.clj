(ns rssbox-clj.aggregator
  (:require [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clj-http.client :as http]
            [rssbox-clj.db :as db]
            [rssbox-clj.config :as config]
            [rssbox-clj.processor :as proc]
            [clojure.tools.logging :as log])
  (:import [com.rometools.rome.io SyndFeedInput XmlReader]
           [com.rometools.rome.feed.synd SyndEntry]))

;; --- State: 存储最终生成的 JSON 数据结构 (Map) ---
(defonce latest-feed-data (atom {}))

;; --- OPML Helper ---
(defn parse-opml []
  (try
    (with-open [r (io/reader (io/resource "hn-popular-blogs-2025.opml"))]
      (let [parsed (xml/parse r)
            find-urls (fn find-urls [node]
                        (if (= :outline (:tag node))
                          (if-let [url (:xmlUrl (:attrs node))]
                            [url]
                            (mapcat find-urls (:content node)))
                          (mapcat find-urls (:content node))))]
        (find-urls parsed)))
    (catch Exception e
      (log/error "OPML Parse Error:" (.getMessage e))
      ;; Fallback urls
      ["https://www.jeffgeerling.com/blog.xml"])))


;; --- Feed Fetching ---
(defn fetch-feed [url]
  (try
    (let [resp (http/get url {:as :stream
                              :headers {"User-Agent" config/user-agent}
                              :socket-timeout 10000
                              :conn-timeout 5000})
          feed (.build (SyndFeedInput.) (XmlReader. (:body resp)))]
      feed)
    (catch Exception e
      (log/warn (str "Fetch failed [" url "]: " (.getMessage e)))
      nil)))

;; --- Helper: 获取安全的日期 ---
;; 很多 Feed 没有 PublishedDate 但有 UpdatedDate，或者反之。
(defn get-safe-date [^SyndEntry entry]
  (or (.getPublishedDate entry)
      (.getUpdatedDate entry)
      ;; 如果都没有，返回 1970 年，确保排在最后，而不是报错
      (java.util.Date. 0)))

;; --- Helper: 按 URL 去重 ---
(defn distinct-by-link [entries]
  (let [seen (volatile! #{})]
    (filter (fn [^SyndEntry entry]
              (let [link (.getLink entry)]
                (if (or (nil? link) (contains? @seen link))
                  false
                  (do (vswap! seen conj link) true))))
            entries)))

;; --- 核心逻辑: Entry -> JSON Map ---
(defn entry->map [^SyndEntry entry]
  (let [link (.getLink entry)
        title (.getTitle entry)
        published (get-safe-date entry) ;; 使用安全日期
        ;; 获取原文内容 (优先取 Content，没有则取 Description)
        original-html (or (some-> entry .getContents first .getValue)
                          (some-> entry .getDescription .getValue)
                          "")]

    (if-let [cached (db/get-cache link)]
      ;; A. 命中缓存：直接使用翻译后的 HTML
      {:id link
       :url link
       :title title
       :content_html (:content cached)
       :date_published published}

      ;; B. 未命中：提交任务，返回带标记的原文
      (do
        ;; 只有真正进入 Top 50 的文章才会被提交翻译，节省资源
        (proc/submit-task link title)
        {:id link
         :url link
         :title (str title " ⏳[Translating...]")
         :content_html original-html
         :date_published published}))))

(defn update-aggregated-feed []
  (log/info ">>> Aggregator cycle started...")
  (try
    (let [urls (parse-opml)
          _ (log/info "Fetching" (count urls) "feeds in parallel...")

          ;; 1. 使用 future 并发抓取 IO
          feeds (->> urls
                     (map (fn [url] (future (fetch-feed url))))
                     (doall)
                     (map deref))

          ;; 2. 展平所有文章并剔除无效 Feed
          all-entries (mapcat #(when % (.getEntries %)) feeds)

          ;; 3. 全局排序 + 去重 + 截取
          ;;    必须先获取所有文章，才能知道哪 50 篇是全网最新的
          sorted-entries (->> all-entries
                              ;; 过滤掉没有标题或链接的坏数据
                              (filter #(and (.getLink %) (.getTitle %)))
                              ;; 按照安全日期倒序 (最新的在前)
                              (sort-by get-safe-date #(compare %2 %1))
                              ;; 根据 URL 去重 (防止不同源包含相同文章，或源自身重复)
                              (distinct-by-link)
                              ;; 只取前 50
                              (take 50))

          ;; 4. 转换为 JSON Map (在此步骤才触发翻译任务)
          json-items (mapv entry->map sorted-entries)] ;; 使用 mapv 立即求值

      (reset! latest-feed-data
              {:version "https://jsonfeed.org/version/1.1"
               :title "Hacker News Blogs (AI Translated)"
               :home_page_url (config/get-config :public-url "http://localhost:3000")
               :feed_url (str (config/get-config :public-url "http://localhost:3000") "/feed")
               :items json-items})

      (log/info ">>> Feed updated. Top item date:"
                (some-> json-items first :date_published)
                "| Total items:" (count json-items)))
    (catch Exception e
      (log/error e "Critical error in aggregator update"))))

;; --- Scheduler ---
(defn start-scheduler! []
  (log/info "Starting scheduler thread...")
  (future
    (loop []
      (try
        (update-aggregated-feed)
        (catch Exception e
          (log/error e "Scheduler loop crash")))
      ;; 使用 Thread/sleep 而不是 timeout，避免引入 async 依赖
      (log/info "Sleeping for 30 minutes...")
      (Thread/sleep (* 30 60 1000))
      (recur))))

(defn get-feed [] @latest-feed-data)
