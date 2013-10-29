(ns ragabone.zip
  (:require [clojure.zip :as zip]))

; This function is from David Santiago's library Hickory.

(defn zip-htmltree
  "Returns a zipper of htmltrees."
  [root]
  (zip/zipper (complement string?)
              (comp seq :content)
              (fn [node children]
                (assoc node :content (and children
                                          (apply vector children))))
              root))
