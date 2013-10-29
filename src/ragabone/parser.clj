(ns ragabone.parser
  (:require [clojure.string :as string])
  (:import [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Attributes Comment DataNode
            Document DocumentType Element Node TextNode XmlDeclaration]
           [org.jsoup.parser Parser Tag]
           (java.io ByteArrayInputStream ByteArrayOutputStream)))

; This namespace owes it's existance to David
; Santiago's library Hickory. Any parts of it
; not directly borrowed were inspired by that
; library, and I owe David a debt of gratitude
; for figuring this all out so I did not have to.

(defn ^:private to-lowercase-keyword
  "Converts a string into a lowercase keyword."
  [s]
  (-> s string/lower-case keyword))

(defprotocol HtmlTree
  "David Santiago's library Hickory introduced what he called
   an 'HTML DOM node map' and I'll just call an htmltree.  It
   is the best representation of HTML in Clojure I've yet to
   encounter so I'm going to use it.

   An htmltree is either a map or a string (strings for text
   or CDATA).  Maps contain some subset of the keys:

     :type     - [:comment, :document, :document-type, :element]
     :tag      - node's tag, if applicable
     :attrs    - node's attributes as a map, if applicable
     :content  - node's child nodes in a vector, if applicable"
  (to-htmltree [data]
    "Transforms jsoup (+ one or two other things) into an htmltree."))

(extend-protocol HtmlTree
  Attribute
  (to-htmltree [jsoup] [(to-lowercase-keyword (.getKey jsoup))
                        (.getValue jsoup)])
  Attributes
  (to-htmltree [jsoup] (not-empty (into {} (map to-htmltree jsoup))))
  Comment
  (to-htmltree [jsoup] {:type :comment
                           :content [(.getData jsoup)]})
  DataNode
  (to-htmltree [jsoup] (str jsoup))
  Document
  (to-htmltree [jsoup] {:type :document
                           :content (not-empty
                                     (into [] (map to-htmltree
                                                   (.childNodes jsoup))))})
  DocumentType
  (to-htmltree [jsoup] {:type :document-type
                           :attrs (to-htmltree (.attributes jsoup))})
  Element
  (to-htmltree [jsoup] {:type :element
                           :attrs (to-htmltree (.attributes jsoup))
                           :tag (to-lowercase-keyword (.tagName jsoup))
                           :content (not-empty
                                     (into [] (map to-htmltree
                                                   (.childNodes jsoup))))})
  TextNode
  (to-htmltree [jsoup] (.getWholeText jsoup))
  clojure.lang.PersistentVector
  (to-htmltree [pvector] (map to-htmltree pvector)))

(defn parse
  "Parses HTML into htmltrees"
  [html]
  (when html
    (let [jsoup (into [] (Parser/parseFragment html nil ""))]
      (to-htmltree jsoup))))
