(ns netlib.bytes-test
  (:require [netlib.bytes :refer :all]
            [midje.sweet :refer :all]
            [octet.core :as buf]))

(fact "byte shift"
      (byte-shift-left 1 0x10) => 0x1000
      (byte-shift-left 2 0x1234) => 0x340000
      (byte-shift-left 1 0x567890) => 0x9000

      )

(fact "bytes num convert"
      (buf/with-byte-order :little-endian
        (bytes->num (byte-array [1 2 3 4])) => 0x4030201
        (bytes->num (byte-array [5 6 7 8])) => 0x8070605
        (vec (num->bytes 0x30201 3)) =>  (vec (byte-array [1 2 3]))

        )

      (buf/with-byte-order :big-endian
        (bytes->num (byte-array [1 2 3 4])) => 0x1020304
        (bytes->num (byte-array [5 6 7 8])) => 0x5060708
        (vec (num->bytes 0x567890 3)) => (vec (byte-array [0x56 0x78 0x90]))

        ))

(fact "medium num spec"
      (buf/size medium-spec) => 3
      (let [buffer (buf/allocate 24)]
        (buf/write! buffer 0x32 medium-spec {:offset 4}) => 3
        (buf/write! buffer 0x1234 medium-spec) => 3

        (buf/read buffer medium-spec) => 0x1234
        (buf/read buffer medium-spec {:offset 4}) => 0x32

        )
      )
