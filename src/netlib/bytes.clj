(ns netlib.bytes
  (:require [taoensso.timbre :as log]
            [octet.core :as buf]
            [octet.buffer :as buffer]
            [octet.spec :as spec]
            [octet.util :as util]
            [clojure.java.io :as io]))

(defn- fix-endian
  "修正字节数组bs的endian"
  [bs]
  (case buffer/*byte-order*
    :big-endian (reverse bs)
    :little-endian bs))

(defn byte-shift-left
  "字节b向左移动pos个位置,后面补0，b只是一个字节,多余的忽略"
  [pos b]
  (let [bit-pos (* 8 pos)]
    (bit-and
     ;; 去除符号位的影响
     ;; 如果有一个负数，会变成fffffffff8000000这样的结果
     ;; 影响后面的bit-and结果
     (bit-shift-left 0xFF bit-pos)
     (bit-shift-left b bit-pos))) )

(defn bytes->num
  "根据endian转换bytes到数字"
  [bs]
  (log/trace "bytes-> num:" bs)
  (->> (fix-endian bs)
       (map-indexed byte-shift-left)
       (apply bit-or)))

(defn num-get-byte
  "获取数字n对应位置的字节"
  [pos n]
  (let [bit-pos (* 8 pos)]
    (bit-and (bit-shift-right n bit-pos)
             0xFF)) )

(defn num->bytes
  "转换数字到字节数组，长度为size,
  如果size小于数字实际长度，会被截断"
  [n size]
  (->> (fix-endian (range size))
       (map #(num-get-byte %1 n))
       byte-array))

(defn nbyte-num
  "n个字节的整数"
  [n]
  (reify
    spec/ISpecSize
    (size [_]
      n)

    spec/ISpec
    (read [_ buff pos]
      (let [[readed bn] (spec/read (buf/bytes n) buff pos)]
        [readed (bytes->num bn)]))
    (write [_ buff pos numb]
      (let [writen (spec/write (buf/bytes n) buff pos (num->bytes numb n))]
        writen))))

(def medium-spec "3字节整数" (nbyte-num 3))

(defn- read-until
  [buff pos & {:keys [value]
               :or {value 0}}]
  (loop [r []
         index 0]
    (let [b (buffer/read-ubyte buff (+ pos index))]
      (if (= b value)
        [index (byte-array r)]
        (recur (conj r b) (inc index))))))

(defn cstring
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
