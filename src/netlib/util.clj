(ns netlib.util
  (:require [taoensso.timbre :as log]
            [omniconf.core :as cfg])
  (:import (java.net Proxy InetSocketAddress)
           java.net.Proxy$Type))

(defn get-proxy
  "根据config项，返回一个java.net.Proxy
  如果找不到配置项则返回Proxy/NO_PROXY"
  [proxy-config]
  (if-let [proxy (cfg/get proxy-config)]
    (-> (case (:type proxy)
          :http Proxy$Type/HTTP
          :socks Proxy$Type/SOCKS
          (do
            (log/warn :get-proxy "unsupport type:" (:type proxy) "use non proxy")
            Proxy$Type/DIRECT))
        (Proxy. (InetSocketAddress. (:host proxy) (:port proxy))))
    Proxy/NO_PROXY))
