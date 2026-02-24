(ns rssbox-clj.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.util.response :refer [response content-type status]]
            [rssbox-clj.aggregator :as agg]
            [rssbox-clj.fetcher :as fetcher]
            [rssbox-clj.immune-fetcher :as immune-fetcher]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]))

;; 这一行是为了让 Cheshire 能够正确序列化 Java Date 对象
(json-gen/add-encoder java.util.Date
                      (fn [c jsonGenerator]
                        (.writeString jsonGenerator (str (.toInstant c)))))

(defn wrap-cors [handler]
  (fn [request]
    (-> (handler request)
        (assoc-in [:headers "Access-Control-Allow-Origin"] "*"))))

(defn handle-feed []
  (let [data (agg/get-feed)]
    (if (empty? data)
      (-> (response (json/generate-string {:error "Initializing..."}))
          (status 503)
          (content-type "application/json"))
      (-> (response (json/generate-string data))
          (content-type "application/feed+json; charset=utf-8")))))

;; --- [新增] 处理 PubMed Feed ---
(defn handle-articles-feed []
  (let [data (fetcher/get-feed)]
    (if (empty? data)
      ;; 修复：补全 JSON Feed 必要字段
      (-> (response (json/generate-string
                     {:version "https://jsonfeed.org/version/1.1"
                      :title "AI Research Radar"
                      :items []}))
          (content-type "application/feed+json; charset=utf-8"))
      (-> (response (json/generate-string data))
          (content-type "application/feed+json; charset=utf-8")))))


;; --- [新增] 处理 Immune Feed ---
(defn handle-immune-articles-feed []
  (let [data (immune-fetcher/get-feed)]
    (if (empty? data)
      (-> (response (json/generate-string
                     {:version "https://jsonfeed.org/version/1.1"
                      :title "Immune Research Radar"
                      :items []}))
          (content-type "application/feed+json; charset=utf-8"))
      (-> (response (json/generate-string data))
          (content-type "application/feed+json; charset=utf-8")))))

(defroutes app-routes
  (GET "/feed" [] (handle-feed))
  (GET "/articles" [] (handle-articles-feed))
  (GET "/immune-articles" [] (handle-immune-articles-feed))
  (GET "/" [] {:status 200 :body "<h1>RSSBox Running (v2)</h1><p><a href='/feed'>/feed</a></p><p><a href='/articles'>/articles</a></p>"})
  (route/not-found "Not Found"))
