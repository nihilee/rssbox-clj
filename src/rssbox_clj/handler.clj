(ns rssbox-clj.handler
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.util.response :refer [response content-type status]]
            [rssbox-clj.aggregator :as agg]
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

(defroutes app-routes
  (GET "/feed" [] (handle-feed))
  (GET "/" [] {:status 200 :body "<h1>RSSBox Running</h1><p><a href='/feed'>/feed</a></p>"})
  (route/not-found "Not Found"))
