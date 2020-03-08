(ns netlib.aquatone
  (:require [netlib.util :refer [shell]]
            [clojure.string :as string]))

(defn aquatone
  ([hosts] (aquatone hosts nil))
  ([hosts {:keys [threads
                  out-path
                  scan-timeout
                  http-timeout
                  ports
                  screenshot-timeout
                  extra-opts]
           :or {threads 2
                out-path "aquatone_out"
                http-timeout 30000
                scan-timeout 10000
                screenshot-timeout 180000
                ports "medium"}}]
   (->> (concat ["-silent"
                 "-out" out-path
                 "-http-timeout" (str http-timeout)
                 "-threads" (str threads)
                 "-scan-timeout" (str scan-timeout)
                 "-screenshot-timeout" (str screenshot-timeout)]
                extra-opts
                [:in hosts])
        (apply shell "aquatone"))))

(defn aquatone-report-file-path
  "根据aquatone的结果目录获取report文件路径"
  [dir]
  (str dir "/aquatone_report.html"))

(comment
  (aquatone "www.hackerone.com" {:ports "80,443"})

 )
