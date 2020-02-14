(ns netlib.qqwry
  (:require [taoensso.timbre :as log]
            [octet.core :as buf]
            [octet.buffer :as buffer]
            [octet.spec :as spec]
            [octet.util :as util]
            [common.fs-ext :as fs-ext]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io])
  (:import [java.io File RandomAccessFile]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel$MapMode]
           [java.net InetAddress Inet4Address UnknownHostException]))

(defn- map-read-file-buffer
  [file]
  (let [rf (RandomAccessFile. file "r")
        file-c (.getChannel rf)]
    (.map file-c FileChannel$MapMode/READ_ONLY 0 (.size file-c))))

(defn- fix-endian
  "修正字节数组bs的endian"
  [bs]
  (case buffer/*byte-order*
    :big-endian (reverse bs)
    :little-endian bs))

(defn- byte-shift-left
  [pos b]
  (let [bit-pos (* 8 pos)]
    (bit-and
     ;; 去除符号位的影响
     ;; 如果有一个负数，会变成fffffffff8000000这样的结果
     ;; 影响后面的bit-and结果
     (bit-shift-left 0xFF bit-pos)
     (bit-shift-left b bit-pos))) )

(defn- bytes->num
  "根据endian转换bytes到数字"
  [bs]
  (log/trace "bytes-> num:" bs)
  (->> (fix-endian bs)
       (map-indexed byte-shift-left)
       (apply bit-or)))

(defn- num-get-byte
  "获取数字n对应位置的字节"
  [pos n]
  (let [bit-pos (* 8 pos)]
    (bit-and (bit-shift-right n bit-pos)
             0xFF)) )

(defn- num->bytes
  "转换数字到字节数组，长度为size,
  如果size小于数字实际长度，会被截断"
  [n size]
  (->> (fix-endian (range size))
       (mapv #(num-get-byte %1 n))))

(def medium-spec
  "3字节整数"
  (reify
    spec/ISpecSize
    (size [_]
      ;;int24 3字节整数
      3)

    spec/ISpec
    (read [_ buff pos]
      (let [[readed b3] (spec/read (buf/bytes 3) buff pos)]
        [readed (bytes->num b3)]))
    (write [_ buff pos n]
      (let [writen (spec/write (buf/bytes 3) buff pos (num->bytes n 3))]
        writen))))

(defn- read-until
  [buff pos & {:keys [value]
               :or {value 0}}]
  (loop [r []
         index 0]
    (let [b (buffer/read-ubyte buff (+ pos index))]
      (if (= b value)
        [index (byte-array r)]
        (recur (conj r b) (inc index))))))

(defn- cstring
  "\\0结束的字符串,c字符串"
  [encoding]
  (reify
    spec/ISpecDynamicSize
    (size* [_ data]
      (let [data (.getBytes data encoding)]
        (+ 1 (count data)))) ; 1字节0结束符

    spec/ISpec
    (read [_ buff pos]
      (let [[size data] (read-until buff pos)
            data (String. data 0 size encoding)]
        [(+ 1 size) data]))

    (write [_ buff pos value]
      (let [input (.getBytes value encoding)
            length (count input)]
        (buffer/write-bytes buff pos length input)
        (buffer/write-byte buff (+ pos length) 0)
        (+ length 1)))))

(defn- bytes->buffer
  [s]
  (ByteBuffer/wrap (byte-array s)))

(def qq-wry (map-read-file-buffer (fs-ext/file-open "qqwry.dat")))

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
                              :offset medium-spec))

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

(defn ip->int
  "ip转换为整数表示"
  [ip]
  (buf/with-byte-order :big-endian
    (-> (InetAddress/getByName ip)
        .getAddress
        bytes->num)))

(defn int->ip
  "整数转换为ip表示"
  [n]
  (-> (num->bytes n 4)
      byte-array
      (InetAddress/getByAddress)
      .getHostAddress))

(defn local-ip?
  "是否为本地ip"
  [ip]
  (or (.isSiteLocalAddress ip)
      (.isAnyLocalAddress ip)
      (.isLinkLocalAddress ip)
      (.isLoopbackAddress ip)
      (.isMulticastAddress ip)))

(defn valid-local?
  "测试ip字符串或域名是否为本地网络ip!注意:会做名称解析"
  [ip-s]
  (try (-> (InetAddress/getByName ip-s)
           local-ip?)
       (catch UnknownHostException e false)))

(defn valid-global?
  "测试ip字符串或域名是否为公网ip,!注意:会做名称解析"
  [ip-s]
  (try (-> (InetAddress/getByName ip-s)
           local-ip?
           not)
       (catch UnknownHostException e false)))

(defn- get-area-addr
  "获得区域信息字符串"
  [offset]
  (let [b (read-qq-wry buf/ubyte offset)]
    (if (#{1 2} b)
      (-> (read-qq-wry medium-spec (+ 1 offset))
          get-area-addr)
      (read-qq-wry (cstring "GBK") offset))))

(def country-and-area (buf/repeat 2 (cstring "GBK")))
;; 用spec包装更好，不过write需要搜集所有区域字符串才可以
(defn- get-address
  "获取国家 区域信息"
  [offset]
  (case (read-qq-wry buf/ubyte offset)
    1 (get-address (read-qq-wry medium-spec (+ 1 offset)))
    2 (let [c-area (-> (read-qq-wry medium-spec (+ 1 offset))
                       get-area-addr)
            a-area (get-area-addr (+ 4 offset))]
        {:county c-area
         :area a-area})
    ;; 第2个cstring有可能是mode 1或2
    (let [[len c-area] (read-qq-wry* (cstring "GBK") offset)
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
  (-> (ip->int ip)
      get-address-offset
      get-address))

