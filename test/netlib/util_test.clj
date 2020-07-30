(ns netlib.util-test
  (:require [netlib.util :refer :all :as util]
            [midje.sweet :refer :all]
            [clojure.spec.alpha :as s]))

(fact "proxy spec"
      (s/valid? ::util/proxy-spec {:host "127.0.0.1"
                                   :port 1234
                                   :type :https}) => false

      (s/valid? ::util/proxy-spec {:host "127.0.0.1"
                                   :port 1234
                                   :type :http}) => true

      (->> (gen-proxy {:host "127.0.0.1"
                       :port 1234
                       :type :socks})
           (instance? java.net.Proxy)) => true

      )
