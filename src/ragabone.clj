(ns ragabone
  (:refer-clojure :exclude [and or not nth])
  (:require [clojure.core :as clj]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.zip :as zip]
            [ragabone.parser :as parser]
            [ragabone.zip :as rzip]))

;####################
;##  PARSING, ETC  ##
;####################

(defn from-file
  "Accepts a path to a resource or other file and returns the
   contents of that file."
  [path]
  (slurp (clj/or (io/resource path)
                 (io/file path))))

(def parse parser/parse)

;###########
;##  ZIP  ##
;###########

(def zip-htmltree rzip/zip-htmltree)

;#################
;##  SELECTORS  ##
;#################

; Basically the entirety of the selectors are taken from
; Raynes library Laser.  All credit for the awesomeness
; goes to him, all errors are down to my lousy transcription.

(defn tag=
  "Accepts a tag and returns a selector for any htmltree which
   has that tag."
  [tag]
  (fn [loc]
    (= (keyword tag) (-> loc zip/node :tag))))

(defn attr=
  "Accepts an attribute and a value and returns a selector for any
   htmltree which has an attribute with that value."
  [attr value]
  (fn [loc]
    (= (name value) (get-in (zip/node loc) [:attrs (keyword attr)]))))

(defn attr?
  "Accepts an attribute and returns a selector for any htmltree
   with that attribute."
  [attr]
  (fn [loc]
    (-> (zip/node loc)
        (:attrs)
        (contains? (keyword attr)))))

(defn ^:private split-classes
  "Given a (zipper) location, returns a set of the classes in
   that htmltree."
  [loc]
  (set (string/split (get-in (zip/node loc) [:attrs :class] "") #" ")))

(defn class=
  "Accepts a variable number of strings or keywords representing
   classes and returns a selector for any htmltree which has all
   those classes."
  [& classes]
  (fn [loc]
    (every? (split-classes loc) (map name classes))))

(defn id=
  "Accepts an id and returns a selector for any htmltree with
   that id."
  [id]
  (attr= :id id))

(defn any
  "A selector that matches any htmltree."
  []
  (constantly true))

(defn not
  "Negates a selector."
  [selector]
  (fn [loc] (clj/not (selector loc))))

(defn and
  "Returns true if all selectors match."
  [& selectors]
  (apply every-pred selectors))

(defn or
  "Returns true if at least one selector matches."
  [& selectors]
  (apply some-fn selectors))

(defn ^:private select-walk
  "A generalied function for implementing selectors that do the
   following:

   1) check if the last selector matches the current loc
   2) check that the selector before it matches a new loc
   after a movement, and so on.

   Unless all the selectors match like this, the result is
   a non-match. The first argument is a function that will
   be run on the result of the selector call and the loc itself
   and should return true to continue or false to stop. The
   second argument tells the function how to move in the
   selector. For example, zip/up."
  [continue? move selectors]
  (fn [loc]
    (let [selectors (reverse selectors)
          selector (first selectors)]
      (if (selector loc)
        (loop [result false
               loc (move loc)
               [selector & selectors :as same] (rest selectors)]
          (cond
           (clj/and selector (nil? loc)) false
           (nil? selector) result
           :else (let [result (selector loc)]
                   (if (continue? result loc)
                     (recur result
                            (move loc)
                            (if result
                              selectors
                              same))
                     result))))
        false))))

(defn child-of
  "Checks that the last selector matches the current loc. If so,
   checks to see if the immediate parent matches the next selector.
   If so, repeat. Equivalent to 'foo > bar > baz' in CSS for matching
   a baz element whose parent is a bar element whose parent is a foo
   element."
  [& selectors]
  (select-walk (fn [result _] result) zip/up selectors))

(defn ^:private parse-css-selector
  "Accepts a faux-css selector and returns any tag, id, and
   classes

   A faux-css selector should follow the pattern tag then id then
   classes. Any of these may be omitted but the order should be
   maintained.

   A tag does not have any symbol before it, an id is preceded
   by a pound sign, and classes are preceeded by periods.

   Examples:

     :header#should.get.things.rolling
     :#id.is.lonely.without.classes
     :div.main
     :.unstyled"
  [selector]
  (let [regex #"([^\s\.#]*)(?:#([^\s\.#]+))?(?:\.([^\s#]+))?"
        [_ tag id classes] (re-matches regex (name selector))]
    (and (if (not-empty tag)
           (tag= tag)
           (any))
         (if id
           (id= id)
           (any))
         (if (not-empty classes)
           (apply class= (string/split classes #"\."))
           (any)))))

(defn ^:private parse-css-selectors
  "Accepts a vector of css selectors and parses them."
  [selectors]
  (case (count selectors)
    0 (any)
    1 (first (map parse-css-selector selectors))
    (apply child-of (map parse-css-selector selectors))))

(def read-selector
  "Accepts either a vector of css selectors or a selector
   function."
  (memoize
   (fn [selector]
     (cond
      (vector? selector) (parse-css-selectors selector)
      :else selector))))

;#################
;##  SELECTION  ##
;#################

; This section, with a couple modifications, has
; been copied from David Santiago's Tinsel library.

; After he sees what I've done to it, he'll probably
; not want it back.  But he deserves credit for it
; anyway.

(def ^:private select-next-loc
  "Given a selector function and a loc inside a htmltree zip
   data structure, returns the next zipper loc that satisfies the
   selection function. This can be the loc that is passed in, so be
   sure to move to the next loc if you want to use this function
   to exhaustively search through a tree manually.

   Note that if there is no next node that satisfies the
   selection function, nil is returned."
  (memoize
   (fn [selector-fn rzip-loc]
     (loop [loc rzip-loc]
       (if (zip/end? loc)
         nil
         (if (selector-fn loc)
           loc
           (recur (zip/next loc))))))))

(defn ^:private select-locs
  "Given a selector function and a htmltree data structure,
   returns a vector containing all of the zipper locs selected
   by the selector function."
  [selector-fn zipper]
  (loop [loc (select-next-loc selector-fn zipper)
         selected-nodes (transient [])]
    (if (nil? loc)
      (persistent! selected-nodes)
      (recur (select-next-loc selector-fn (zip/next loc))
             (conj! selected-nodes loc)))))

(defn select
  "Given a selector function and a htmltree data structure,
   returns a vector containing all of the htmltree nodes
   selected by the selector function."
  [selector source]
  (let [selector-fn (read-selector selector)]
    (if (seq? source)
      (->> (for [zipper (map zip-htmltree source)]
             (mapv zip/node (select-locs selector-fn zipper)))
           (apply concat))
      (mapv zip/node (select-locs selector-fn (zip-htmltree source))))))

;#################
;##  EXTRACTORS ##
;#################

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

(defn node
  "Extracts an entire htmltree node."
  ([]
     (fn [selection]
       (node* selection)))
  ([selection]
     (node* selection)))

(defn tag
  "Extracts the tag of a htmltree node."
  ([]
     (fn [selection]
       (tag* selection)))
  ([selection]
     (tag* selection)))

(defn attr
  "Extracts the value of the supplied attribute key from
   a htmltree node."
  ([attribute]
     (fn [selection]
       (attr* selection attribute)))
  ([attribute selection]
     (attr* selection attribute)))

(defn attrs
  "Extracts all the attribute key/value pairs from a
   htmltree node."
  ([]
     (fn [selection]
       (attrs* selection)))
  ([selection]
     (attrs* selection)))

(defn text
  "Returns the text value of a htmltree node
   and its contents."
  ([]
     (fn [selection]
       (text* selection)))
  ([selection]
     (text* selection)))

(defn compose
  "Executes the supplied functions in left to right order
   on a htmltree node."
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

;############
;##  MAIN  ##
;############

(defn run-on
  "Runs an extraction (a paired selector and extractor)
   on a source htmltree."
  ([source]
     (fn [extraction]
       (let [[selector extractor] extraction
             selection (select selector source)]
         (case (count selection)
           0 nil
           1 (extractor (first selection))
           (extractor selection)))))
  ([source extraction]
     ((run-on source) extraction)))

(defn run-all-on
  "Runs all extractions supplied to 'extract'
   or 'extract-from' on a source htmltree."
  [source extractions]
  (case (count extractions)
    0 source
    1 (run-on source (first extractions))
    (map (run-on source) extractions)))

(defn extract
  "Accepts a source htmltree, a vector of keys which may
   be empty, and a variable number of extractions (a
   selector followed by an extractor).

   If a vector of keys is supplied, they are zipmapped to
   the result of the tasks upon the source.  If no keys
   are supplied, a seq of the results is returned."
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
  "Behaves like extract, but before extracting it uses
   the provided selector to narrow down the data to be
   searched.

   Very useful when one wants to narrow down to a seq
   of items and then extract keys from each of those
   items."
  [raw-source selector ks & raw-extractions]
  (let [sources (select selector raw-source)]
    (for [source sources]
      (apply (partial extract source ks) raw-extractions))))
