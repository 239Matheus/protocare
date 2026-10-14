;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.accessibility
  "Accessibility rules (WCAG 2.2, deterministic):

   - `accessibility/contrast`     text contrast (1.4.3, AA): 4.5:1, or
                                  3:1 for large text (>= 24px, or >= 18.66px bold);
   - `accessibility/text-size`    font size below the configured minimum;
   - `accessibility/touch-target` interactive targets below the minimum
                                  size (2.5.8, AA: 24x24)."
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.color :as clr]
   [app.governance.context :as gctx]
   [app.governance.rule :as rule]
   [app.governance.shapes :as gsh]))

(defn- outcome
  [ctx f]
  (let [results (vec (mapcat (fn [[page shape]] (f page shape)) (gsh/page-shapes ctx)))]
    (rule/make-outcome results {:violations (count results)})))

;; --- Contrast math

(defn- channel
  [c]
  (let [c (/ c 255.0)]
    (if (<= c 0.03928)
      (/ c 12.92)
      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn luminance
  [hex]
  (let [[r g b] (clr/hex->rgb hex)]
    (+ (* 0.2126 (channel r)) (* 0.7152 (channel g)) (* 0.0722 (channel b)))))

(defn contrast-ratio
  [a b]
  (let [la (luminance a)
        lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn- blend
  "`fg` with `opacity` over the opaque `bg`, as hex."
  [fg opacity bg]
  (if (>= opacity 0.999)
    fg
    (let [[r1 g1 b1] (clr/hex->rgb fg)
          [r2 g2 b2] (clr/hex->rgb bg)
          mix        (fn [a b] (Math/round (+ (* opacity a) (* (- 1 opacity) b))))]
      (clr/rgb->hex [(mix r1 r2) (mix g1 g2) (mix b1 b2)]))))

;; --- Background resolution

(defn- fill-kind
  "How a shape paints its area: [:solid hex], :unknown (image, gradient or
   translucent fill) or nil (no fill)."
  [shape]
  (when-let [fill (first (:fills shape))]
    (cond
      (or (:fill-image fill) (:fill-color-gradient fill)) :unknown
      (and (:fill-color fill)
           (>= (or (:fill-opacity fill) 1) 0.999)
           (>= (or (:opacity shape) 1) 0.999))           [:solid (:fill-color fill)]
      :else :unknown)))

(defn background-of
  "Opaque color behind the center of `shape`, or nil when it cannot be
   known (images, gradients, translucency, nothing behind)."
  [page shape]
  (let [objects (:objects page)
        point   (gsh/center (gsh/bounds shape))]
    (loop [current shape]
      (let [parent (get objects (:parent-id current))]
        (if (or (nil? parent) (= (:id parent) (:id current)))
          (some-> (:background page) gctx/normalize-color)
          (let [siblings (gsh/children objects parent)
                below    (reverse (take-while #(not= (:id %) (:id current)) siblings))
                hit      (some (fn [s]
                                 (when (and (not (:hidden s))
                                            (gsh/contains-point? (gsh/bounds s) point))
                                   (when-let [kind (fill-kind s)] kind)))
                               below)]
            (cond
              (vector? hit)    (gctx/normalize-color (second hit))
              (= :unknown hit) nil
              :else
              (let [kind (when-not (gsh/root? parent) (fill-kind parent))]
                (cond
                  (vector? kind)    (gctx/normalize-color (second kind))
                  (= :unknown kind) nil
                  (gsh/root? parent) (some-> (:background page) gctx/normalize-color)
                  :else (recur parent))))))))))

(defn- large-text?
  [{:keys [size weight]}]
  (or (>= (or size 0) 24)
      (and (>= (or size 0) 18.66) (>= (or weight 400) 700))))

(defn- evaluate-contrast
  [ctx rule]
  (outcome
   ctx
   (fn [page shape]
     (when (cfh/text-shape? shape)
       (when-let [bg (background-of page shape)]
         (let [checks (->> (gsh/text-nodes shape)
                           (keep (fn [node]
                                   (when-let [[color opacity] (gsh/node-color node)]
                                     (let [fg       (gctx/normalize-color (blend color opacity bg))
                                           style    (gsh/node-style node)
                                           required (if (large-text? style) 3.0 4.5)
                                           ratio    (contrast-ratio fg bg)]
                                       {:fg fg :ratio ratio :required required}))))
                           (filter #(< (:ratio %) (:required %)))
                           (sort-by :ratio))]
           (when-let [{:keys [fg ratio required]} (first checks)]
             (let [ratio-text (str (gsh/fmt-num ratio) ":1")
                   req-text   (str (gsh/fmt-num required) ":1")]
               [(rule/make-result
                 rule (:id page) shape
                 {:attribute :contrast
                  :message (str "Text " fg " on " bg " has contrast " ratio-text "; WCAG AA requires " req-text ".")
                  :message-args [fg bg ratio-text req-text]
                  :recommendation "Use a darker text color or a lighter background (or the opposite)."
                  :current-value ratio-text
                  :expected-value (str ">= " req-text)
                  :fix-available false})]))))))))

(def contrast-rule
  {:id "accessibility/contrast"
   :category "accessibility"
   :title "Text contrast"
   :description "Text must meet WCAG 2.2 AA contrast against its background."
   :severity :high
   :evaluate evaluate-contrast})

;; --- Text size

(defn- evaluate-text-size
  [ctx rule]
  (let [min-size (get-in ctx [:options :min-font-size] 12)]
    (outcome
     ctx
     (fn [page shape]
       (let [sizes    (seq (keep (comp :size gsh/node-style) (gsh/text-nodes shape)))
             smallest (when sizes (apply min sizes))]
         (when (and smallest (< smallest min-size))
           (let [current (str (gsh/fmt-num smallest) "px")
                 minimum (str (gsh/fmt-num min-size) "px")]
             [(rule/make-result
               rule (:id page) shape
               {:attribute :font-size
                :message (str "Text size " current " is below the minimum of " minimum ".")
                :message-args [current minimum]
                :recommendation "Use a larger text style from the design system."
                :current-value current
                :expected-value (str ">= " minimum)
                :fix-available false})])))))))

(def text-size-rule
  {:id "accessibility/text-size"
   :category "accessibility"
   :title "Minimum text size"
   :description "Text must not be smaller than the configured minimum."
   :severity :medium
   :evaluate evaluate-text-size})

;; --- Touch target

(def ^:private interactive-component-re
  #"(?i)button|bot[aã]o|input|campo|link|checkbox|switch|toggle|tab|radio")

(defn- interactive?
  [ctx shape]
  (or (seq (:interactions shape))
      (when (:component-root shape)
        (let [{:keys [name path]} (get-in ctx [:components (:component-id shape)])]
          (re-find interactive-component-re (str path " " name))))))

(defn- evaluate-touch-target
  [ctx rule]
  (let [min-size (get-in ctx [:options :min-touch-target] 24)]
    (outcome
     ctx
     (fn [page shape]
       (when (interactive? ctx shape)
         (let [{:keys [width height]} (gsh/bounds shape)]
           (when (or (< width min-size) (< height min-size))
             (let [current (str (gsh/fmt-num width) "x" (gsh/fmt-num height))
                   minimum (str (gsh/fmt-num min-size) "x" (gsh/fmt-num min-size))]
               [(rule/make-result
                 rule (:id page) shape
                 {:attribute :size
                  :message (str "Interactive target " current " is smaller than " minimum ".")
                  :message-args [current minimum]
                  :recommendation "Grow the target or its padding (44x44 is recommended on touch screens)."
                  :current-value current
                  :expected-value (str ">= " minimum)
                  :fix-available false})]))))))))

(def touch-target-rule
  {:id "accessibility/touch-target"
   :category "accessibility"
   :title "Touch target size"
   :description "Interactive elements must meet the minimum target size."
   :severity :medium
   :evaluate evaluate-touch-target})

(def rules
  [contrast-rule text-size-rule touch-target-rule])
