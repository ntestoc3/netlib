(defproject ntestoc3/netlib "0.3.7"
  :description "net work utils"
  :url "https://github.com/ntestoc3/netlib"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                      :username :env/clojars_user
                                      :password :env/clojars_pass
                                      :sign-releases false}]]
  :pedantic? false

  :dependencies [[org.clojure/clojure "1.11.1"]
                 [ntestoc3/common "2.1.7"]
                 [buddy/buddy-core "1.11.423"]                    ; crypt
                 [camel-snake-kebab/camel-snake-kebab "0.4.3"] ;; name convert
                 [cheshire/cheshire "5.12.0"]                  ;; json
                 [clj-http "3.12.3"]
                 [clojure.java-time "1.4.2"] ; datetime
                 [com.cemerick/url "0.1.1"]  ; uri java.net

                 [org.flatland/ordered "1.15.11"]

                 [com.taoensso/timbre "6.3.1"]   ;; better logging
                 [commons-net "3.10.0"]              ;; whois client
                 [dnsjava/dnsjava "3.5.3"] ;; dns client
                 [funcool/octet "1.1.2"]          ;; bytes buffer
                 [me.raynes/fs "1.4.6"]           ;; file utility
                 [org.clojure/data.zip "1.0.0"]
                 [org.slf4j/slf4j-simple "2.0.9"]
                 [reaver/reaver "0.1.3"] ;html parser

                 ]
  :profiles {:dev {:dependencies [[midje "1.10.9" :exclusions [org.clojure/clojure]]
                                  ]
                   :plugins [[lein-midje "3.2.1"]]}}
  )
