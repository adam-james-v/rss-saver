(ns rss-saver.main-test
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]
            [clojure.test :as t :refer [deftest is]]
            [hiccup2.core :refer [html]]))

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

;;  Tests
;; -------

;; this is a pull from my feed.atom at a point where 7 posts existed
(def entries (feed-str->entries (slurp "sample-feed.atom")))
(def raw-entry-content (:content (nth entries 4)))
(def normalized-entry (normalize-entry (nth entries 4)))

(def normalized-entry-keys
  #{:name :email :id
    :published :updated
    :link :title :content})

(deftest entry-tests
  (is (= 7 (count entries)))
  (is (seq? raw-entry-content))
  (is (= (type (:content normalized-entry)) java.lang.String))
  (is (= normalized-entry-keys (into #{} (keys normalized-entry)))))

(defn get-figure-nodes
  [s]
  (let [s (-> s
              (str/replace "<br>" "<br></br>")
              (str/replace #"<img[\w\W]+?>" #(str %1 "</img>")))]
    (-> s
        (xml/parse-str {:namespace-aware false})
        zip/xml-zip
        (get-nodes (match-tag :figure)))))

(def fig-img (-> (:content normalized-entry)
                 get-figure-nodes
                 first))

(def unwrapped-img (unwrap-img-from-figure fig-img))
                    
(deftest image-tests
  (is (= :figure (:tag fig-img)))
  (is (= :img (:tag unwrapped-img))))

(def some-string "Hi there!")
(def empty-string "  ")
(def br {:tag :br :attrs {} :content '()})

(def img-attrs {:src "https://www.fillmurray.com/300/200"
                :alt "Bill Murray is great!"})
(def img {:tag :img
          :attrs img-attrs
          :content '()})
(def img-result [:img (merge img-attrs {:style {:max-width 500}})])

(def div {:tag :div
          :attrs {:a 1 :b 2}
          :content (list some-string br img)})
(def div-result (list "Hi there!" [:br] img-result))

(def default (assoc div :tag :anything))
(def default-result [:anything (:attrs div) div-result])

(def duplicates ["a" "a" "a" "b" "a" "c" "c" "d" "d" "c"])
(def nested (list (list (repeat 4 "a") (repeat 3 [:br]) "a" [:em "b"] "c")))

(deftest node->hiccup-tests
  (is (= (node->hiccup some-string) "Hi there!"))
  (is (nil? (node->hiccup empty-string)))
  (is (= (node->hiccup br) [:br]))
  (is (= (node->hiccup img) img-result))
  (is (= (node->hiccup div) div-result))
  (is (= (node->hiccup default) default-result))
  (is (= (de-dupe duplicates) ["a" "b" "a" "c" "d" "c"]))
  (is (= (selective-flatten nested)
         (list "a" "a" "a" "a" [:br] [:br] [:br] "a" [:em "b"] "c")))
  (is (= (group-inline (selective-flatten nested))
         (list [:p "a" "a" "a" "a"] [:p "a" [:em "b"] "c"]))))

(t/run-tests)
