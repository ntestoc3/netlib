(ns netlib.asn
  (:require [netlib.dns :refer [lookup]]
            [netlib.whois :as whois]
            [clojure.string :as str]
            [taoensso.timbre :as log]))


(defn whois-get
  [ip]
  (whois/query (str " -v " ip) {:whois-server "whois.cymru.com"}))

(defn cymru-query
  [ip]
  (let [ip-bytes (->> (java.net.InetAddress/getByName ip)
                      (.getAddress))
        target (if (= (count ip-bytes) 4)
                 (-> (map #(str (bit-and 0xff %)) ip-bytes)
                     reverse
                     vec
                     (conj "origin.asn.cymru.com"))
                 (-> (mapcat #(vector (bit-shift-right (bit-and 0xff %) 4) (bit-and % 0xf)) ip-bytes)
                     reverse
                     vec
                     (conj "origin6.asn.cymru.com")))]
    (str/join "." target)))

(defn get-group-name
  [s]
  (str/replace s #",.*$" ""))

(defn get-asn-group
  [asn]
  (let [q (str "AS" asn ".asn.cymru.com")]
    (some-> (lookup q {:type :txt})
            first
            :strings
            first
            (str/split #"\s+\|\s+")
            last
            get-group-name)))

(defn valid-asn-result
  [r]
  (re-matches #"^\d+ \|.*" (first (:strings r))))

(defn get-asn
  [ip]
  (let [info (some-> (cymru-query ip)
                     (lookup {:type :txt})
                     (->> (filter valid-asn-result))
                     first
                     :strings
                     first
                     (str/split #"\|")
                     (->> (map str/trim))
                     vec
                     (->> (zipmap [:asn :asn-cidr :county-code :registry :allocated])))]
    (when info
      (assoc info :org (get-asn-group (:asn info))))))

(comment
  (lookup "8.8.8.8.origin.asn.cymru.com" {:type :txt
                                          :resolve "223.5.5.5"
                                          })


  (lookup "as15169.asn.cymru.com" {:type :txt})

  )
