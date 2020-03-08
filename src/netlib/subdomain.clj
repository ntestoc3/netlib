(ns netlib.subdomain
  (:require [taoensso.timbre :as log]
            [cemerick.url :refer [url url-encode]]
            [reaver :as html]
            [clj-http.client :as http]
            [netlib.search :as se]
            [clj-http.cookies :as cookies]
            [clojure.string :as str]))

(defn ->set
  "转换序列到set,如果datas是字符串，则将整个串作为set元素"
  [datas]
  (if (string? datas)
    (set [datas])
    (set datas)))

(defn crt-sh
  "从crt.sh返回子域名集合"
  ([domain] (crt-sh domain nil))
  ([domain opts]
   (log/info :crt-sh domain)
   (let [u (-> "https://crt.sh/"
               url
               (assoc :query {:dNSName domain}))]
     (->> (some-> (http/get (str u)
                            opts)
                  :body
                  html/parse
                  (html/extract [] "tr > td:eq(4)" html/text)
                  ->set)
          (mapcat #(-> (str/replace %1 #"\*\." "")
                       (str/split #"\s")))
          set))))

(str/split "im.baidu.com" #"\s")
;;; -------------------- virustotal --------------

(defn virustotal-next
  [data]
  (get-in data [:links :next]))

(defn virustotal-domain-get
  [data]
  (map :id (:data data)))

(defn virustotal
  "从virusTotal返回子域名集合"
  ([domain] (virustotal domain nil))
  ([domain opts]
   (log/info :virustotal domain)
   (let [u (format "https://www.virustotal.com/ui/domains/%s/subdomains?limit=40" domain)]
     (loop [sub-domains #{}
            url u]
       (if url
         (-> (http/get url
                       (merge opts
                              {:accept :json
                               :as :json}))
             :body
             (as-> data
                 (recur (->> (virustotal-domain-get data)
                             (into sub-domains))
                        (virustotal-next data))))
         sub-domains)))))

;;; ----------------- dnsdumpster -----------

(defn get-resp-cookie-value
  [resp cookie-name]
  (get-in resp [:cookies cookie-name :value]))

(def find-domain (partial re-find #"[^\s]+"))
(defn dnsdumpster-extract-domain
  [doc]
  (let [tables (-> (html/parse doc)
                   (html/select "table"))]
    ;; host 记录在最后一个table中
    (some-> (last tables)
            (html/extract [] "tr > td:eq(0)" html/text)
            (as-> r
                ;; 如果只有一个结果，就会返回一个字符串，不是序列
                (if (string? r)
                  (set [(find-domain r)])
                  (-> (map find-domain r)
                      set))))))

(defn dnsdumpster
  "从dnsdumpster返回子域名，最多120个"
  ([domain] (dnsdumpster domain nil))
  ([domain opts]
   (log/info :dnsdumpster domain)
   (let [u "https://dnsdumpster.com/"
         cs (cookies/cookie-store)
         ;; 先获取csrf token.
         csrf (-> (http/get u
                            (merge opts
                                   {:cookie-store cs
                                    :cookie-policy :standard}))
                  (get-resp-cookie-value "csrftoken"))]
     (-> (http/post u
                    (merge opts
                           {:cookie-store cs
                            :cookie-policy :standard
                            :headers {"Referer" u}
                            :form-params {:csrfmiddlewaretoken csrf
                                          :targetip domain}}))
         :body
         dnsdumpster-extract-domain))))

;;; --------------- chinaz ---------------

(defn chinaz-domain-get
  [html]
  (html/extract html [] "ul.ResultListWrap > li.ReLists > div > a" html/text))

(defn chinaz-next
  [html]
  (html/extract html [] "div.ToolPage-right > a[title=下一页]" (html/attr "href")))

(defn chinaz
  "从chinaz返回子域名集合"
  ([domain] (chinaz domain nil))
  ([domain opts]
   (log/info :chinaz domain)
   (let [base-url "http://tool.chinaz.com"
         path (str "/subdomain/?domain=" domain)]
     (loop [sub-domains #{}
            path path]
       (if path
         (some-> (http/get (str base-url path)
                           opts)
                 :body
                 html/parse
                 (as-> doc
                     (recur (->> (chinaz-domain-get doc)
                                 (into sub-domains))
                            (chinaz-next doc))))
         ;; 最后一条会多一个other
         (disj sub-domains "OTHER"))))))

;; ---------------------- ip138 ------------

(defn ip138
  "从ip138返回子域名集合"
  ([domain] (ip138 domain nil))
  ([domain opts]
   (log/info :ip138 domain)
   (let [u (format "http://site.ip138.com/%s/domain.htm" domain)]
     (some-> (http/get u opts)
             :body
             html/parse
             (html/extract [] "div.panel > p > a" html/text)
             ;; 当只有一个结果时，会返回一个字符串，set又把字符串作为序列...
             ->set))))


;; ---------------------- total
(def supported-subdomain-tools
  {:ip138 ip138
   :chinaz chinaz
   :dnsdumpster dnsdumpster
   :virustotal virustotal
   :crt-sh crt-sh})
