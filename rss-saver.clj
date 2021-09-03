(ns rss-saver.main
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]))

(def feed-url "https://world.hey.com/adam.james/feed.atom")
(def current-feed-str (slurp feed-url))

(defn init-previous!
  "Saves current-feed-str to previous-feed.atom when no previous-feed.atom file exists."
  []
  (when-not (.exists (io/file "previous-feed.atom"))
    (spit "previous-feed.atom" "")))

(init-previous!)

(def previous-feed-str (slurp "previous-feed.atom"))

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

(def current-entries
  (-> current-feed-str
      (xml/parse-str {:namespace-aware false})
      zip/xml-zip
      (get-nodes match-entry?)))

(def previous-entries
  (when-not (= "" previous-feed-str)
    (-> previous-feed-str
        (xml/parse-str {:namespace-aware false})
        zip/xml-zip
        (get-nodes match-entry?))))

(def entries
  (remove (into #{} previous-entries) current-entries))

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

(defn main
  []
  (println (str "Handling " (count entries) " entries."))
  (sh "mkdir" "-p" "posts")
  (into []
        (for [[id post] (mapv entry->html entries)]
          (let [fname (str "posts/" (second (str/split id #"/")) ".html")]
            (spit fname post))))
  (spit "previous-feed.atom" current-feed-str))

(main)
