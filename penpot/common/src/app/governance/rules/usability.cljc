;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.usability
  "Usability rules: is the prototype ready to be tested?

   - `usability/broken-link` navigation to a board that does not exist;
   - `usability/flow`        a page with navigation but no prototype flow
                             (testers have no starting point);
   - `usability/dead-end`    a board reached by navigation that has no
                             interaction to leave it.

   Moderated/unmoderated usability sessions (tasks, success rate, time
   on task) are planned in docs/03-analytics-plan.md; these rules only
   check what the file itself can tell."
  (:require
   [app.common.files.helpers :as cfh]
   [app.governance.rule :as rule]
   [app.governance.shapes :as gsh]))

(defn- navigations
  "[source-shape interaction] for navigate interactions of a page."
  [page]
  (for [shape (vals (:objects page))
        interaction (:interactions shape)
        :when (= :navigate (:action-type interaction))]
    [shape interaction]))

(defn- top-board
  "Top-level board containing `shape` (or the shape itself)."
  [objects shape]
  (let [ids (cons (:id shape) (gsh/ancestors-of objects shape))]
    (->> ids
         (keep #(get objects %))
         (filter cfh/frame-shape?)
         (remove gsh/root?)
         (last))))

(defn- evaluate-broken-link
  [ctx rule]
  (let [results
        (for [page (:pages ctx)
              [shape interaction] (navigations page)
              :let [dest (:destination interaction)]
              :when (or (nil? dest) (nil? (get (:objects page) dest)))]
          (rule/make-result
           rule (:id page) shape
           {:attribute :interaction
            :message (str "\"" (:name shape) "\" navigates to a board that does not exist.")
            :message-args [(:name shape)]
            :recommendation "Point the interaction to an existing board or remove it."
            :fix-available false}))]
    (rule/make-outcome results {:violations (count results)})))

(def broken-link-rule
  {:id "usability/broken-link"
   :category "usability"
   :title "Broken prototype link"
   :description "Navigation must lead to an existing board."
   :severity :high
   :evaluate evaluate-broken-link})

(defn- evaluate-flow
  [ctx rule]
  (let [results
        (for [page (:pages ctx)
              :let [navs (navigations page)]
              :when (and (seq navs) (empty? (:flows page)))
              :let [board (or (top-board (:objects page) (ffirst navs)) (ffirst navs))]]
          (rule/make-result
           rule (:id page) board
           {:attribute :flow
            :message (str "Page \"" (:name page) "\" has navigation but no prototype flow.")
            :message-args [(:name page)]
            :recommendation "Add a flow starting on the first screen so testers know where to begin."
            :fix-available false}))]
    (rule/make-outcome results {:violations (count results)})))

(def flow-rule
  {:id "usability/flow"
   :category "usability"
   :title "Prototype flow"
   :description "Pages with navigation need a flow to start testing."
   :severity :low
   :evaluate evaluate-flow})

(defn- evaluate-dead-end
  [ctx rule]
  (let [results
        (for [page (:pages ctx)
              :let [objects (:objects page)
                    dests   (set (keep (comp :destination second) (navigations page)))
                    exits   (set (for [shape (vals objects)
                                       :when (seq (:interactions shape))]
                                   (:id (top-board objects shape))))]
              dest dests
              :let [board (get objects dest)]
              :when (and board (not (contains? exits dest)))]
          (rule/make-result
           rule (:id page) board
           {:attribute :interaction
            :message (str "\"" (:name board) "\" is reached by navigation but has no way out.")
            :message-args [(:name board)]
            :recommendation "Add a back or next action so the test can continue."
            :fix-available false}))]
    (rule/make-outcome results {:violations (count results)})))

(def dead-end-rule
  {:id "usability/dead-end"
   :category "usability"
   :title "Dead end screen"
   :description "Screens reached by navigation need a way to continue."
   :severity :low
   :evaluate evaluate-dead-end})

(def rules
  [broken-link-rule flow-rule dead-end-rule])
