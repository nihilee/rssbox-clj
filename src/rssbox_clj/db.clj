(ns rssbox-clj.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def db-spec {:dbtype "sqlite"
              :dbname "rssbox_cache.db"
              ;; 关键：添加这些参数以启用 WAL 模式
              :busy_timeout 5000})

(def ds (jdbc/get-datasource db-spec))

(defn init-db! []
  ;; 显式开启 WAL 模式
  (jdbc/execute! ds ["PRAGMA journal_mode=WAL;"])
  (jdbc/execute! ds ["
    CREATE TABLE IF NOT EXISTS article_cache (
      url TEXT PRIMARY KEY,
      title TEXT,
      content TEXT,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
  "]))

(defn get-cache [url]
  (jdbc/execute-one! ds ["SELECT content FROM article_cache WHERE url = ?" url]
                     {:builder-fn rs/as-unqualified-maps}))

(defn save-cache! [url title content]
  (jdbc/execute! ds ["INSERT OR REPLACE INTO article_cache (url, title, content, updated_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                     url title content]))
