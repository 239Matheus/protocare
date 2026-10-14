;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.areas
  "Audit areas shown in the Audit mode. An area groups rule categories;
   a rule joins an area just by declaring one of its categories, so the
   UI and the health reports need no change when rules are added."
  (:require
   [app.governance.engine :as engine]))

(def areas
  [{:id :design-system
    :categories #{"token" "component"}}
   {:id :accessibility
    :categories #{"accessibility"}}
   {:id :ui-quality
    :categories #{"layout" "heuristic" "gestalt"}}
   {:id :layer-semantics
    :categories #{"structure" "naming"}}
   {:id :usability
    :categories #{"usability"}}])

(def area-by-id
  (into {} (map (juxt :id identity)) areas))

(defn area-of
  "Area id of a rule category, or nil."
  [category]
  (some (fn [{:keys [id categories]}]
          (when (contains? categories category) id))
        areas))

(defn rules-of
  "Rules of `rules` (default: the engine rules) that belong to `area-id`."
  ([area-id] (rules-of area-id engine/default-rules))
  ([area-id rules]
   (let [categories (get-in area-by-id [area-id :categories] #{})]
     (filterv #(contains? categories (:category %)) rules))))

(defn active?
  "True when the area has at least one rule."
  ([area-id] (active? area-id engine/default-rules))
  ([area-id rules] (boolean (seq (rules-of area-id rules)))))
