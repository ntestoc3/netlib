(ns netlib.ip
  (:require [taoensso.timbre :as log]
            [netlib.bytes :refer [bytes->num num->bytes]]
            [octet.core :as buf])
  (:import [java.net InetAddress Inet4Address UnknownHostException]))

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

