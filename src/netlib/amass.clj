(ns netlib.amass
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [camel-snake-kebab.core :refer :all]
            [me.raynes.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.shell :refer [sh]]
            [taoensso.timbre :as log]))



(defn amass
  [domain json-out-fname]
  (sh "amass" "enum" "-v" "-src" "-json" json-out-fname "-d" domain))

(defn parse-amass-out
  "解析amass输出"
  [out]
  (some->> (string/split-lines out)
           (mapv (fn [line]
                   (let [[_ source domain] (re-find #"\[(.+)\]\s+(.+)" line)]
                     ;; 这里必须用:type和:result，为了和wrap-type的类型对应起来
                     (when (and source domain)
                       {:source (-> (str "amass-" source)
                                  ->kebab-case-keyword)
                        :domain domain}))))
           (filter identity)))

(defn parse-amass-json
  "解析amass输出的json文件"
  [json-out-file]
  (some->>  (slurp json-out-file)
            (string/split-lines)
            (map #(json/parse-string %1 keyword))))

(defn run-amass
  "如果不指定输出json的文件名，则默认保存为当前目录下amsss_`domain`.json"
  ([domain] (run-amass domain
                       {:output-json-path (str "amass_" domain ".json")}))
  ([domain {:keys [output-json-path]}]
   (let [amass-r (amass domain output-json-path)]
     (if (zero? (:exit amass-r))
       (do
         (log/warn :run-amass (:err amass-r))
         (parse-amass-out (:out amass-r)))
       (log/error :run-amass domain "return code" (:exit amass-r) (:err amass-r))))))
