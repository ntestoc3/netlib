(ns netlib.qqwry-test
  (:require [netlib.qqwry :refer :all]
            [midje.sweet :refer :all]
            [clojure.string :as str]))

(fact "ip location"
      (str/includes? (:area (get-location "8.8.8.8")) "谷歌") => true

      (= "局域网" (:county (get-location "127.0.0.1"))) => true

      )

(fact "local global test"
      (valid-local? "127.0.0.1") => true
      (valid-local? "8.8.8.8") => false

      (valid-global? "127.0.0.1") => false
      (valid-global? "8.8.8.8") => true
      (valid-global? "www.baidu.com") => true
      )
