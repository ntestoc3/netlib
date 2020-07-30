(ns netlib.util
  (:require [taoensso.timbre :as log]
            [clojure.java.shell :refer [sh]]
            [clojure.spec.alpha :as s]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as codecs]
            [clojure.string :as str])
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

(defn shell
  [& args]
  (log/info "shell run: [ " (str/join " " args) " ]")
  (apply sh args))

(defn md5-str
  [input]
  (-> (hash/md5 input)
      codecs/bytes->hex))

(defn page-seq
  "分页请求
  `page-fn` 请求数据的函数
  `args` 传递给page-fn的参数
  `next-arags-fn` 接受(page-fn args)的结果，并生成下一次page-fn的函数
  `delay` 延时，单位为毫秒
  "
  ([page-fn args next-args-fn] (page-seq page-fn args next-args-fn nil))
  ([page-fn args next-args-fn delay]
   (lazy-seq
    (let [result (page-fn args)]
      (when result
        (cons result
              (do
                (when delay (Thread/sleep delay))
                (when-let [next-args (next-args-fn result)]
                  (page-seq page-fn
                            next-args
                            next-args-fn
                            delay)))))))))
