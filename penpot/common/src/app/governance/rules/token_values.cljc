;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.token-values
  "Design System rules for numeric and typographic values:

   - `token/spacing`    layout padding and gap without spacing tokens;
   - `token/radius`     corner radius without radius tokens;
   - `token/typography` text styles without a typography token.

   A literal that equals an active token is fixable: the fix only binds
   the token, the visual value stays the same."
  (:require
   [app.common.files.helpers :as cfh]
   [app.governance.context :as gctx]
   [app.governance.rule :as rule]
   [app.governance.shapes :as gsh]
   [cuerdas.core :as str]))

;; --- Helpers

(defn- literal-slots
  "[[attr value] ...] of non-zero values whose attr has no applied token."
  [shape attrs->values]
  (->> attrs->values
       (keep (fn [[attr value]]
               (let [n (gsh/->num value)]
                 (when (and (some? n)
                            (not (zero? n))
                            (nil? (get-in shape [:applied-tokens attr])))
                   [attr n]))))))

(defn- slots->text
  [slots]
  (str/join " · " (map (fn [[attr n]] (str (name attr) " " (gsh/fmt-num n))) slots)))

(defn- tokens-for
  "{attr token-name} for the slots that have an equal token, or nil when
   any slot has none."
  [index slots]
  (let [pairs (map (fn [[attr n]] [attr (first (gctx/lookup-number index n))]) slots)]
    (when (every? second pairs)
      (into {} pairs))))

(defn- outcome
  [ctx f]
  (let [results (vec (mapcat (fn [[page shape]] (f page shape)) (gsh/page-shapes ctx)))]
    (rule/make-outcome results {:violations (count results)})))

;; --- token/spacing

(defn- spacing-slots
  [shape]
  (when (gsh/layout? shape)
    (let [{:keys [p1 p2 p3 p4]} (:layout-padding shape)
          {:keys [row-gap column-gap]} (:layout-gap shape)]
      (literal-slots shape [[:p1 p1] [:p2 p2] [:p3 p3] [:p4 p4]
                            [:row-gap row-gap] [:column-gap column-gap]]))))

(defn- evaluate-spacing
  [ctx rule]
  (outcome
   ctx
   (fn [page shape]
     (when-let [slots (seq (spacing-slots shape))]
       (let [tokens  (tokens-for (:spacing-index ctx) slots)
             current (slots->text slots)]
         [(rule/make-result
           rule (:id page) shape
           (cond-> {:attribute :spacing
                    :message (str "Layout spacing uses literal values (" current ").")
                    :message-args [current]
                    :current-value current
                    :fix-available (some? tokens)}
             tokens
             (assoc :expected-value (str/join ", " (distinct (vals tokens)))
                    :recommendation "Bind the spacing tokens with the same values."
                    :fix {:type :apply-tokens :tokens tokens})

             (nil? tokens)
             (assoc :recommendation "Use values from the spacing scale.")))])))))

(def spacing-rule
  {:id "token/spacing"
   :category "token"
   :title "Spacing token usage"
   :description "Layout padding and gaps must use spacing tokens."
   :severity :medium
   :evaluate evaluate-spacing})

;; --- token/radius

(defn- radius-slots
  [shape]
  (literal-slots shape [[:r1 (:r1 shape)] [:r2 (:r2 shape)]
                        [:r3 (:r3 shape)] [:r4 (:r4 shape)]]))

(defn- evaluate-radius
  [ctx rule]
  (outcome
   ctx
   (fn [page shape]
     (when-let [slots (seq (radius-slots shape))]
       (let [tokens  (tokens-for (:radius-index ctx) slots)
             values  (distinct (map second slots))
             current (str/join " / " (map gsh/fmt-num values))]
         [(rule/make-result
           rule (:id page) shape
           (cond-> {:attribute :radius
                    :message (str "Corner radius " current " is a literal value.")
                    :message-args [current]
                    :current-value current
                    :fix-available (some? tokens)}
             tokens
             (assoc :expected-value (str/join ", " (distinct (vals tokens)))
                    :recommendation "Bind the radius token with the same value."
                    :fix {:type :apply-tokens :tokens tokens})

             (nil? tokens)
             (assoc :recommendation "Use a radius from the design system.")))])))))

(def radius-rule
  {:id "token/radius"
   :category "token"
   :title "Radius token usage"
   :description "Corner radius must use radius tokens."
   :severity :medium
   :evaluate evaluate-radius})

;; --- token/typography

(def ^:private typography-attrs
  #{:typography :font-size :font-family :font-weight :line-height})

(defn- governed-typography?
  [shape]
  (some #(get-in shape [:applied-tokens %]) typography-attrs))

(defn- style-matches?
  [style token-style]
  (and (gsh/same-num? (:size style) (:size token-style))
       (gsh/same-num? (:weight style) (:weight token-style))
       (or (nil? (:line-height token-style))
           (gsh/same-num? (:line-height style) (:line-height token-style)))))

(defn- style->text
  [{:keys [size weight line-height]}]
  (str/join " · " (cond-> [(str (gsh/fmt-num size) "px")]
                    weight (conj (gsh/fmt-num weight))
                    line-height (conj (gsh/fmt-num line-height)))))

(defn- evaluate-typography
  [ctx rule]
  (outcome
   ctx
   (fn [page shape]
     (when (and (cfh/text-shape? shape)
                (not (governed-typography? shape)))
       (let [styles (->> (gsh/text-nodes shape)
                         (map gsh/node-style)
                         (filter :size)
                         (distinct))]
         (when (seq styles)
           (let [index     (:typography-index ctx)
                 candidate (fn [style]
                             (some (fn [[name token-style]]
                                     (when (style-matches? style token-style) name))
                                   index))
                 names     (distinct (map candidate styles))
                 token     (when (and (= 1 (count names)) (first names))
                             (first names))
                 current   (str/join ", " (map style->text styles))]
             [(rule/make-result
               rule (:id page) shape
               (cond-> {:attribute :typography
                        :message (str "Text style " current " is not bound to a typography token.")
                        :message-args [current]
                        :current-value current
                        :fix-available (some? token)}
                 token
                 (assoc :expected-value token
                        :recommendation (str "Apply the typography token " token ", which has the same values.")
                        :recommendation-args [token]
                        :fix {:type :apply-tokens :tokens {:typography token}})

                 (nil? token)
                 (assoc :recommendation "Use one of the typography styles of the design system.")))])))))))

(def typography-rule
  {:id "token/typography"
   :category "token"
   :title "Typography token usage"
   :description "Text must use typography tokens."
   :severity :medium
   :evaluate evaluate-typography})
