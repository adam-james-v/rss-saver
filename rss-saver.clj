(ns rss-saver.main
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]
            [hiccup.core :refer [html]]))

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

(defmulti node->hiccup
  (fn [node]
    (cond
      (map? node) (:tag node)
      (and (seqable? node) (not (string? node))) :list
      :else :string)))

(defmethod node->hiccup :string
  [node]
  (when-not (= (str/trim node) "") node))

(defn inline-elem? [item] (when (#{:em :strong} (first item)) true))
(defn inline? [item] (or (string? item) (inline-elem? item)))

(defn group-inline
  [list]
  (let [groups (partition-by inline? list)
        f (fn [list]
            (let [f (fn [[a b c]]
                      (cond
                        (and (string? a)
                             (inline-elem? b)
                             (string? c))
                        [:p a b c]
                        
                        :else
                        a))]
              (map f (partition-all 3 1 list))))]
    (->> groups
         (map f))))

(defn de-dupe
  [list]
  (->> list
       (partition-by identity)
       (map first)))

(defn selective-flatten
  ([l] (selective-flatten [] l))
  ([acc l]
   (if (seq l)
     (let [item (first l)
           xacc (if (or (string? item)
                        (and (vector? item) (keyword? (first item))))
                 (conj acc item)
                 (into [] (concat acc (selective-flatten item))))]
       (recur xacc (rest l)))
     (apply list acc))))

(defmethod node->hiccup :list
  [node]
  (->> node
       (map node->hiccup)
       (remove nil?)
       de-dupe
       selective-flatten))

(defmethod node->hiccup :div [node] (node->hiccup (:content node)))

(defmethod node->hiccup :default
  [{:keys [tag attrs content]}]
  [tag attrs (node->hiccup content)])

(defn html-str->hiccup
  "Parses and converts an html string to markdown."
  [s]
  (-> s
      (xml/parse-str {:namespace-aware false})
      node->hiccup))

(defn entry->edn
  "Converts a parsed XML entry node into a Hiccup data structure."
  [entry]
  (let [entry (normalize-entry entry)]
    {:id (:id entry)
     :post (assoc entry :post (->> entry :content
                                   clean-html
                                   html-str->hiccup))}))

(defn readable-date
  [s]
  (as-> s s
    (str/split s #"[a-zA-Z]")
    (str/join " " s)))

(defn entry->html
  "Converts a parsed XML entry node into an html document."
  [entry]
  (let [entry (normalize-entry entry)
        info-span (fn [label s]
                    [:span {:style {:display "block"
                                    :margin-bottom "2px"}}
                     [:strong label] s])]
    (assoc entry :post
           (str
            "<!DOCTYPE html>\n"
            (html
             (list
              [:head
               [:meta {:charset "utf-8"}]
               [:title (:title entry)]]
              [:body
               [:div {:class "post-info"}
                (info-span "Author: " (:name entry))
                (info-span "Email: " (:email entry))
                (info-span "Published: " (:published entry))
                (info-span "Updated: " (:updated entry))]
               [:a {:href (:link entry)} [:h1 (:title entry)]]
               (->> entry :content
                    clean-html
                    html-str->hiccup
                    html)]))))))

(def cli-options
  [["-h" "--help"]
   ["-u" "--url URL" "The URL of the RSS feed you want to save."]
   ["-d" "--dir DIR" "The directory where articles will be saved."
    :default "./posts"]
   ["-f" "--format FORMAT" "The format of saved articles. Either 'html' or 'edn' for a Clojure Map with Hiccup style syntax. Defaults to html if unspecified."
    :default "html"]
   ["-c" "--clear" "Clear the cached copy of the previous feed."]])

(defn clear!
  [opts]
  (let [prev-fname (str (:dir opts) "/" "previous-feed.atom")]
    (sh "rm" "-f" prev-fname)))

(defn save!
  [opts]
  (let [save-fn (get {"html" entry->html
                      "edn" entry->edn} (:format opts))
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
