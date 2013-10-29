# Ragabone

Ragabone is a library for extracting data out of HTML and into Clojure data structures.

For example, here is how you would scrape the story headlines and links from the Clojure subreddit.

```clojure
(require '[ragabone :as r])

; get the subreddit HTML
(def clojure-reddit (slurp "http://www.reddit.com/r/clojure"))

;;extract the titles and urls from HTML into a seq of Clojure maps
(r/extract-from (r/parse clojure-reddit) [:.sitetable :.thing]
                [:title :url]
                [:.title :a] (r/text)
                [:.title :a] (r/attr :href))

;> ({:url "..." :title "..."} {:url "..." :title "..."} ...)
```
## Contents

- [Installation](#installation)
- [Rationale](#rationale)
- [Quick Start](#quick-start)
    - [API](#api)
        - [parse](#parse)
        - [extract](#extract)
        - [extract-from](#extract-from)
    - [Selectors](#selectors)
        - [Faux-CSS Selectors](#faux-css-selectors)
        - [Functional Selectors](#functional-selectors)
    - [Extractors](#extractors)
- [Acknowledgements](#acknowledgements)

## Installation

Add the following dependency to your project.clj file:

```clojure
[ragabone "0.0.9"]
```
[**Back To Top ⇧**](#contents)

## Rationale

You can already extract data from HTML with Enlive or Laser, so why bother with Ragabone?

Enlive and Laser are wonderful, useful libraries, but they are first-and-foremost templating libraries. Ragabone focuses entirely on extracting data out of HTML and into Clojure data strucures.

This focus means that the data-extraction code you write using Ragabone is simpler and more maintainable than the code you would need to write to make Enlive or Laser do the same thing. Let me give you an example.

In his article [Scraping HTML Table Data in Clojure for Profit](http://blog.safaribooksonline.com/2013/09/09/scraping-html-table-data-in-clojure-for-profit/), Timothy Pratley uses Enlive to scrape his data. Here is a truncated version of how he does it.

```clojure
(ns tablescrape.enlive
  (:require [net.cgrand.enlive-html :refer :all]))
	    
;; Enlive bits
	    
(defn contents [x]
  (map (comp first :content) x))

(defn- parse-fs [fs ds]
  (for [[f d] (map vector fs ds)
        :when f]
    (f d)))

(defn- parse-table
  [table fs]
  (for [tr (select table [:tr])
        :let [row (contents (select tr [:td]))]
        :when (seq row)]
    (parse-fs fs row)))

(defn scrape-table
  "Scrapes data from a HTML table at url with CSS selector.
  fs are the parsing functions to use per column, nil indicates skip."
  [url selector fs]
  (parse-table
   (select
    (html-resource (java.net.URL. url))
    selector)
   fs))

;; The Working Example; imagine date-parser and parse-money were defined.

(scrape-table "http://www.multpl.com/table?f=m"
              [:table#datatable]
              [(date-parser "MMM dd, yyyy") parse-money])
```

Here is what it would haved looked like if he had used Ragabone.

```clojure
(ns tablescrape.ragabone
  (:require [ragabone :as r]))

;; In Ragabone fetching and extracting html are separate concerns.

(def html (slurp "http://www.multpl.com/table?f=m"))

;; The Working Example; again, imagine date-parser and parse-money were defined.

(r/extract-from (r/parse html) [:tbody :tr]
                []
                [:td.left] (r/compose r/text (date-parser "MMM dd, yyyy"))
                [:td.right] (r/compose r/text parse-money))
```
[**Back To Top ⇧**](#contents)

## Quick Start

### API

#### parse

Before a string of HTML can be used by Ragabone, it needs to be parsed into the htmltree structure.

To do this, call the ``parse`` function on the string of html.

```clojure
(def html (slurp "http://www.google.com"))

(parse html)
```
Ragabone also includes a convinience function, ``from-file``, for parsing files or resources.

```clojure
(parse (from-file "index.html"))
```
[**Back To Top ⇧**](#contents)

#### extract

``extract`` accepts an htmltree or htmltrees, a vector of keys (which can be empty or nil), and a variable number of extractions (a paired selector and extractor).  

If keys are supplied, ``extract`` returns a map of the data extracted from the htmltree(s).  If no keys are supplied, ``extract`` returns a seq of the extracted data.

```clojure
(def html
  (parse "<html><body><div class=\"main\">Content</div></body></html>"))

(extract html
         [:scraped]
         [:.main] (text))

;> {:scraped "Content"}

(extract html
         []
         [:.main] (text))

;> ("Content")
```
[**Back To Top ⇧**](#contents)

#### extract-from

``extract-from`` behaves like ``extract``, except that ``extract-from`` takes a selector as one of the initial parameters, and that selector is then used to narrow down the data before extractions are applied to it.

```clojure
(def html
  (parse "<div><span class=\"item\">1</span><span class=\"item\">2</span></div>"))

(extract html
         [:scraped]
         [:.item] (text)

;> {:scraped ("1" "2")}

(extract-from html [:.item] ; [:.item] is the selector
                   [:scraped]
                   [:.item] (text))

;> ({:scraped "1"} {:scraped "2"})
```
[**Back To Top ⇧**](#contents)

### Selectors

Ragabone supports both faux-CSS selectors and functional selectors. You may use one or the other within a given selector, but not both.

#### Faux-CSS Selectors

Faux-CSS selectors, as popularized by Enlive, are a vector of keywords in which each keyword consists of a tag, an id, and a variable number of classes. This order (tag, then id, then classes) must be maintained, but any of those elements may be omitted.

```clojure 
; Selects all divs
[:div]

; Selects all tags with the id "hasId"
[:#hasId]

; Selects all tags with the class "unstyled"
[:.unstyled]

; Selects all divs with the id "hasId" and the classes "of", "an",
; and "ox"
[:div#hasId.of.an.ox]

; Selects all tags with the class "item" who also have a parent tag
; that is a ul
[:ul :.item]
```
[**Back To Top ⇧**](#contents)

#### Functional Selectors

Functional selectors compose nicely and allow some functionality that the faux-CSS selectors do not.

The functional selectors are ``tag=``, ``attr=``, ``class=``, ``id=``, ``any``, ``not``, ``and``, ``or``, and ``child-of``.

```clojure
; Selects all divs
(tag= :div)

; Selects all tags with the id "hasId"
(id= "hasId")

; Selects all tags with the class "unstyled"
(class= "unstyled")

; Selects all divs with the id "hasId" and the classes "of", "an",
; and "ox"
(and (tag= :div) (id= "hasId") (class= "of" "an" "ox")) 

; Selects all tags with the class "item" who also have a parent tag
; that is a ul.
(child-of (tag= :ul) (class= "item")) 

; Selects all tags which have either the class "even" or the class 
; "odd" (or both)
(or (class= "even") (class= "odd")) 

; Selects all li tags who are not children of ol tags.
(child-of (not (tag= :ol)) (tag= :li)) 
```
[**Back To Top ⇧**](#contents)

### Extractors

Extractors help you to extract whatever piece of data you are interested in out of an htmltree node.

These extractors are ``tag``, ``attr``, ``attrs``, ``text``, ``node``, ``compose``, and ``nth``.

```clojure
;; Example data
(def html
  (parse "<a class=\"clickable\" href=\"https://www.google.com/\">Google</a>"))


; Returns :a
(tag html)

; Returns "https://www.google.com"
((attr :href) html)

; Returns {:class "clickable" :href "https://www.google.com"}
(attrs html) 

; Returns "Google"
(text html)

; Returns the harvestable representing the whole <a> node
(node html)

; Returns "GOOGLE"
((compose text clojure.string/uppercase) html)
```
[**Back To Top ⇧**](#contents)

## Acknowledgements

A lot of the code in this library is inspired by or copied/adapted from [Laser](https://github.com/Raynes/laser), [Hickory](https://github.com/davidsantiago/hickory), and [Tinsel](https://github.com/davidsantiago/tinsel). 

Without the example of these libraries there would be no Harvester, so a big "Thank you!" to David Santiago and Anthony Grimes.

[**Back To Top ⇧**](#contents)

## License

Copyright © 2013 Mischov

Distributed under the Eclipse Public License, the same as Clojure.
