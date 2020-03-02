(ns netlib.whois
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [common.wrap :refer [with-exception-default]]
            [netlib.util :refer [gen-proxy]]
            [camel-snake-kebab.core :refer :all]
            [taoensso.timbre :as log]
            [java-time]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [common.fs-ext :as fs-ext])
  (:import [org.apache.commons.net.whois WhoisClient]
           (java.net Proxy InetSocketAddress)
           java.net.Proxy$Type
           [java.io BufferedReader StringReader]))

(def iana-whois-server "whois.iana.org")

(defn- query
  "Wraps WhoisClient.query.
  whois-server is the whois server you want to query
  url is the domain name you want to look up

  whois-server 用于查询whois信息的whois服务器
  proxy为代理, 参考`::util/proxy-spec` "
  [url {:keys [whois-server conn-timeout default-timeout proxy]
        :or {whois-server  WhoisClient/DEFAULT_HOST
             conn-timeout 5000
             default-timeout 8000}}]
  (log/info :debug :query url "with whois-server:" whois-server)
  (let [wis (doto (WhoisClient.)
              (.setConnectTimeout conn-timeout)
              (.setDefaultTimeout default-timeout))]
    (when proxy
      (.setProxy wis (gen-proxy proxy)))
    (.connect wis whois-server)
    (let [ret (try (. wis query url)
                   (catch java.io.IOException ex
                     (log/error :whois-query url)
                     "Failed"))]
      (.disconnect wis)
      ret)))

(defn- parse-iana-response
  "Parse the response from iana-whois-server"
  [response]
  (let [whois (re-find #"whois:\s+(\S+)" response)
        domain (re-find #"domain:\s+(\S+)" response)
        status (re-find #"status:\s+(\S+)" response)]
    (if whois
      {:domain (peek domain)
       :whois (peek whois)
       :status (peek status)}
      nil)))

(def tld-to-whois-server-map (atom {}))
(def default-tld-file "tld-maps.edn")

(defn- get-whois-server-for-tld
  "Get the whois server for a given TLD."
  [tld]
  (if-let [whois-server (get @tld-to-whois-server-map tld)]
    whois-server
    (:whois (parse-iana-response (query tld {:whois-server iana-whois-server})))))

(defn update-tld-maps
  ([tlds] (update-tld-maps tlds default-tld-file))
  ([tlds out-file]
   ;; emacs set *print-length* to 100
   (let [tlds (->> (filter identity tlds)
                   (reduce (fn [result info]
                             (assoc result
                                    (str "." (str/lower-case (:domain info)))
                                    (str/lower-case (:whois info))))
                           {}))]
     (reset! tld-to-whois-server-map tlds)
     (binding [*print-length* nil]
       (->> (pr-str tlds)
            (spit out-file))))))

(defn update-tlds
  []
  (->> (with-open [rdr (io/reader "https://data.iana.org/TLD/tlds-alpha-by-domain.txt")]
         (let [lines (line-seq rdr)]
           (println (first lines))
           (doall (map (fn [tld]
                         (log/info :whois-get-tld tld)
                         (try
                           (parse-iana-response (query (str "." tld)
                                                       {:whois-server iana-whois-server}))
                           (catch Exception e
                             (log/error :whois-get-tld tld))))
                       (rest lines)))))
       update-tld-maps)
  (log/info :whois-update-tlds "over."))

(defn init-tlds!
  []
  (some->> (with-exception-default (update-tlds)
             (-> (fs-ext/file-open default-tld-file)
                 slurp
                 read-string))
           (reset! tld-to-whois-server-map)))

(defonce ___init___ (init-tlds!))

(defn- get-tld-from-url
  "Extract TLD from a URL"
  [url]
  (some-> (re-find #"\.\w+$" url)
          str/lower-case))

(defn- trans-result-field-type
  [result]
  (map (fn [[k v]]
         (cond
           (#{:name-server
              } k)
           [k v]

           (= :domain-status k)
           [k (map #(re-find #"^\w+" %) v)]
           ;; 日期格式不统一
           ;; (#{:updated-date
           ;;    :creation-date
           ;;    :registry-expiry-date
           ;;    } k)
           ;; [k (java-time/zoned-date-time (first v))]

           ;; (#{:expiration-time
           ;;    :registration-time
           ;;    } k)
           ;; [k (java-time/local-date-time "y-M-d H:m:s" (first v))]

           (= 1 (count v))
           [k (first v)]

           :else [k v]))
       result))

(defn- format-result
  "格式化查询结果到map"
  [r]
  (->> r
       (group-by first)
       (map (fn [[k v]] [(-> (->kebab-case-string k)
                             (str/replace #"/-" "|")
                             keyword)
                         (map second v)]))
       (trans-result-field-type)
       (into {})))

(defn- parse-result
  [result]
  (let [r1 (re-find #"(?s)(.*)>>>" result)
        r (if r1 (second r1) result)]
    (->> (re-seq #"(\S[\S ]+?):\s+(\S[\S ]+)" r)
         (map rest)
         format-result)))

(defn- get-whois-server
  [url]
  (-> (get-tld-from-url url)
      (get-whois-server-for-tld)))

(defn whois
  "`url` 要查询whois的域名
  `opts` 可选参数，:whois-server 指定从whois server查询
                 :conn-timeout 连接超时时间
                 :default-timeout 默认超时时间
                 :proxy 使用代理服务器"
  ([url] (whois url nil))
  ([url opts]
   (let [r (->> (if (:whois-server opts)
                  opts
                  (assoc opts :whois-server (get-whois-server url)))
                (query url)
                parse-result)
         new-whois-server (:registrar-whois-server r)]
     (if (and new-whois-server
              (not= new-whois-server (:whois-server opts)))
       (->> (assoc opts :whois-server new-whois-server)
            (whois url))
       r))))

;; ip whois
(defn- get-rir-from-ip
  [ip opts]
  (-> (query ip (assoc opts :whois-server iana-whois-server))
      parse-iana-response
      :whois))

(def arin-rir "whois.arin.net")

(defmulti rir-query
  {:arglists '([rir ip opts])}
  (fn [rir & _] rir))

(defn parse-rip-lines
  [rs]
  (some->> (map #(-> (re-find #"(\S[\S ]+?):\s+(\S[\S ]+)" %1)
                     rest)
                rs)
           (filter first)
           format-result))

(defn- str-filter-line
  "过滤字符串s中匹配re的行，或空行"
  [re s]
  (->> (line-seq (BufferedReader. (StringReader. s)))
       (filter #(not (or (empty? %1)
                         (re-find re %1))))))


(defmethod rir-query arin-rir
  [rir ip opts]
  (log/debug "rir server: " rir " query ip: " ip)
  (let [r (query (str "n " ip) (assoc opts :whois-server rir))]
    (-> (str-filter-line #"^#" r)
        parse-rip-lines)))

;; 其它几个地区注释都是%开头的
(defmethod rir-query :default
  [rir ip opts]
  (log/debug "rir: " rir " query ip: " ip)
  (let [r (query ip (assoc opts :whois-server rir))]
    (-> (str-filter-line #"^%" r)
        parse-rip-lines)))

(defn whois-ip
  "`ip` 要查询whois的ip地址字符串
  `opts` 可选参数，:conn-timeout 连接超时时间
                 :default-timeout 默认超时时间
                 :proxy 使用代理服务器"

  ([ip] (whois-ip ip nil))
  ([ip opts]
   (some-> (get-rir-from-ip ip opts)
           (rir-query ip opts))))
