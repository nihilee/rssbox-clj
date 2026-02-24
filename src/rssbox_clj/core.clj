(ns rssbox-clj.core
  (:require [rssbox-clj.handler :refer [app-routes wrap-cors]]
            [rssbox-clj.db :as db]
            [clojure.tools.logging :as log]
            [rssbox-clj.processor :as proc]
            [rssbox-clj.aggregator :as agg]
            [rssbox-clj.fetcher :as fetcher]
            [rssbox-clj.config :as config]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

(defn -main [& args]
  (log/info "=== RSSBox Starting ===")

  (db/init-db!)

  ;; 启动 3 个线程处理翻译 (Thread mode)
  (proc/start-workers! 3)

  ;; 启动聚合调度器
  (agg/start-scheduler!)

  (fetcher/start-scheduler!)

  (let [port (Integer/parseInt (str (config/get-config :port 8000)))]
    (log/info "Server running on http://localhost:" port)
    (jetty/run-jetty (-> app-routes
                         wrap-cors
                         wrap-params)
                     {:port port :join? true})))
