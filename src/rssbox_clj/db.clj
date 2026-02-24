(ns rssbox-clj.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def db-spec {:dbtype "sqlite"
              :dbname "rssbox_cache.db"
              ;; 关键：添加这些参数以启用 WAL 模式
              :busy_timeout 5000})

(def ds (jdbc/get-datasource db-spec))

(defn init-db! []
  (jdbc/execute! ds ["PRAGMA journal_mode=WAL;"])
  (jdbc/execute! ds ["
    CREATE TABLE IF NOT EXISTS article_cache (
      url TEXT PRIMARY KEY,
      title TEXT,
      content TEXT,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
  "])
  ;; [新增] Immune 专属表
  (jdbc/execute! ds ["
    CREATE TABLE IF NOT EXISTS immune_article_cache (
      url TEXT PRIMARY KEY,
      title TEXT,
      content TEXT,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
  "]))

(defn get-immune-cache [url]
  (jdbc/execute-one! ds ["SELECT content FROM immune_article_cache WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-maps}))

(defn save-immune-cache! [url title content]
  (jdbc/execute! ds ["INSERT OR REPLACE INTO immune_article_cache (url, title, content, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                     url title content]))

(defn get-recent-immune-articles [limit]
  (jdbc/execute! ds ["SELECT url as id, url, title, content as content_html, updated_at as date_published
                      FROM immune_article_cache
                      WHERE title LIKE '⭐%' OR title LIKE '📄%'
                      ORDER BY updated_at DESC LIMIT ?" limit]
                 {:builder-fn rs/as-unqualified-maps}))


(defn get-cache [url]
  (jdbc/execute-one! ds ["SELECT content FROM article_cache WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-maps}))

(defn save-cache! [url title content]
  (jdbc/execute! ds ["INSERT OR REPLACE INTO article_cache (url, title, content, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                     url title content]))

(defn get-recent-articles [limit]
  (jdbc/execute! ds ["SELECT url as id, url, title, content as content_html, updated_at as date_published
                      FROM article_cache
                      WHERE title LIKE '⭐%' OR title LIKE '📄%'
                      ORDER BY updated_at DESC LIMIT ?" limit]
                 {:builder-fn rs/as-unqualified-maps}))

