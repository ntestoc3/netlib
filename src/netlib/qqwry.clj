(ns netlib.qqwry
  (:require [taoensso.timbre :as log]
            [octet.core :as buf]
            [netlib.bytes :as bytes-util]
            [common.fs-ext :as fs-ext]
            [netlib.ip :as ip]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io])
  (:import [java.io File RandomAccessFile]
           [java.nio.channels FileChannel$MapMode]))

(defn make-readonly-file-map
  "创建只读文件映射"
  [file]
  (let [rf (RandomAccessFile. file "r")
        file-c (.getChannel rf)]
    (.map file-c FileChannel$MapMode/READ_ONLY 0 (.size file-c))))

(def data-file "qqwry.dat")
(fs-ext/extract-resource! data-file)
(def qq-wry (make-readonly-file-map (io/file data-file)))

(defn- read-qq-wry
  [spec offset]
  (buf/with-byte-order :little-endian
    (buf/read qq-wry spec {:offset offset})))

(defn- read-qq-wry*
  [spec offset]
  (buf/with-byte-order :little-endian
    (buf/read* qq-wry spec {:offset offset})))

(def offset-spec (buf/spec :first buf/uint32
                           :last buf/uint32))

(def ip-record-spec (buf/spec :ip buf/uint32
                              :offset bytes-util/medium-spec))

(def t (read-qq-wry offset-spec 0))
(def ip-count (inc (/ (- (:last t) (:first t)) 7)))

(defn- ip-index-offset
  [pos]
  (+ (:first t) (* pos (buf/size ip-record-spec))))

(defn- bsearch
  "通过二分搜索ip,返回ip索引"
  ([ip] (bsearch ip 0 (dec ip-count)))
  ([ip low high]
   (if (<= (- high low) 1)
     (let [offset (ip-index-offset low)]
       (read-qq-wry ip-record-spec offset))
     (let [middle (quot (+ low high) 2)
           offset (ip-index-offset middle)
           ip-index (read-qq-wry buf/uint32 offset)]
       ;(log/info "low:" low " mid:" middle " high:" high " ip:" ip-index)
       (if (<= ip ip-index)
         (recur ip low middle)
         (recur ip middle high))))))

(defn- get-area-addr
  "获得区域信息字符串"
  [offset]
  (let [b (read-qq-wry buf/ubyte offset)]
    (if (#{1 2} b)
      (-> (read-qq-wry bytes-util/medium-spec (+ 1 offset))
          get-area-addr)
      (read-qq-wry (bytes-util/cstring "GBK") offset))))

(def country-and-area (buf/repeat 2 (bytes-util/cstring "GBK")))
;; 用spec包装更好，不过write需要搜集所有区域字符串才可以
(defn- get-address
  "获取国家 区域信息"
  [offset]
  (case (read-qq-wry buf/ubyte offset)
    1 (get-address (read-qq-wry bytes-util/medium-spec (+ 1 offset)))
    2 (let [c-area (-> (read-qq-wry bytes-util/medium-spec (+ 1 offset))
                       get-area-addr)
            a-area (get-area-addr (+ 4 offset))]
        {:county c-area
         :area a-area})
    ;; 第2个cstring有可能是mode 1或2
    (let [[len c-area] (read-qq-wry* (bytes-util/cstring "GBK") offset)
          a-area (get-area-addr (+ offset len))]
      {:county c-area
       :area a-area})))

(defn- get-address-offset
  [ip]
  (+ 4 (:offset (bsearch ip))))

(defn get-location
  "根据ip字符串获得区域信息{:contry \"所属国家\"
                        :area \"区域信息\"}"
  [ip]
  (-> (ip/ip->int ip)
      get-address-offset
      get-address))

