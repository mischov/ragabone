(ns ragabone
  (:refer-clojure :exclude [nth])
  (:require [clojure.core :as clj]
            [clojure.string :as string])
  (:import [org.jsoup Jsoup]
           [org.jsoup.parser Parser]
           [org.jsoup.nodes Attribute Attributes Comment DataNode
                            Document DocumentType Element Node
                            TextNode]
           [org.jsoup.select Elements]))

;;;;;;;;;;;;;;;;;;
;;  Parse HTML  ;;
;;;;;;;;;;;;;;;;;;

(defn parse
  "Parses a string representing a full HTML document
   into JSoup."
  [^String html]
  (when html
    (Jsoup/parse html)))

(defn parse-fragment
  "Parses a string representing a fragment of HTML
   into JSoup"
  [^String html]
  (when html
    (-> (Jsoup/parseBodyFragment html) .body (.childNode 0))))

;;;;;;;;;;;;;;;
;;  Emitter  ;;
;;;;;;;;;;;;;;;

(defn ^:private to-keyword
  "Converts a string into a lowercase keyword."
  [s]
  (-> s string/lower-case keyword))

(defn reduce-into
  "Imperfectly mimics 'into' with 'reduce' and 'conj'
   for better performance."
  [empty-coll xs]
  (reduce conj empty-coll xs))

(defprotocol JSoup
  (to-edn [jsoup]
    "Converts JSoup into an edn representation of HTML."))

(extend-protocol JSoup
  Attribute
  (to-edn [attr] [(to-keyword (.getKey attr))
                      (.getValue attr)])
  Attributes
  (to-edn [attrs] (not-empty (reduce-into {} (map to-edn attrs))))
  Comment
  (to-edn [comment] {:type :comment
                     :content [(.getData comment)]})
  DataNode
  (to-edn [node] (str node))
  Document
  (to-edn [doc] {:type :document
                 :content (not-empty
                           (reduce-into [] (map to-edn (.childNodes doc))))})
  DocumentType
  (to-edn [doctype] {:type :document-type
                     :attrs (to-edn (.attributes doctype))})
  Element
  (to-edn [element] {:type :element
                     :attrs (to-edn (.attributes element))
                     :tag (to-keyword (.tagName element))
                     :content (not-empty
                               (reduce-into [] (map to-edn (.childNodes element))))})
  Elements
  (to-edn [elements] (when (> (.size elements) 0)
                       (map to-edn elements)))
  TextNode
  (to-edn [node] (.getWholeText node)))

;;;;;;;;;;;;;;;;
;;  Selector  ;;
;;;;;;;;;;;;;;;;

(defn select-soup
  "Accepts parsed HTML and a string representing a
   CSS-esque selector and returns JSoup representing
   any successfully selected data.

   For more on selector syntax, see:
   http://jsoup.org/cookbook/extracting-data/selector-syntax"
  [^Node node ^String css-selector]
  (.select node css-selector))

(defn select
  "Like select-soup, but returns edn."
  [node css-selector]
  (to-edn (select-soup node css-selector)))

;;;;;;;;;;;;;;;;;
;;  Extractor  ;;
;;;;;;;;;;;;;;;;;

(defprotocol Extractor
  "Extractors are at their lowest level protocols. This
   permits such voodoo as the nth extractor, which
   allows one to take the nth member of a selection."
  (node* [selection])
  (tag* [selection])
  (attr* [selection attribute])
  (attrs* [selection])
  (text* [selection])
  (compose* [selection fns])
  (nth* [selection index]))

(extend-protocol Extractor
  java.lang.String
  (node* [htmltree]
    htmltree)
  (tag* [_]
    nil)
  (attr* [_ _]
    nil)
  (attrs* [_]
    nil)
  (text* [htmltree]
    htmltree)
   (compose* [htmltree fns]
     ((apply comp (reverse fns)) htmltree))
   (nth* [htmltree _]
     htmltree))

(extend-protocol Extractor
  clojure.lang.IPersistentMap
  (node* [htmltree]
    htmltree)
  (tag* [htmltree]
    (:tag htmltree))
  (attr* [htmltree attribute]
    (get (:attrs htmltree) (keyword attribute)))
  (attrs* [htmltree]
    (:attrs htmltree))
  (text* [htmltree]
    (cond
     (string? htmltree) htmltree
     (and (map? htmltree)
          (not= (:type htmltree) :comment)) (string/join (map text* (:content htmltree)))
          :else ""))
  (compose* [htmltree fns]
    ((apply comp (reverse fns)) htmltree))
  (nth* [htmltree _]
    htmltree))

(extend-protocol Extractor
  clojure.lang.Sequential
  (node* [htmltrees]
    (map node* htmltrees))
  (tag* [htmltrees]
    (map tag* htmltrees))
  (attr* [htmltrees attribute]
    (map #(attr* % attribute) htmltrees))
  (attrs* [htmltrees]
    (map attrs* htmltrees))
  (text* [htmltrees]
    (map text* htmltrees))
  (compose* [htmltrees fns]
    ((apply comp (reverse fns)) htmltrees))
  (nth* [htmltrees index]
    (clj/nth htmltrees index)))

(defn element
  "Extracts an element."
  ([]
     (fn [selection]
       (node* selection)))
  ([selection]
     (node* selection)))

(defn tag
  "Extracts the tag of from an element."
  ([]
     (fn [selection]
       (tag* selection)))
  ([selection]
     (tag* selection)))

(defn attr
  "Extracts the value of the supplied attribute key from
   an element."
  ([attribute]
     (fn [selection]
       (attr* selection attribute)))
  ([attribute selection]
     (attr* selection attribute)))

(defn attrs
  "Extracts all the attribute key/value pairs from an
   element."
  ([]
     (fn [selection]
       (attrs* selection)))
  ([selection]
     (attrs* selection)))

(defn text
  "Returns the text value of an element and its contents."
  ([]
     (fn [selection]
       (text* selection)))
  ([selection]
     (text* selection)))

(defn compose
  "Executes the supplied functions in left to right order
   on an element."
  [& fns]
  (fn [selection]
    (compose* selection fns)))

(defn nth
  "Returns the nth member of a selection if that selection
   is a sequence.  If not, it just returns the selection."
  ([index]
     (fn [selection]
       (nth* selection index)))
  ([index selection]
     (nth* selection index)))

;;;;;;;;;;;;
;;  Main  ;;
;;;;;;;;;;;;

(defn run-on
  [source extraction]
  (let [[selector extract] extraction 
        selected (select source selector)]
    (case (count selected)
      0 nil
      1 (extract (first selected))
      (extract selected))))

(defn run-all-on
  [source extractions]
  (case (count extractions)
    0 source
    1 (run-on source (first extractions))
    (map #(run-on source %) extractions)))

(defn extract
  "Accepts parsed HTML, a vector of keys which may be
   empty, and a variable number of extractions (which
   is a selector followed by an extractor).

   If a vector of keys is supplied, they are zipmapped
   together with the results of the extractions on the
   source.

   If no keys are supplied, a seq of the results is
   returned."
  [source ks & raw-extractions]
  (let [extractions (partition 2 raw-extractions)
        extracted (run-all-on source extractions)]
    (if (empty? ks)
      extracted
      (case (count extractions)
        0 {(first ks) extracted}
        1 {(first ks) extracted}
        (zipmap ks extracted)))))

(defn extract-from
  "Behaves like extract, but prior to extraction
   extract-from uses the additional selector to
   narrow down the data to be searched.

   This is useful when one wants select a sequence
   of items, then extract identical info from each."
  [raw-source selector ks & raw-extractions]
  (let [sources (select-soup raw-source selector)]
    (for [source sources]
      (apply (partial extract source ks) raw-extractions))))
