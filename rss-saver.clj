(ns rss-saver.main
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]))

;; https://ravi.pckl.me/short/functional-xml-editing-using-zippers-in-clojure/
(defn tree-edit
  [zipper matcher editor]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher loc)]
        (let [new-loc (zip/edit loc editor)]
          (if (not (= (zip/node new-loc) (zip/node loc)))
            (recur (zip/next new-loc))
            (recur (zip/next loc))))
        (recur (zip/next loc))))))

(defn get-nodes
  [zipper matcher]
  (loop [loc zipper
         acc []]
    (if (zip/end? loc)
      acc
      (if (matcher loc)
        (recur (zip/next loc) (conj acc (zip/node loc)))
        (recur (zip/next loc) acc)))))

(defn match-entry?
  [loc]
  (let [node (zip/node loc)
        {:keys [tag]} node]
    (= tag :entry)))

(defn feed-str->entries
  "Returns a sequence of parsed article entry nodes from an XML feed string."
  [s]
  (-> s
      (xml/parse-str {:namespace-aware false})
      zip/xml-zip
      (get-nodes match-entry?)))

(defn get-previous-entries
  []
  (when (.exists (io/file "previous-feed.atom"))
    (feed-str->entries (slurp "previous-feed.atom"))))

(defn get-current-entries
  [url]
  (feed-str->entries (slurp url)))

(defn entry->html
  [entry]
  (let [{:keys [content]} entry
        content (remove string? content)
        content-map (zipmap (map :tag content)
                            (map #(first (:content %)) content))]
    [(:id content-map)
     (format "
<html>
  <head>
    <Title>%s</Title>
  </head>
  <body>
    <h1>%s</h1>
    <h4>Published: %s</h4>
    <h4>Updated: %s</h4>
    %s
  </body>
</html>"
             (:title content-map)
             (:title content-map)
             (:published content-map)
             (:updated content-map)
             (:content content-map))]))

(def cwd (.getCanonicalPath (io/file ".")))

(def cli-options
  [["-h" "--help"]
   ["-u" "--url URL" "The URL of the RSS feed you want to save."]
   ["-d" "--dir DIR" "The directory where articles will be saved."
    :default (str cwd "/posts")]
   ["-c" "--clear" "Clear the cached copy of the previous feed."]])

(defn main
  [& args]
  (let [parsed (cli/parse-opts args cli-options)
        opts (:options parsed)]
    (cond
      (:help opts)
      (println (str "Usage:" "\n" (:summary parsed)))

      (nil? (:url opts))
      (println "Please specify feed URL.")

      :else
      (let [cur-str (slurp (:url opts)) ;; TODO: fix so that URL is only slurped once
            prev (get-previous-entries)
            cur (get-current-entries (:url opts))
            entries (remove (into #{} prev) cur)]
        (println (str "Handling " (count entries) " entries."))
        (sh "mkdir" "-p" (:dir opts))
        (into []
              (for [[id post] (mapv entry->html entries)]
                (let [fname (str
                             (:dir opts) "/"
                             (second (str/split id #"/")) ".html")]
                  (spit fname post))))
        (spit "previous-feed.atom" cur-str)))))

(apply main *command-line-args*)
(shutdown-agents)
