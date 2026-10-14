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
   [app.governance.areas :as areas]
   [app.governance.rule :as rule]
   [app.governance.rules.accessibility :as accessibility]
   [app.governance.rules.layout :as layout]
   [app.governance.rules.naming :as naming]
   [app.governance.rules.token-color :as token-color]
   [app.governance.rules.token-values :as token-values]
   [app.governance.rules.usability :as usability]
   [app.governance.shapes :as gsh]))

(def version
  "Bump when rules or result shapes change; stored with every audit."
  "0.3.0")

(def default-rules
  (into [token-color/rule
         token-values/spacing-rule
         token-values/radius-rule
         token-values/typography-rule]
        (concat accessibility/rules
                layout/rules
                naming/rules
                usability/rules)))

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
     :by-area (->> results (keep #(some-> (:category %) areas/area-of name)) (frequencies))
     :by-rule (count-by :rule-id)
     :by-page (count-by :page-id)
     :token-adherence (when (some? adherence) (double adherence))}))

(defn- with-board
  "Adds the top-level board (screen) of the node to a result, so reports
   can go from file to screen to finding."
  [objects-by-page result]
  (let [objects (get objects-by-page (:page-id result))
        board   (some->> (get objects (:node-id result)) (gsh/top-board objects))]
    (assoc result
           :board-id (:id board)
           :board-name (:name board))))

(defn run-audit
  "Evaluates `rules` (default: all) against `ctx`.

   Returns `{:results [...] :stats {rule-id {...}} :summary {...}}`."
  [ctx & {:keys [rules config] :or {rules default-rules config {}}}]
  (let [rules    (configure rules config)
        outcomes (map (fn [r] [(:id r) ((:evaluate r) ctx r)]) rules)
        objects  (into {} (map (juxt :id :objects)) (:pages ctx))
        results  (->> outcomes
                      (mapcat (comp :results second))
                      (map (partial with-board objects))
                      (sort-results)
                      (vec))
        stats    (into {} (map (fn [[id o]] [id (:stats o)])) outcomes)]
    {:results results
     :stats stats
     :summary (assoc (summarize results stats)
                     :themes (vec (:themes ctx)))}))
