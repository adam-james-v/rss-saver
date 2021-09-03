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

(defn normalize-entry
  "Normalizes the entry node by flattening content into a map."
  [entry]
  (let [content (filter map? (:content entry))
        f (fn [{:keys [tag content] :as node}]
            (let [val (cond (= tag :link) (get-in node [:attrs :href])
                            :else (first content))]
                {tag val}))
        author-map (->> content
                        (filter #(= (:tag %) :author))
                        first :content
                        (filter map?)
                        (map f)
                        (apply merge))]
   (apply merge (conj
                 (map f (remove #(= (:tag %) :author) content))
                 author-map))))

(defn readable-date
  [s]
  (as-> s s
    (str/split s #"[a-zA-Z]")
    (str/join " " s)))

(defn entry->html
  "Converts a parsed XML entry node into an html document.
  The generated html string does not reformat the html from the feed."
  [entry]
  (let [entry (normalize-entry entry)]
    {:id (:id entry)
     :post (format "
<!DOCTYPE html>
<html lang=\"en\">
  <head>
    <meta charset=\"utf-8\"/>
    <title>%s</title>
  </head>
  <body>
    <p><strong>Author:</strong> %s</p>
    <p><strong>email:</strong> %s</p>
    <p><strong>Published:</strong> %s</p>
    <p><strong>Updated:</strong> %s</p>
    <a href=\"%s\"><h1>%s</h1></a>
    %s
  </body>
</html>"
                   (:title entry)
                   (:name entry)
                   (:email entry)
                   (readable-date (:published entry))
                   (readable-date (:updated entry))
                   (:link entry)
                   (:title entry)
                   (:content entry))}))

(defn clean-html
  "Clean up the html string from the feed."
  [s]
  (let [s (str/replace s "<br>" "<br></br>")]
    (xml/parse-str s)))

(def cli-options
  [["-h" "--help"]
   ["-u" "--url URL" "The URL of the RSS feed you want to save."]
   ["-d" "--dir DIR" "The directory where articles will be saved."
    :default "./posts"]
   ["-c" "--clear" "Clear the cached copy of the previous feed."]])

(defn clear!
  [opts]
  (let [prev-fname (str (:dir opts) "/" "previous-feed.atom")]
    (sh "rm" "-f" prev-fname)))

(defn save!
  [opts]
  (let [cur-str (slurp (:url opts))
        prev-fname (str (:dir opts) "/" "previous-feed.atom")
        prev-str (when (.exists (io/file prev-fname))
                   (slurp prev-fname))
        prev (when prev-str (feed-str->entries prev-str))
        cur (feed-str->entries cur-str)
        entries (remove (into #{} prev) cur)]
    (if (> (count entries) 0)
      (do
        (println (str "Handling " (count entries) " entries."))
        (sh "mkdir" "-p" (:dir opts))
        (doseq [{:keys [id post]} (mapv entry->html entries)]
          (let [fname (str
                       (:dir opts) "/"
                       (second (str/split id #"/")) ".html")]
            (spit fname post)))
        (spit prev-fname cur-str))
      (println "No changes found in feed."))))

(defn -main
  [& args]
  (let [parsed (cli/parse-opts args cli-options)
        opts (:options parsed)]
    (cond
      (:help opts)
      (println (str "Usage:" "\n" (:summary parsed)))

      (nil? (:url opts))
      (println "Please specify feed URL.")

      :else
      (do
        (when (:clear opts) (clear! opts))
        (save! opts)))))

(apply -main *command-line-args*)
(shutdown-agents)
