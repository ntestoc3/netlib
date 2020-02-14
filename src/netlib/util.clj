(ns netlib.util
  (:require [common.config :as config]
            [taoensso.timbre :as log])
  (:import (java.net Proxy InetSocketAddress)
           java.net.Proxy$Type))

(defn get-proxy
  "根据config项，返回一个java.net.Proxy
  如果找不到配置项则返回Proxy/NO_PROXY"
  [proxy-config]
  (if-let [proxy (config/get-config proxy-config)]
    (-> (case (:type proxy)
          :http Proxy$Type/HTTP
          :socks Proxy$Type/SOCKS
          (do
            (log/warn :proxy-config "unsupport type:" (:type proxy) "use non proxy")
            Proxy$Type/DIRECT))
        (Proxy. (InetSocketAddress. (:host proxy) (:port proxy))))
    Proxy/NO_PROXY))
