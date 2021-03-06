#!/usr/local/bin/bb
(ns rss-saver.main
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]
            [hiccup.core :refer [html]]))

;;  Zipper Tools
;; --------------

;; https://ravi.pckl.me/short/functional-xml-editing-using-zippers-in-clojure/
(defn edit-nodes
  "Edit nodes from `zipper` that return `true` from the `matcher` predicate fn with the `editor` fn.
  Returns the root of the provided zipper, *not* a zipper.
  The `matcher` fn expects a zipper location, `loc`, and returns `true` (or some value) or `false` (or nil).
  The `editor` fn expects a `node` and returns a potentially modified `node`."
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
  "Remove nodes from `zipper` that return `true` from the `matcher` predicate fn.
  Returns the root of the provided zipper, *not* a zipper.
  The `matcher` fn expects a zipper location, `loc`, and returns `true` (or some value) or `false` (or nil)."
  [zipper matcher]
  (loop [loc zipper]
    (if (zip/end? loc)
      (zip/root loc)
      (if-let [matcher-result (matcher loc)]
        (let [new-loc (zip/remove loc)]
          (recur (zip/next new-loc)))
        (recur (zip/next loc))))))

(defn get-nodes
  "Returns a list of nodes from `zipper` that return `true` from the `matcher` predicate fn.
  The `matcher` fn expects a zipper location, `loc`, and returns `true` (or some value) or `false` (or nil)."
  [zipper matcher]
  (loop [loc zipper
         acc []]
    (if (zip/end? loc)
      acc
      (if (matcher loc)
        (recur (zip/next loc) (conj acc (zip/node loc)))
        (recur (zip/next loc) acc)))))

(defn match-tag
  "Returns a `matcher` fn that matches any node containing the specified `key` as its `:tag` value."
  [key]
  (fn [loc]
    (let [node (zip/node loc)
          {:keys [tag]} node]
      (= tag key))))

;;  Entry Nodes
;; -------------

(defn feed-str->entries
  "Returns a sequence of parsed article entry nodes from an XML feed string."
  [s]
  (-> s
      (xml/parse-str {:namespace-aware false})
      zip/xml-zip
      (get-nodes (match-tag :entry))))

;;  Entry Transforms
;; ------------------

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

(defn unwrap-img-from-figure
  "Returns the simplified `:img` node from its parent node."
  [node]
  (let [img-node (-> node
                 zip/xml-zip
                 (get-nodes (match-tag :img))
                 first)
        new-attrs (-> img-node :attrs
                      (dissoc :srcset :decoding :loading))]
    (assoc img-node :attrs new-attrs)))

(defn clean-html
  "Cleans up the html string `s`.
  The string is well-formed html, but is coerced into XML conforming form by closing <br> and <img> tags.
  The emitted XML string has the <\\?xml...> tag stripped.
  This cleaning is done so that clojure.data.xml can continue to be used for parsing in later stages."
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

;;  Node Transforms
;; -----------------

(defmulti node->hiccup
  (fn [node]
    (cond
      (map? node) (:tag node)
      (and (seqable? node) (not (string? node))) :list
      :else :string)))

(defmethod node->hiccup :string
  [node]
  (when-not (= (str/trim node) "") node))

(defmethod node->hiccup :br [_] [:br])
(defmethod node->hiccup :div [node] (node->hiccup (:content node)))

(defmethod node->hiccup :default
  [{:keys [tag attrs content]}]
  [tag attrs (node->hiccup content)])

(defmethod node->hiccup :img
  [{:keys [tag attrs]}]
  [tag (assoc attrs :style {:max-width 500})])

(defn de-dupe
  "Remove only consecutive duplicate entries from the `list`."
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

(defn inline-elem? [item] (when (#{:em :strong :a} (first item)) true))
(defn inline? [item] (or (string? item) (inline-elem? item)))

(defn group-inline
  "Groups the `list` of strings and Hiccup elements using the `inline?` predicate and wraps them in <p> tags.
  Once all groups are wrapped, the list is flattened again and any remaining <br> tags are removed."
  [list]
  (let [groups (partition-by inline? list)
        f (fn [l]
            (if (not= (first (first l)) :br)
              (into [:p] l)
              l))]
    (->> groups
         (map f)
         selective-flatten
         (remove #(= :br (first %))))))

;;  entry->
;; ---------

(defn html-str->hiccup
  "Parses and converts an html string `s` into a Hiccup data structure."
  [s]
  (-> s
      (xml/parse-str {:namespace-aware false})
      node->hiccup
      group-inline
      de-dupe))

(defn entry->edn
  "Converts a parsed XML entry node into a Hiccup data structure."
  [entry]
  (let [entry (normalize-entry entry)]
    {:id (:id entry)
     :file-contents (assoc entry :post (->> entry :content
                                            clean-html
                                            html-str->hiccup))}))

(defn readable-date
  "Format the date string `s` into a nicer form for display."
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
                     [:strong label] s])
        post (->> entry :content
                   clean-html
                   html-str->hiccup)]
    (assoc entry :file-contents
           (->
            (str
            "<!DOCTYPE html>\n"
            (html
             {:mode :html}
             [:head
              [:meta {:charset "utf-8"}]
              [:title (:title entry)]]
             [:body
              [:div {:class "post-info"}
               (info-span "Author: " (:name entry))
               (info-span "Email: " (:email entry))
               (info-span "Published: " (readable-date (:published entry)))
               (info-span "Updated: " (readable-date (:updated entry)))]
              [:a {:href (:link entry)} [:h1 (:title entry)]]
              post]))
           (str/replace #"</br>" "")))))

;;  CLI
;; -----

(def cli-options
  [["-h" "--help"]
   ["-u" "--url URL" "The URL of the RSS feed you want to save."]
   ["-d" "--dir DIR" "The directory where articles will be saved."
    :default "./posts"]
   ["-f" "--format FORMAT" "The format of saved articles. Either 'html' or 'edn' for a Clojure Map with the post saved as Hiccup style syntax. Defaults to edn if unspecified."
    :default "edn"]
   ["-c" "--clear" "Clear the cached copy of the previous feed."]
   ["-s" "--silent" "Silence the script's output."]])

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
        (when-not (:silent opts)
          (println "Handling" (count entries) "entries as" (str (:format opts) ".")))

        ;; always create the posts directory
        (sh "mkdir" "-p" (:dir opts))

        ;; for each entry, transformed with the appropriate fn, save the file with id.ext
        (doseq [{:keys [id file-contents]} (mapv save-fn entries)]
          (let [fname (str
                       (:dir opts) "/"
                       (second (str/split id #"/")) "."
                       (:format opts))]
            (spit fname file-contents)))
        (spit prev-fname cur-str))

      ;; when there are no new entries, simply tell the user and then do nothing.
      (when-not (:silent opts)
        (println "No changes found in feed.")))))

(defn -main
  [& args]
  (let [parsed (cli/parse-opts args cli-options)
        opts (:options parsed)]
    (cond
      (:help opts)
      (println "Usage:\n" (:summary parsed))

      (nil? (:url opts))
      (when-not (:silent opts)
        (println "Please specify feed URL."))

      (not (#{"html" "edn"} (:format opts)))
      (when-not (:silent opts)
        (println "Invalid format:" (:format opts)))

      :else
      (do
        (when (:clear opts) (clear! opts))
        (save! opts)))))

;; apply -main to the args because I call this script with bb rss-saver.clj -u URL
;; if you run this script with clj -m, these two s-exprs should be commented out.
(apply -main *command-line-args*)
(shutdown-agents)
