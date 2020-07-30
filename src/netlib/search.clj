(ns netlib.search
  (:require [clj-http.client :as client]
            [clj-http.cookies :as cookies]
            [java-time :as time]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [cemerick.url :refer [url url-encode]]
            [common.http :as http]
            [netlib.util :refer [page-seq]]
            [reaver :as html]
            [buddy.core.codecs :as codecs]
            [buddy.core.codecs.base64 :as base64]
            [clojure.java.io :as io]))

(defn create-engine
  "创建一个搜索引擎。
  参数：
  - `engine-type` 搜索引擎类型 :google :bing :sogou
  - `opts` 引擎相关参数 :http-opts 为http请求参数
                      :request-delay 为请求延时"
  ([engine-type] (create-engine engine-type nil))
  ([engine-type {:keys [http-opts request-delay]
                 :or {request-delay 200}
                 :as opts}]
   (let [cs (cookies/cookie-store) ;每个engine用一个cookie store
         header (http/build-http-opt {:cookie-store cs
                                      :cookie-policy :standard})]
     {:type engine-type
      :option (merge header http-opts)
      :request-delay request-delay})))

(defn get-option
  [engine option-name]
  (get-in engine [:option option-name]))

(defn get-delay
  [engine]
  (:request-delay engine))

(defn set-ua
  "设置engine的user agent,返回新的engine"
  [engine new-user-agent]
  (assoc-in engine [:option :headers "User-Agent"] new-user-agent))

(def google
  "google search engine"
  (partial create-engine :google))

(def bing
  "bing search engine"
  (partial create-engine :bing))

(def sogou
  "sogou search engine"
  (partial create-engine :sogou))

(defn dispatch-engine
  [engine & _]
  (:type engine))

(defn add-option
  "向engine中添加新的选项,返回新的engine
  - `engine` 要添加的engine
  - `option` 选项map，同名的会覆盖旧的"
  [engine option]
  (let [old-option (:option engine)]
    (->> (merge old-option option)
         (assoc engine :option))))

;; --- helper ------------------------------
(defn parse-get-url
  [url option]
  (log/debug "get url:" url)
  (-> (client/get url option)
      :body
      html/parse))

(defn parse-post-url
  [url option]
  (-> (client/post url option)
      :body
      html/parse))

;; ---extract entrys ------------------------------
(defmulti extract-entrys
  "提取搜索结果条目信息"
  {:arglists '([engine doc])}
  dispatch-engine)

(defmethod extract-entrys
  :google
  [engine doc]
  (html/extract-from doc "div.g"
                     [:url :title :desc]
                     "div.r > a:eq(0)" (html/attr "href")
                     "div.r > a:eq(0)" html/text
                     "div.s span.st" html/text))

(defmethod extract-entrys
  :bing
  [engine doc]
  (html/extract-from doc "li.b_algo"
                     [:url :title :desc]
                     "h2 > a" (html/attr "href")
                     "h2 > a" html/text
                     "div > p" html/text))

(defmethod extract-entrys
  :sogou
  [engine doc]
  (html/extract-from doc "div.rb"
                     [:snap-link :url :title :desc]
                     ;; 如果有快照，则从快照链接中解析出url
                     "div.fb > a:eq(1)" (fn [link]
                                    (if link
                                      (-> (html/attr link "href")
                                          (url)
                                          (get-in [:query "url"]))))
                     "h3 > a" (html/attr "href")
                     "h3 > a" html/text
                     "div.ft" html/text))

;; ---extract next path ------------------------------
(defmulti extract-next-path
  "提取搜索结果下一页地址"
  {:arglists '([engine doc])}
  dispatch-engine)

(defmethod extract-next-path
  :google
  [engine doc]
  (-> (html/select doc "#pnnext" )
      (html/attr "href")))

(defmethod extract-next-path
  :bing
  [engine doc]
  (-> (html/select doc "a.sb_pagn" )
      (html/attr "href")))

(defmethod extract-next-path
  :sogou
  [engine doc]
  (-> (html/select doc "#sogou_next" )
      (html/attr "href")))

;; ---search------------------------------
(defmulti search
  "搜索kw并返回结果列表"
  {:arglists '([engine kw])}
  dispatch-engine)

(defmethod search
  :google
  [engine kw]
  (let [base-url (url "https://www.google.com/")
        fn-page (fn [u]
                  (let [doc (parse-get-url (str u) (:option engine))
                        entrys (extract-entrys engine doc)
                        next-path (some->> (extract-next-path engine doc)
                                           (assoc base-url :path))]
                    {:entrys entrys
                     :next-page next-path}))]
    (page-seq fn-page
              (assoc base-url :path "/search"
                     :query {:q kw :num 100 :filter 0})
              :next-page
              (get-delay engine))))

(defmethod search
  :bing
  [engine kw]
  (let [base-url (url "https://www.bing.com/")
        cs (get-option engine :cookie-store)
        _ (->> (http/new-cookie "SRCHHPGUSR"
                                "NRSLT=50"
                                ".bing.com")
               (cookies/add-cookie cs))
        fn-page (fn [u]
                  (let [doc (parse-get-url (str u) (:option engine))
                        entrys (extract-entrys engine doc)
                        next-path (some->> (extract-next-path engine doc)
                                           (assoc base-url :path))]
                    {:entrys entrys
                     :next-page next-path}))]
    (page-seq fn-page
              (assoc base-url :path "/search"
                     :query {:q kw})
              :next-page
              (get-delay engine))))

(defn sogou-get-real-addr
  "获取sogou搜索结果中的真实url"
  [engine base-url link]
  (let [u (assoc base-url :path link)]
    (log/debug "sogou get real addr" u)
    (->> (client/get (str u) (:option engine))
         :body
         (re-find #"replace\(\"(.*)\"\)")
         second)))

(defmethod search
  :sogou
  [engine kw]
  (let [base-url (url "https://www.sogou.com/web")
        cs (get-option engine :cookie-store)
        _ (->> (http/new-cookie "com_sohu_websearch_ITEM_PER_PAGE"
                                "100"
                                ".sogou.com")
               (cookies/add-cookie cs))
        resolve-real-url (fn [{:keys [snap-link url title desc]}]
                           ;; 如果没有快照地址，则请求真实的url
                           {:url (if snap-link
                                   snap-link
                                   (sogou-get-real-addr engine base-url url))
                            :title title
                            :desc desc})
        fn-page (fn [u]
                  (let [doc (parse-get-url (str u) (:option engine))
                        entrys (->> (extract-entrys engine doc)
                                    (map resolve-real-url))
                        next-path (some-> (extract-next-path engine doc)
                                          ;; sougou的下一页是query,去掉开头的'?'
                                          (subs 1)
                                          (->> (assoc base-url :query)))]
                    {:entrys entrys
                     :next-page next-path}))]
    (page-seq fn-page
              (assoc base-url :query {:query kw})
              :next-page
              (get-delay engine))))

(defn- parse-url-host
  [u]
  (-> (url u)
      :host))

(defn search-pages
  "搜索指定页数的结果"
  [engine kw page]
  (->> (search engine kw)
       (take page)
       (mapcat :entrys)))

(defn find-sub-domains
  "从搜索引擎中查找子域名
  `max-page` 指定最大查找页数,默认为5"
  ([engine site] (find-sub-domains engine site 5))
  ([engine site max-page]
   (->> (search-pages engine (str "site:" site) max-page)
        (map #(parse-url-host (:url %1)))
        set)))

