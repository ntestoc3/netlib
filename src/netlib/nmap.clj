(ns netlib.nmap
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.zip.xml :refer [xml-> attr text]]
            [clojure.zip :as zip]
            [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [me.raynes.fs :as fs]
            [taoensso.timbre :as log]))

(defn masscan
  [ip {:keys [rate port out-fname]
       :or {rate 100000
            port "0-65535"}}]
  (sh "masscan" "-p" port "-oX" out-fname "--rate" (str rate) ip))

(defn nmap
  [ip {:keys [port out-fname]
       :or {port "0-65535"}}]
  (sc.api/spy (sh "nmap" "-v0" "-Pn" "--open"
       "-p" port
       "-sT" "-sV" "-oX" out-fname
       ip)))

(defn read-nmap-xml
  [fname]
  (-> fname xml/parse zip/xml-zip))

(defn parse-port-servces
  [port-xml]
  (let [port (attr port-xml :portid)
        proto (attr port-xml :protocol)
        state (-> (xml-> port-xml
                         :state
                         (attr :state))
                  first)
        service (some-> (xml-> port-xml
                               :service)
                        first
                        (as-> srv
                            {:type (attr srv :name)
                             :product (attr srv :product)
                             :finger-print (attr srv :servicefp)
                             :version (attr srv :version)
                             :extra (attr srv :extrainfo)
                             :device-type (attr srv :devicetype)}))]
    {:port port
     :proto proto
     :status state
     :service service}))

(defn parse-ip-services
  [xml]
  (->> (for [host (xml-> xml :nmaprun :host)]
         (let [addrs (xml-> host
                            :address
                            (attr :addr))
               hostname (xml-> host
                               :hostnames
                               :hostname
                               (attr :name))
               ports (xml-> host
                            :ports
                            :port)]
           {:ip (first addrs)
            :host (first hostname)
            :ports (mapv parse-port-servces ports)}))
       (group-by :ip)
       (map (fn [[ip v]] {:ip ip
                          ;; 这里host只取第一个结果
                          :host (-> v first :host)
                          :ports (mapcat :ports v)}))))

(defn parse-nmap-xml
  "解析nmap xml结果文件"
  [fname]
  (-> (read-nmap-xml fname)
      parse-ip-services))

(defn get-ports
  [ip-info]
  (some->> ip-info
           :ports
           (mapv :port)))

(defn run-masscan
  ([ip] (run-masscan ip nil))
  ([ip opts]
   (let [;; masscan的输出都在stderr中
         out-fname (str "masscan_" ip ".xml")
         mass-r (masscan ip (assoc opts :out-fname out-fname))]
     (cond
       (not (zero? (:exit mass-r)))
       (log/error :run-masscan ip "return code" (:exit mass-r) (:err mass-r))

       (empty? (slurp out-fname))
       (log/warn :run-masscan "no result for" ip "info:" (:err mass-r))

       :else
       (parse-nmap-xml out-fname)))))

(defn run-nmap
  ([ip] (run-nmap ip nil))
  ([ip opts]
   (let [out-fname (get opts :out-fname (str "nmap_" ip ".xml"))
         nmap-r (nmap ip (assoc opts :out-fname out-fname))]
     (if (zero? (:exit nmap-r))
       (parse-nmap-xml out-fname)
       (log/error :run-nmap ip "return code" (:exit nmap-r) (:err nmap-r))))))

(defn services-scan
  "对ip进行服务扫描，ip格式同masscan+nmap
  可以是单个ip: 192.168.0.0
  或者多个ip: 192.168.0.0,192.168.0.1
  或者范围: 192.168.0.0-192.168.0.20,192.168.0.0/24 "
  ([ip] (services-scan ip nil))
  ([ip opts]
   (some->> (run-masscan ip opts)
            (mapcat  #(run-nmap (:ip %1)
                                (assoc opts :port (get-ports %1)))))))
