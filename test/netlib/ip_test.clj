(ns netlib.ip-test
  (:require [netlib.ip :refer :all]
            [midje.sweet :refer :all]))

(fact "local global test"
      (valid-local? "127.0.0.1") => true
      (valid-local? "8.8.8.8") => false

      (valid-global? "127.0.0.1") => false
      (valid-global? "8.8.8.8") => true
      (valid-global? "www.baidu.com") => true
      )

(fact "ip int converter"
      (ip->int "0.0.0.0") => 0
      (int->ip 0) => "0.0.0.0"

      (int->ip 0xffffffff) => "255.255.255.255"
      (int->ip 0x01020304) => "1.2.3.4"

      )

