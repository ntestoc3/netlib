(ns netlib.dns
  (:import
   (java.net InetAddress Inet4Address Inet6Address)
   (org.xbill.DNS Address Type Lookup
                  Record ARecord AAAARecord
                  CNAMERecord DSRecord MXRecord
                  NSRecord PTRRecord SOARecord
                  TXTRecord TXTBase ExtendedResolver
                  SimpleResolver)
   (org.xbill.DNS.spi DNSJavaNameServiceDescriptor DNSJavaNameService))
  (:require [taoensso.timbre :as log]
            [clojure.set :as set]))

(def keyword-type
  [[:a Type/A]
   [:a6 Type/A6]
   [:aaaa Type/AAAA]
   [:afsdb Type/AFSDB]
   [:any Type/ANY]
   [:apl Type/APL]
   [:atma Type/ATMA]
   [:axfr Type/AXFR]
   [:cert Type/CERT]
   [:cname Type/CNAME]
   [:dhcid Type/DHCID]
   [:dlv Type/DLV]
   [:dname Type/DNAME]
   [:dnskey Type/DNSKEY]
   [:ds Type/DS]
   [:eid Type/EID]
   [:gpos Type/GPOS]
   [:hinfo Type/HINFO]
   [:ipseckey Type/IPSECKEY]
   [:isdn Type/ISDN]
   [:ixfr Type/IXFR]
   [:key Type/KEY]
   [:kx Type/KX]
   [:loc Type/LOC]
   [:maila Type/MAILA]
   [:mailb Type/MAILB]
   [:mb Type/MB]
   [:md Type/MD]
   [:mf Type/MF]
   [:mg Type/MG]
   [:minfo Type/MINFO]
   [:mr Type/MR]
   [:mx Type/MX]
   [:naptr Type/NAPTR]
   [:nimloc Type/NIMLOC]
   [:ns Type/NS]
   [:nsap Type/NSAP]
   [:nsap-ptr Type/NSAP_PTR]
   [:nsec Type/NSEC]
   [:nsec3 Type/NSEC3]
   [:nsec3param Type/NSEC3PARAM]
   [:null Type/NULL]
   [:nxt Type/NXT]
   [:opt Type/OPT]
   [:ptr Type/PTR]
   [:px Type/PX]
   [:rp Type/RP]
   [:rrsig Type/RRSIG]
   [:rt Type/RT]
   [:sig Type/SIG]
   [:soa Type/SOA]
   [:spf Type/SPF]
   [:srv Type/SRV]
   [:sshfp Type/SSHFP]
   [:tkey Type/TKEY]
   [:tlsa Type/TLSA]
   [:tsig Type/TSIG]
   [:txt Type/TXT]
   [:uri Type/URI]
   [:wks Type/WKS]
   [:x25 Type/X25]
   ])

(def keyword-type-table (into {} keyword-type))
(def type-keyword-table (set/map-invert keyword-type-table))
(defn get-lookup-type
  [^clojure.lang.Keyword kw]
  (get keyword-type-table kw))
(defn get-kw-from-lookup-type
  [^Integer look-type]
  (get type-keyword-table look-type))

(defn invoke-protected-method
  "Hack to work around https://dev.clojure.org/jira/browse/CLJ-1243"
  ([java-obj meth-name]
   (let [m (.getDeclaredMethod (class java-obj) meth-name (into-array Class []))]
     (.setAccessible m true)
     (.invoke m java-obj nil)))

  ([klass java-obj meth-name]
   (let [m (.getDeclaredMethod klass meth-name (into-array Class []))]
     (.setAccessible m true)
     (.invoke m java-obj nil))))

(defn convert-rec
  [^Record rec]
  (let [rec-type (get-kw-from-lookup-type (.getType rec))
        address  ()]
    {:type rec-type #_:class #_ (class rec)
     :name (.toString (.getName rec))
     :ttl (.getTTL rec)}))

(defprotocol ConvertibleToKWHashProtocol
  "Convertible to a keyword hash"
  (convert [a] "Convert data type to a keyword hash"))

(extend-type ARecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :address (.getHostAddress (.getAddress o)))))

(extend-type AAAARecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :address (.getHostAddress (.getAddress o)))))

(extend-type CNAMERecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :alias (.toString (.getAlias o))
                      :target (.toString (.getTarget o)))))

(extend-type MXRecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :addl-name (.toString (.getAdditionalName o))
                      :priority (.getPriority o)
                      :target (.toString (.getTarget o)))))

(extend-type PTRRecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :target (.toString (.getTarget o)))))

(extend-type SOARecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :admin (.toString (.getAdmin o))
                      :expire (.getExpire o)
                      :host (.toString (.getHost o))
                      :minimum (.getMinimum o)
                      :refresh (.getRefresh o)
                      :retry (.getRetry o)
                      :serial (.getSerial o))))

(extend-type TXTRecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :strings (invoke-protected-method TXTBase o "getStrings"))))

(extend-type NSRecord
  ConvertibleToKWHashProtocol
  (convert [o] (assoc (convert-rec o)
                      :target (.toString (.getTarget o)))))
(extend-type Record
  ConvertibleToKWHashProtocol
  (convert [o] (convert-rec o)))

(defn lookup
  "查找dns
  `domain` 要查找的域名

  可选的option:
  :type 查找的类型:a :mx 等,默认为:a
  :resolve 使用的解析服务器
  :extend-resolves 使用一组解析服务器

  如果无法解析，返回{:error 错误信息}
  "
  ([domain] (lookup domain nil))
  ([domain {:keys [resolve
                   extend-resolves
                   type]
            :or {type :a}}]
   (let [look-type (get-lookup-type type)
         l (Lookup. domain look-type)]
     (.setCache l nil) ;; no cache
     (when resolve
       (log/trace "set resolve:" resolve)
       (->> resolve
            SimpleResolver.
            (.setResolver l)))
     (when extend-resolves
       (log/trace "set resolves:" extend-resolves)
       (->> extend-resolves
            (into-array  String)
            ExtendedResolver.
            (.setResolver l)))
     (let [r (.run l)
           r-code (.getResult l)]
       (if (= 0 r-code)
         (mapv convert r)
         {:error (.getErrorString l)})))))

(def dns-service (.createNameService (DNSJavaNameServiceDescriptor.)))
(defn rev-lookup
  "Lookup hostname by ip address

   (rev-lookup \"8.8.8.8\")
  "
  [ip-address]
  (->> (Address/getByAddress ip-address)
       .getAddress
       (.getHostByAddr dns-service)))

;; (lookup "bing.com" :a :resolve "8.8.8.8")
;; (lookup "silisili.cn" :soa :extend-resolves ["8.8.8.8"
;;                                                      "4.2.2.6"
;;                                                      "4.2.2.1"
;;                                                      "4.2.2.3"])
;; (rev-lookup "4.2.2.2")
;;(lookup "baidu.cn" :ns :resolve "8.8.8.8")
