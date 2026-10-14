;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.shapes
  "Shape helpers shared by the governance rules. Pure functions over the
   audit context (pages with their objects map)."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- Numbers

(defn ->num
  "Parses numbers stored as strings (\"14\", \"14px\", \"1.5\") or numbers."
  [v]
  (cond
    (number? v) (double v)
    (string? v) (d/parse-double (str/replace v #"[^0-9.\-]" "") nil)
    :else nil))

(defn same-num?
  [a b]
  (and (some? a) (some? b) (< (abs (- a b)) 0.01)))

(defn fmt-num
  "Number without trailing zeros, for messages."
  [n]
  (let [r (/ (Math/round (* (double n) 100)) 100.0)]
    (if (== r (Math/floor r))
      (str (long r))
      (str r))))

;; --- Traversal

(defn root?
  [shape]
  (= uuid/zero (:id shape)))

(defn auditable?
  [ctx shape]
  (and (some? shape)
       (not (root? shape))
       (not (and (get-in ctx [:options :skip-hidden?])
                 (:hidden shape)))))

(defn page-shapes
  "Seq of [page shape] for every auditable shape of the context."
  [ctx]
  (for [page  (:pages ctx)
        shape (vals (:objects page))
        :when (auditable? ctx shape)]
    [page shape]))

(defn children
  "Children of `shape`, in z-order (bottom first)."
  [objects shape]
  (keep #(get objects %) (:shapes shape)))

(defn containers
  "Shapes that hold children (root, boards, groups, bools excluded)."
  [objects]
  (filter #(seq (:shapes %)) (vals objects)))

(defn layout?
  "Children positioned by flex/grid layout."
  [shape]
  (contains? #{:flex :grid} (:layout shape)))

(defn in-copy?
  "Inside a component copy (names and structure come from the main)."
  [shape]
  (some? (:shape-ref shape)))

(defn ancestors-of
  "Ids of the ancestors of `shape`, nearest first, root included."
  [objects shape]
  (loop [id  (:parent-id shape)
         out []]
    (if (or (nil? id) (contains? (set out) id))
      out
      (let [parent (get objects id)]
        (if (or (nil? parent) (root? parent))
          (conj out id)
          (recur (:parent-id parent) (conj out id)))))))

(defn top-board
  "Top-level board (a screen) containing `shape`, the shape itself when it
   is one, or nil for shapes outside any board."
  [objects shape]
  (->> (cons (:id shape) (ancestors-of objects shape))
       (keep #(get objects %))
       (filter cfh/frame-shape?)
       (remove root?)
       (last)))

;; --- Geometry

(defn bounds
  [shape]
  (let [{:keys [x y width height]} (or (:selrect shape) shape)]
    {:x (or x 0) :y (or y 0) :width (or width 0) :height (or height 0)}))

(defn contains-point?
  [{:keys [x y width height]} [px py]]
  (and (<= x px (+ x width))
       (<= y py (+ y height))))

(defn center
  [{:keys [x y width height]}]
  [(+ x (/ width 2)) (+ y (/ height 2))])

;; --- Text

(defn text-nodes
  "Leaf text nodes of a text shape (with their own style attrs)."
  [shape]
  (when (cfh/text-shape? shape)
    (txt/node-seq txt/is-text-node? (:content shape))))

(def weight-names
  {"thin" 100 "extralight" 200 "light" 300 "regular" 400 "normal" 400
   "medium" 500 "semibold" 600 "bold" 700 "extrabold" 800 "black" 900})

(defn ->weight
  [v]
  (or (->num v)
      (some-> v str str/lower (get weight-names) double)))

(defn node-style
  "Typography of a text node as numbers."
  [node]
  {:size (->num (:font-size node))
   :weight (->weight (:font-weight node))
   :line-height (->num (:line-height node))})

(defn node-color
  "First solid fill of a text node: [hex opacity] or nil."
  [node]
  (some (fn [fill]
          (when (and (:fill-color fill)
                     (nil? (:fill-color-gradient fill))
                     (nil? (:fill-image fill)))
            [(:fill-color fill) (or (:fill-opacity fill) 1)]))
        (:fills node)))
