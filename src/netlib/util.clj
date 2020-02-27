(ns netlib.util
  (:require [taoensso.timbre :as log]
            [clojure.spec.alpha :as s])
  (:import (java.net Proxy InetSocketAddress)
           java.net.Proxy$Type))

(s/def :proxy/host string?)
(s/def :proxy/port int?)
(s/def :proxy/type #{:http :socks})
(s/def ::proxy-spec (s/keys :req-un [:proxy/host :proxy/port :proxy/type]))

(defn gen-proxy
  "根据proxy配置返回一个Proxy对象"
  [proxy]
  {:pre [(s/valid? ::proxy-spec proxy)]}
  (-> (case (:type proxy)
        :http Proxy$Type/HTTP
        :socks Proxy$Type/SOCKS
        (do
          (log/warn :gen-proxy "unsupport type:" (:type proxy) "use non proxy")
          Proxy$Type/DIRECT))
      (Proxy. (InetSocketAddress. (:host proxy) (:port proxy)))))
