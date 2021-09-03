(ns rss-saver.main
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]))

;; https://ravi.pckl.me/short/functional-xml-editing-using-zippers-in-clojure/
(defn edit-nodes
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

(defn remove-nodes
  [zipper matcher]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher loc)]
        (let [new-loc (zip/remove loc)]
          (recur (zip/next new-loc)))
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

(defn match-tag
  [k]
  (fn
    [loc]
    (let [node (zip/node loc)
          {:keys [tag]} node]
      (= tag k))))

(defn wrap-strs-in-p-tags
  [node]
  (let [f (fn [item]
            (if (string? item)
              {:tag :p :attrs {} :content [item]}
              item))
        new-content (->> node
                         :content
                         (map f))]
    (assoc node :content new-content)))

(defn convert-to-p-tag
  [node]
  (assoc node :tag :p))

(defn unwrap-img-from-figure
  [node]
  (let [img-node (-> node
                 zip/xml-zip
                 (get-nodes (match-tag :img))
                 first)
        new-attrs (-> img-node
                      :attrs
                      (dissoc :srcset :decoding :loading))]
    (assoc img-node :attrs new-attrs)))

(defn clean-html
  "Clean up the html string from the feed."
  [s]
  (let [s (-> s
              (str/replace "<br>" "<br></br>")
              (str/replace #"<img[\w\W]+?>" #(str %1 "</img>")))]
    (-> s
        (xml/parse-str {:namespace-aware false})
        zip/xml-zip
        (edit-nodes (match-tag :figure) unwrap-img-from-figure)
        xml/emit-str
        (str/replace #"<\?xml[\w\W]+?>" ""))))

(defn readable-date
  [s]
  (as-> s s
    (str/split s #"[a-zA-Z]")
    (str/join " " s)))

(defn entry->html
  "Converts a parsed XML entry node into an html document."
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
                   (clean-html (:content entry)))}))

(defn xml->hiccup
  [xml]
  (if-let [t (:tag xml)]
    (let [elem [t]
          elem (if-let [attrs (:attrs xml)]
                 (conj elem attrs)
                 elem)]
      (into elem (map xml->hiccup (:content xml))))
    xml))

(def tag-str
  {:h1 "# "
   :h2 "## "
   :h3 "### "
   :h4 "#### "
   :h5 "##### "
   :h6 "###### "
   :p ""
   :div ""
   :br "\n"
   :img "IMAGE"
   :ul ""
   :li "\n - "})

(defn xml->str
  [xml]
  (if-let [t (:tag xml)]
    (let [elem-start (if (t tag-str) (t tag-str) "")]
      (apply str (concat
                  [elem-start]
                  (mapv xml->str (:content xml)))))
    xml))

(defn html-str->markdown
  "Parses and converts an html string to markdown."
  [s]
  (-> s
      (xml/parse-str {:namespace-aware false})
      xml->str))

(defn entry->markdown
  "Converts a parsed XML entry node into a markdown document."
  [entry]
  (let [entry (normalize-entry entry)]
    {:id (:id entry)
     :post (format "
name: %s
email: %s
published: %s
updated: %s
link: [%s](%s)

# %s

%s"
                   (:name entry)
                   (:email entry)
                   (readable-date (:published entry))
                   (readable-date (:updated entry))
                   (:link entry)
                   (:link entry)
                   (:title entry)
                   (-> entry :content clean-html html-str->markdown))}))

(def cli-options
  [["-h" "--help"]
   ["-u" "--url URL" "The URL of the RSS feed you want to save."]
   ["-d" "--dir DIR" "The directory where articles will be saved."
    :default "./posts"]
   ["-f" "--format FORMAT" "The format of saved articles. Either 'html' or 'md' for markdown, defaulting to html if unspecified."
    :default "html"]
   ["-c" "--clear" "Clear the cached copy of the previous feed."]])

(defn clear!
  [opts]
  (let [prev-fname (str (:dir opts) "/" "previous-feed.atom")]
    (sh "rm" "-f" prev-fname)))

(defn save!
  [opts]
  (let [save-fn (get {"html" entry->html
                      "md" entry->markdown} (:format opts))
        cur-str (slurp (:url opts))
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
        (doseq [{:keys [id post]} (mapv save-fn entries)]
          (let [fname (str
                       (:dir opts) "/"
                       (second (str/split id #"/")) "."
                       (:format opts))]
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
