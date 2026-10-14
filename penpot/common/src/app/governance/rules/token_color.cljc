;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.token-color
  "Rule `token/color`: every solid fill and stroke color must come from
   a color token (or, optionally, a library color style).

   For each shape the rule inspects:
   - `:fills` (or the fills inside text nodes for text shapes), governed
     by the applied token under `[:applied-tokens :fill]`;
   - `:strokes`, governed by `[:applied-tokens :stroke-color]`.

   A literal color that matches an active color token is reported as
   fixable (replace literal by token). A literal with no token is
   reported as not fixable."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.governance.context :as gctx]
   [app.governance.rule :as rule]
   [cuerdas.core :as str]))

(def rule-id "token/color")

;; --- Candidate ranking
;;
;; One hex value often maps to several tokens (#FFFFFF can be a surface,
;; an inverse text and a palette step). The suggestion must be the token
;; a designer would pick, so candidates are ranked deterministically:
;; semantic tokens before palette tokens, then the role that matches the
;; attribute (text, border or background), then by name.

(def default-color-roles
  "Role segments (second segment of `color.<role>.*`) preferred per slot.
   Overridable with the `:color-roles` option."
  {:text   #{"text"}
   :stroke #{"border"}
   :fill   #{"bg" "action" "feedback"}})

(defn- slot-kind
  [shape attribute]
  (cond
    (= attribute :stroke-color) :stroke
    (cfh/text-shape? shape)     :text
    :else                       :fill))

(defn rank-candidates
  "Sorts token names from best to worst suggestion for a slot kind."
  [names kind roles]
  (let [preferred (get roles kind #{})]
    (vec (sort-by (fn [name]
                    (let [[root role] (str/split name ".")]
                      [(if (= root "color") 0 1)
                       (if (contains? preferred role) 0 1)
                       name]))
                  names))))

;; --- Slots

(defn- fill-slots
  "Solid fill colors of a shape. Text shapes keep fills inside their
   content nodes, so those are flattened here."
  [shape]
  (let [fills (if (cfh/text-shape? shape)
                (->> (txt/node-seq txt/is-text-node? (:content shape))
                     (mapcat :fills))
                (:fills shape))]
    (->> fills
         (filter #(and (:fill-color %)
                       (nil? (:fill-color-gradient %))
                       (nil? (:fill-image %))))
         (map (fn [fill]
                {:attribute :fill
                 :raw (:fill-color fill)
                 :color (gctx/normalize-color (:fill-color fill))
                 :ref-id (:fill-color-ref-id fill)})))))

(defn- stroke-slots
  [shape]
  (->> (:strokes shape)
       (filter :stroke-color)
       (map (fn [stroke]
              {:attribute :stroke-color
               :raw (:stroke-color stroke)
               :color (gctx/normalize-color (:stroke-color stroke))
               :ref-id (:stroke-color-ref-id stroke)}))))

(defn- governed?
  "A slot is governed when its attribute has an applied token or, when
   allowed, when it references a library color."
  [ctx shape slot]
  (or (some? (get-in shape [:applied-tokens (:attribute slot)]))
      (and (get-in ctx [:options :allow-library-colors?])
           (some? (:ref-id slot)))))

;; --- Evaluation

(defn- slot->result
  [ctx rule page-id shape {:keys [attribute raw color]}]
  (let [roles      (merge default-color-roles (get-in ctx [:options :color-roles]))
        candidates (some-> (get-in ctx [:color-index color])
                           (rank-candidates (slot-kind shape attribute) roles))
        expected   (first candidates)
        attr-label (if (= attribute :fill) "fill" "stroke")]
    (if (some? expected)
      (rule/make-result
       rule page-id shape
       {:attribute attribute
        :message (str "The " attr-label " color " raw " is applied as a literal value.")
        :message-args [raw]
        :recommendation (str "Replace the literal value by the token " expected ".")
        :recommendation-args [expected]
        :current-value raw
        :expected-value expected
        :candidates candidates
        :fix-available true
        :fix {:type :apply-color-token
              :attribute attribute
              :token-name expected
              :current-value raw}})
      (rule/make-result
       rule page-id shape
       {:attribute attribute
        :message (str "The " attr-label " color " raw " does not exist in the design system.")
        :message-args [raw]
        :recommendation "Use an existing color token or ask the design system team to add one."
        :current-value raw
        :fix-available false}))))

(defn- evaluate-shape
  "Returns [results stats] for one shape."
  [ctx rule page-id shape]
  (let [slots (concat (fill-slots shape) (stroke-slots shape))
        {:keys [governed literal]}
        (group-by (fn [slot] (if (governed? ctx shape slot) :governed :literal)) slots)
        ;; One result per (attribute, color): a text with many spans of the
        ;; same literal is a single problem.
        results (->> literal
                     (filter :color)
                     (reduce (fn [acc slot]
                               (let [k [(:attribute slot) (:color slot)]]
                                 (if (contains? acc k)
                                   acc
                                   (assoc acc k (slot->result ctx rule page-id shape slot)))))
                             {})
                     (vals))]
    [results
     {:checked (count slots)
      :governed (count governed)
      :literal (count literal)}]))

(defn- auditable?
  [ctx shape]
  (and (some? shape)
       (not= uuid/zero (:id shape))
       (not (and (get-in ctx [:options :skip-hidden?])
                 (:hidden shape)))))

(defn evaluate
  [ctx rule]
  (let [[results stats]
        (reduce (fn [[results stats] page]
                  (reduce (fn [[results stats] shape]
                            (if (auditable? ctx shape)
                              (let [[rs st] (evaluate-shape ctx rule (:id page) shape)]
                                [(into results rs)
                                 (merge-with + stats st)])
                              [results stats]))
                          [results stats]
                          (vals (:objects page))))
                [[] {:checked 0 :governed 0 :literal 0}]
                (:pages ctx))
        checked (:checked stats)]
    (rule/make-outcome
     results
     (assoc stats
            :violations (count results)
            :adherence (if (pos? checked)
                         (/ (:governed stats) checked)
                         1)))))

(def rule
  {:id rule-id
   :category "token"
   :title "Color token usage"
   :description "Fills and strokes must use color tokens instead of literal values."
   :severity :medium
   :evaluate evaluate})
