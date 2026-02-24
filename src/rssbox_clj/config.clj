(ns rssbox-clj.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn- load-config-file []
  (try
    (if (.exists (io/file "secrets.edn"))
      (edn/read-string (slurp "secrets.edn"))
      {})
    (catch Exception e
      (log/warn "Config load error:" (.getMessage e))
      {})))

;; 使用 memoize 缓存配置，避免每次都读文件
(def read-config (memoize load-config-file))

(defn get-config
  ([key] (get (read-config) key))
  ([key default] (get (read-config) key default)))

(def user-agent "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

;; --- [新增] PubMed 相关配置 ---
(def pubmed-search-term
  (get-config :pubmed-query
              "(\"Bioinformatics\"[MeSH] OR \"Computational Biology\"[MeSH] OR \"Neoplasms\"[MeSH]) AND (2024/01/01:3000/12/31[Date - Publication])"))

(def pubmed-min-citedness
  (get-config :pubmed-min-score 3.0))