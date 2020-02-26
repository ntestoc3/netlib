(ns netlib.config)


(def config-define
  {:whois-proxy {:nested {:host {:description "proxy host"
                                 :type :string}
                          :port {:type :number
                                 :description "proxy port"}
                          :type {:type :keyword
                                 :one-of [:http :socks]
                                 :default :http
                                 :description "proxy type, :http or :socks"}}}})
