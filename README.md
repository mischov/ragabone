# Ragabone

### **Ragabone is deprecated in favor of [Reaver](https://github.com/mischov/reaver).**

Ragabone is a Clojure library for extracting data out of HTML and into edn.

For example, here is how you would scrape the story headlines and links from the Clojure subreddit.

```clojure
(require '[ragabone :as r])

; get the subreddit HTML
(def clojure-reddit (slurp "http://www.reddit.com/r/clojure"))

; extract the titles and urls from the HTML into a seq of Clojure maps.
(r/extract-from (r/parse clojure-reddit) ".sitetable .thing"
                [:headline :url]
                ".title a.title" (r/text)
                ".title a.title" (r/attr :href))

;> ({:headline "...", :url "..."}, {:headline "...", :url "..."} ...)
```
## Contents

- [Installation](#installation)
- [Rationale](#rationale)

## Installation

Add the following dependency to your project.clj file:

```clojure
[ragabone "0.0.10"]
```
[**Back To Top ⇧**](#contents)

## Rationale

Both of the libraries most commonly used to extract data from HTML, Enlive and Laser, are primarily templating libraries.

Ragabone focuses exclusively on extracting data out of HTML and into Clojure data structures, and this focus means that the data-extraction code you write is simpler and more maintainable than the equivilant Enlive or Laser code.

But talk is cheap... so here's an example.

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

Here is what it would have looked like had he used Ragabone.

```clojure
(ns tablescrape.ragabone
  (:require [ragabone :as r]))

;; In Ragabone fetching and extracting html are separate concerns.

(def html (slurp "http://www.multpl.com/table?f=m"))

;; The Working Example; again, imagine date-parser and parse-money were defined.

(r/extract-from (r/parse html) "tbody tr"
                []
                "td.left" (r/compose r/text (date-parser "MMM dd, yyyy"))
                "td.right" (r/compose r/text parse-money))
```
[**Back To Top ⇧**](#contents)

## License

Copyright © 2014 Mischov

Distributed under the Eclipse Public License.
