;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.layout
  "Interface quality rules (Gestalt), deterministic:

   - `layout/alignment` a sibling whose left or top edge almost matches
                        the edge shared by other siblings (0.5–3px apart):
                        a near miss reads as an error (Gestalt:
                        continuity, common region);
   - `layout/spacing`   in a column of stacked siblings, a gap that is
                        almost the gap used elsewhere in the column
                        (Gestalt: proximity, rhythm).

   Both rules report only the odd one out: the element with less support
   (fewer siblings sharing its edge or gap). Ties go to the element that
   comes later, and for gaps a value on the spacing scale wins. Gaps and
   edges that are clearly different are intentional and never reported:
   text boxes have line-height based sizes, so an off-the-scale check
   alone would flag almost every text.

   Only free-positioned containers are checked: children of flex/grid
   layouts are placed by the layout, and the canvas root is not a UI."
  (:require
   [app.governance.rule :as rule]
   [app.governance.shapes :as gsh]))

(defn- free-containers
  "[page container children] for containers whose children are placed
   by hand (no layout, not the canvas root, not inside a copy)."
  [ctx]
  (for [page      (:pages ctx)
        container (gsh/containers (:objects page))
        :when (and (not (gsh/root? container))
                   (not (gsh/layout? container))
                   (not (gsh/in-copy? container)))
        :let [kids (->> (gsh/children (:objects page) container)
                        (remove :hidden)
                        (vec))]
        :when (> (count kids) 1)]
    [page container kids]))

;; --- Odd one out

(defn- near-miss?
  [a b tolerance]
  (let [d (abs (- a b))]
    (and (>= d 0.5) (<= d tolerance))))

(defn- odd-one-out
  "For a vector of numbers, returns {index best-other-index} for the
   values that nearly match a value with more support. `rank` returns a
   comparable for an index (higher wins)."
  [values tolerance rank]
  (into {}
        (keep (fn [i]
                (let [vi     (nth values i)
                      better (->> (range (count values))
                                  (filter #(and (not= i %)
                                                (near-miss? vi (nth values %) tolerance)
                                                (pos? (compare (rank %) (rank i)))))
                                  (sort-by rank #(compare %2 %1))
                                  (first))]
                  (when better [i better]))))
        (range (count values))))

(defn- support
  "How many values are the same as the value at `i` (itself included)."
  [values i]
  (count (filter #(gsh/same-num? % (nth values i)) values)))

(defn- first-index
  [values i]
  (first (filter #(gsh/same-num? (nth values %) (nth values i)) (range (count values)))))

;; --- Alignment

(defn- edge-hits
  "{shape-index [other-index edge]} for one edge of the siblings."
  [kids edge tolerance]
  (let [values (mapv #(get (gsh/bounds %) (if (= edge :left) :x :y)) kids)
        rank   (fn [i] [(support values i) (- (first-index values i))])]
    (update-vals (odd-one-out values tolerance rank) (fn [o] [o edge]))))

(defn- evaluate-alignment
  [ctx rule]
  (let [tolerance (get-in ctx [:options :alignment-tolerance] 3)
        results
        (for [[page _ kids] (free-containers ctx)
              :let [hits (merge (edge-hits kids :top tolerance)
                                (edge-hits kids :left tolerance))]
              [i [o edge]] (sort-by key hits)]
          (let [shape (nth kids i)
                other (nth kids o)
                key*  (if (= edge :left) :x :y)
                d     (abs (- (get (gsh/bounds shape) key*) (get (gsh/bounds other) key*)))
                px    (str (gsh/fmt-num d) "px")
                edge* (name edge)]
            (rule/make-result
             rule (:id page) shape
             {:attribute :position
              :message (str "\"" (:name shape) "\" is " px " off the " edge* " edge of \"" (:name other) "\".")
              :message-args [(:name shape) px edge* (:name other)]
              :recommendation "Align it exactly, or move it clearly apart if the difference is intended."
              :current-value px
              :expected-value "0px"
              :fix-available false})))]
    (rule/make-outcome results {:violations (count results)})))

(def alignment-rule
  {:id "layout/alignment"
   :category "layout"
   :title "Near-miss alignment"
   :description "An element almost aligned with its siblings should align exactly."
   :severity :low
   :evaluate evaluate-alignment})

;; --- Vertical rhythm (consistent gaps)

(defn- columns
  "Groups shapes whose left edges are within `tolerance`, each column
   sorted top to bottom."
  [shapes tolerance]
  (->> shapes
       (sort-by (comp :x gsh/bounds))
       (reduce (fn [cols shape]
                 (let [x    (:x (gsh/bounds shape))
                       last (peek cols)]
                   (if (and last (<= (abs (- x (:x (gsh/bounds (first last))))) tolerance))
                     (conj (pop cols) (conj last shape))
                     (conj cols [shape]))))
               [])
       (map #(sort-by (comp :y gsh/bounds) %))))

(defn- column-gaps
  "[shape-below gap] for each pair of stacked shapes of a column, keeping
   gaps a reader perceives as spacing (positive, up to `max-gap`)."
  [column max-gap]
  (for [[prev next] (partition 2 1 column)
        :let [pb  (gsh/bounds prev)
              nb  (gsh/bounds next)
              gap (- (:y nb) (+ (:y pb) (:height pb)))]
        :when (and (> gap 0.5) (<= gap max-gap))]
    [next gap]))

(defn- evaluate-spacing
  [ctx rule]
  (let [scale     (:spacing-scale ctx)
        tolerance (get-in ctx [:options :alignment-tolerance] 3)
        max-gap   (get-in ctx [:options :max-rhythm-gap] 48)
        on-scale? (fn [gap] (boolean (some #(gsh/same-num? % gap) scale)))
        results
        (for [[page _ kids] (free-containers ctx)
              column        (columns kids tolerance)
              :let [pairs (vec (column-gaps column max-gap))
                    gaps  (mapv second pairs)
                    rank  (fn [i] [(support gaps i)
                                   (if (on-scale? (nth gaps i)) 1 0)
                                   (- (first-index gaps i))])]
              [i o] (sort-by key (odd-one-out gaps tolerance rank))]
          (let [next     (first (nth pairs i))
                current  (str (gsh/fmt-num (nth gaps i)) "px")
                expected (str (gsh/fmt-num (nth gaps o)) "px")]
            (rule/make-result
             rule (:id page) next
             {:attribute :gap
              :message (str "Gap of " current " above \"" (:name next) "\" is almost the "
                            expected " used in the rest of the column.")
              :message-args [current (:name next) expected]
              :recommendation (str "Use the same gap (" expected ") so the rhythm is consistent.")
              :recommendation-args [expected]
              :current-value current
              :expected-value expected
              :fix-available false})))]
    (rule/make-outcome results {:violations (count results)})))

(def spacing-rule
  {:id "layout/spacing"
   :category "layout"
   :title "Vertical rhythm"
   :description "Stacked elements should keep a consistent gap."
   :severity :low
   :evaluate evaluate-spacing})

(def rules
  [alignment-rule spacing-rule])
