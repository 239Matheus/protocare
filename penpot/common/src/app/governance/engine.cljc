;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.engine
  "Runs governance rules over an audit context and consolidates results.

   Rules are data. Adding a rule means adding a map to `default-rules`
   (or passing your own vector); the engine never changes."
  (:require
   [app.governance.rule :as rule]
   [app.governance.rules.token-color :as token-color]))

(def version
  "Bump when rules or result shapes change; stored with every audit."
  "0.1.0")

(def default-rules
  [token-color/rule])

(defn- configure
  "Merges per-rule policy config (`{rule-id {:enabled? .. :severity .. :options ..}}`)
   into the rule maps and drops disabled rules."
  [rules config]
  (->> rules
       (map (fn [r] (merge r (get config (:id r)))))
       (remove #(false? (:enabled? %)))
       (map rule/check-rule)))

(defn- sort-results
  [results]
  (sort-by (fn [r] [(- (rule/severity-rank (:severity r)))
                    (:category r)
                    (:rule-id r)
                    (str (:page-id r))
                    (str (:node-id r))])
           results))

(defn summarize
  [results stats]
  (let [count-by (fn [k] (->> results (map k) (frequencies)))
        adherence (get-in stats ["token/color" :adherence])]
    {:total (count results)
     :fixable (count (filter :fix-available results))
     :by-severity (count-by :severity)
     :by-category (count-by :category)
     :by-rule (count-by :rule-id)
     :by-page (count-by :page-id)
     :token-adherence (when (some? adherence) (double adherence))}))

(defn run-audit
  "Evaluates `rules` (default: all) against `ctx`.

   Returns `{:results [...] :stats {rule-id {...}} :summary {...}}`."
  [ctx & {:keys [rules config] :or {rules default-rules config {}}}]
  (let [rules    (configure rules config)
        outcomes (map (fn [r] [(:id r) ((:evaluate r) ctx r)]) rules)
        results  (->> outcomes (mapcat (comp :results second)) (sort-results) (vec))
        stats    (into {} (map (fn [[id o]] [id (:stats o)])) outcomes)]
    {:results results
     :stats stats
     :summary (summarize results stats)}))
