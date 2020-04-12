(defproject ntestoc3/netlib "0.3.3-SNAPSHOT"
  :description "net work utils"
  :url "https://github.com/ntestoc3/netlib"
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                      :username :env/clojars_user
                                      :password :env/clojars_pass
                                      :sign-releases false}]]

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [ntestoc3/common "2.1.4-SNAPSHOT"]

                 [buddy/buddy-core "1.6.0"]                    ; crypt
                 [camel-snake-kebab/camel-snake-kebab "0.4.1"] ;; name convert
                 [cheshire/cheshire "5.10.0"]                  ;; json
                 [clj-http "3.10.0"]
                 [clojure.java-time "0.3.2"] ; datetime
                 [com.cemerick/url "0.1.1"]  ; uri java.net

                 [com.taoensso/timbre "4.10.0"]   ;; better logging
                 [commons-net "3.6"]              ;; whois client
                 [dnsjava/dnsjava "3.0.2"] ;; dns client
                 [funcool/octet "1.1.2"]          ;; bytes buffer
                 [me.raynes/fs "1.4.6"]           ;; file utility
                 [org.clojure/data.zip "0.1.3"]
                 [org.slf4j/slf4j-simple "1.7.30"]
                 [reaver/reaver "0.1.2"] ;html parser

                 ]
  :profiles {:dev {:dependencies [[midje "1.9.9" :exclusions [org.clojure/clojure]]
                                  ]
                   :plugins [[lein-midje "3.2.1"]]}}
  )
