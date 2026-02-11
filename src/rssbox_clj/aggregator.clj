(ns rssbox-clj.aggregator
  (:require [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clj-http.client :as http]
            [rssbox-clj.db :as db]
            [rssbox-clj.config :as config]
            [rssbox-clj.processor :as proc]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop <! timeout]])
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
      ;; Fallback urls just in case
      ["https://www.jeffgeerling.com/blog.xml"])))

;; --- Feed Fetching ---
(defn fetch-feed [url]
  (try
    (let [resp (http/get url {:as :stream :socket-timeout 6000 :conn-timeout 4000})
          feed (.build (SyndFeedInput.) (XmlReader. (:body resp)))]
      feed)
    (catch Exception e
      (log/warn "Skip feed:" url) ;; 简化日志，避免刷屏
      nil)))

;; --- 核心逻辑: Entry -> JSON Map ---
(defn entry->map [^SyndEntry entry]
  (let [link (.getLink entry)
        title (.getTitle entry)
        published (.getPublishedDate entry)
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
        (proc/submit-task link title)
        {:id link
         :url link
         :title (str title " ⏳[Translating...]")
         :content_html original-html ;; 先展示原文，下次刷新就有翻译了
         :date_published published}))))

(defn update-aggregated-feed []
  (log/info ">>> Aggregator cycle started...")
  (let [urls (parse-opml)
        ;; 并行抓取所有 Feeds
        feeds (pmap fetch-feed urls)
        ;; 展平所有文章
        all-entries (mapcat #(if % (.getEntries %) []) feeds)
        ;; 按时间排序，取前 50 篇
        sorted (take 50 (sort-by #(.getPublishedDate ^SyndEntry %) #(compare %2 %1) all-entries))
        ;; 转换为 JSON Map
        json-items (map entry->map sorted)]

    (reset! latest-feed-data
            {:version "https://jsonfeed.org/version/1.1"
             :title "Hacker News Blogs (AI Translated)"
             :home_page_url (config/get-config :public-url "http://localhost:3000")
             :feed_url (str (config/get-config :public-url "http://localhost:3000") "/feed")
             :items json-items})
    (log/info ">>> Feed updated with" (count json-items) "items.")))

;; --- Scheduler ---
(defn start-scheduler! []
  ;; 启动后立即运行一次
  (future (update-aggregated-feed))
  ;; 定时循环
  (go-loop []
    (<! (timeout (* 30 60 1000))) ;; 30分钟
    (try
      (update-aggregated-feed)
      (catch Exception e (log/error e "Aggregator loop error")))
    (recur)))

(defn get-feed [] @latest-feed-data)
