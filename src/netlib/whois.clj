(ns netlib.whois
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [common.wrap :refer [with-exception-default]]
            [common.config :as config]
            [netlib.util :refer [get-proxy]]
            [camel-snake-kebab.core :refer :all]
            [taoensso.timbre :as log]
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
  whois-endpoint is the whois server you want to query
  url is the domain name you want to look up

  Examples:
    (whois \"whois.nic.it\" \"google.it\")
    (whois \"whois.iana.org\" \"com.\") -- this gets the whois server for the given tld
  "
  ([url] (query WhoisClient/DEFAULT_HOST url))
  ([whois-endpoint url]
   (let [wis (doto (WhoisClient.)
               (.setProxy (get-proxy :whois-proxy))
               (.setConnectTimeout 5000)
               (.setDefaultTimeout 8000))]
     (.connect wis whois-endpoint)
     (let [ret (try (. wis query url)
                    (catch java.io.IOException ex
                      (log/warn :whois-query url ex)
                      "Failed"))]
       (.disconnect wis)
       ret))))

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
    (:whois (parse-iana-response (query iana-whois-server tld)))))

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
                           (parse-iana-response (query iana-whois-server (str "." tld)))
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
  (re-find #"\.\w+$" url))

(defn- format-result
  "格式化查询结果到map"
  [r]
  (->> r
       (group-by first)
       (map (fn [[k v]] [(->kebab-case-keyword k)
                         (map second v)]))
       (into {})))

(defn- parse-result
  [result]
  (let [r1 (re-find #"(?s)(.*)>>>" result)
        r (if r1 (second r1) result)]
    (->> (re-seq #"(\S[\S ]+?):\s+(\S[\S ]+)" r)
         (map rest)
         format-result)))

(defn whois
  [url]
  (let [tld (get-tld-from-url url)
        whois-server (get-whois-server-for-tld tld)]
    (log/info :whois url " with server:" whois-server)
    (if whois-server
      (-> (query whois-server url)
          parse-result)
      (log/error :whois "could not find whois server for TLD " tld))))

(defn get-name-servers
  "从whois结果中获取NS地址
  (-> (whois \"bing.com)
      get-name-servers)"
  [r]
  (map second (r "Name Server")))

;; ip whois
(defn- get-rir-from-ip
  [ip]
  (-> (query iana-whois-server ip)
      parse-iana-response
      :whois))

(def arin-rir "whois.arin.net")
(defn- arin-get-net
  [ip]
  (let [q (query arin-rir (str "n " ip))]
    (-> (re-seq #"(?s)\((NET[-\d]+)\)" q)
        last
        second)))

(defmulti rir-query
  {:arglists '([rir ip])}
  (fn [rir & _] rir))

(defn parse-rip-lines
  [rs]
  (->> (map #(-> (re-find #"(\S[\S ]+?):\s+(\S[\S ]+)" %1)
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
  [rir ip]
  (log/debug "rir server: " rir " query ip: " ip)
  (let [net (arin-get-net ip)
        r (query rir net)]
    (-> (str-filter-line #"^#" r)
         parse-rip-lines)))

;; 其它几个地区注释都是%开头的
(defmethod rir-query :default
  [rir ip]
  (log/debug "rir: " rir " query ip: " ip)
  (let [r (query rir ip)]
    (-> (str-filter-line #"^%" r)
        parse-rip-lines)))

(defn whois-ip
  [ip]
  (-> (get-rir-from-ip ip)
      (rir-query ip)))
