(defproject rssbox-clj "0.1.0-SNAPSHOT"
  :description "AI-Powered RSS Proxy"
  :url "http://localhost:3000"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ring/ring-core "1.9.6"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 [compojure "1.7.0"]
                 [cheshire "5.12.0"]
                 [clj-http "3.12.3"]
                 [com.github.seancorfield/next.jdbc "1.3.909"]
                 [org.xerial/sqlite-jdbc "3.45.3.0"]
                 [com.rometools/rome "2.1.0"]
                 [org.jsoup/jsoup "1.17.2"]
                 [net.dankito.readability4j/readability4j "1.0.8"]
                 [org.clojure/tools.logging "1.3.0"]
                 [org.slf4j/slf4j-simple "1.7.36"]
                 [org.clojure/core.async "1.6.681"]
                 [org.clojure/data.xml "0.0.8"]]
  :main ^:skip-aot rssbox-clj.core
  :uberjar-name "rssbox-clj.jar"
  :profiles {:uberjar {:aot :all}})
