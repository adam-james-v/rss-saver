(ns rss-saver.main-test
  (:require [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [clojure.java.shell :as sh :refer [sh]]
            [clojure.tools.cli :as cli]
            [clojure.test :as t :refer [deftest is]]
            [hiccup2.core :refer [html]]))

(def script-str
  (->> (slurp "rss-saver.clj")
       (str/split-lines)
       (drop 1)
       (drop-last 4)
       #_(remove #(str/starts-with? % ";"))
       (apply str)))

(spit "tmp.clj" script-str)
(load-file "tmp.clj")
#_(sh "rm" "tmp.clj")

#_(ns-publics *ns*)
#_(node->hiccup {:tag :list :attrs {} :content (list "a" "b" "c")})
