;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rule
  "Rule and result data model for the Governance Engine.

   A rule is a plain map with an `:evaluate` function. The engine calls
   `(evaluate ctx rule)` and expects `{:results [...] :stats {...}}`.
   Rules must be pure and deterministic: same context, same output."
  (:require
   [app.common.schema :as sm]))

;; --- Severity

(def severities
  "Ordered from least to most severe."
  [:info :low :medium :high :critical])

(def severity-rank
  (zipmap severities (range)))

(def schema:severity
  [::sm/one-of (set severities)])

(defn severity>=
  "True when `a` is at least as severe as `b`."
  [a b]
  (>= (get severity-rank a 0) (get severity-rank b 0)))

;; --- Rule

(def schema:rule
  [:map {:title "GovernanceRule"}
   [:id :string]
   [:category :string]
   [:title :string]
   [:description :string]
   [:severity schema:severity]
   [:enabled? {:optional true} :boolean]
   [:options {:optional true} [:map-of :keyword :any]]
   [:evaluate fn?]])

(def check-rule
  (sm/check-fn schema:rule :hint "expected valid governance rule"))

;; --- Result

(def schema:fix
  [:map {:title "RuleFix"}
   [:type :keyword]])

(def schema:result
  [:map {:title "RuleResult"}
   [:rule-id :string]
   [:category :string]
   [:page-id ::sm/uuid]
   [:node-id ::sm/uuid]
   [:node-name {:optional true} :string]
   [:attribute {:optional true} :keyword]
   [:severity schema:severity]
   [:message :string]
   ;; Arguments of the localized message/recommendation in the UI
   ;; (`governance.rule.<id>.message`); `:message` is the English fallback.
   [:message-args {:optional true} [:vector :string]]
   [:recommendation {:optional true} :string]
   [:recommendation-args {:optional true} [:vector :string]]
   [:expected-value {:optional true} :any]
   [:current-value {:optional true} :any]
   [:candidates {:optional true} [:vector :string]]
   [:fix-available :boolean]
   [:fix {:optional true} schema:fix]])

(def check-result
  (sm/check-fn schema:result :hint "expected valid rule result"))

(defn make-result
  "Builds a result for `rule` on `shape`. `attrs` may override any key."
  [rule page-id shape attrs]
  (-> {:rule-id (:id rule)
       :category (:category rule)
       :severity (:severity rule)
       :page-id page-id
       :node-id (:id shape)
       :node-name (or (:name shape) "")
       :fix-available false}
      (merge attrs)
      (check-result)))

(defn make-outcome
  "Normalizes what a rule returns."
  [results stats]
  {:results (vec results)
   :stats (or stats {})})
