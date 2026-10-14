;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.rules.naming
  "Layer semantics rules:

   - `naming/default-name` layers that keep the default name the editor
                           gave them (\"Rectangle\", \"Group 3\"...);
   - `naming/duplicate`    siblings with the same name, which developers
                           and handoff tools cannot tell apart.

   Text layers are skipped by `naming/default-name` (their name is their
   content) and shapes inside component copies are skipped by both (their
   names come from the main component)."
  (:require
   [app.common.files.helpers :as cfh]
   [app.governance.rule :as rule]
   [app.governance.shapes :as gsh]
   [cuerdas.core :as str]))

(def default-name-re
  #"(?i)^(rectangle|ellipse|board|frame|artboard|group|path|image|line|bool|union|difference|intersection|exclude|svg|ret[aâ]ngulo|elipse|prancheta|quadro|grupo|caminho|imagem|linha)(\s*\d+)?$")

(defn- evaluate-default-name
  [ctx rule]
  (let [results
        (for [[page shape] (gsh/page-shapes ctx)
              :when (and (not (cfh/text-shape? shape))
                         (not (gsh/in-copy? shape))
                         (re-matches default-name-re (str (:name shape))))]
          (rule/make-result
           rule (:id page) shape
           {:attribute :name
            :message (str "Layer keeps the default name \"" (:name shape) "\".")
            :message-args [(:name shape)]
            :recommendation "Name the layer after its role (e.g. \"Header background\", \"Avatar\")."
            :current-value (:name shape)
            :fix-available false}))]
    (rule/make-outcome results {:violations (count results)})))

(def default-name-rule
  {:id "naming/default-name"
   :category "naming"
   :title "Default layer name"
   :description "Layers should be named after their role."
   :severity :low
   :evaluate evaluate-default-name})

(defn- evaluate-duplicate
  [ctx rule]
  (let [results
        (for [page      (:pages ctx)
              container (gsh/containers (:objects page))
              :when (not (gsh/in-copy? container))
              :let [kids (remove #(str/blank? (:name %))
                                 (gsh/children (:objects page) container))]
              [_ group] (group-by :name kids)
              :when (> (count group) 1)
              shape (rest group)]
          (rule/make-result
           rule (:id page) shape
           {:attribute :name
            :message (str "\"" (:name shape) "\" has a sibling with the same name.")
            :message-args [(:name shape)]
            :recommendation "Give each sibling a name that says what it is (e.g. \"Email input\", \"Password input\")."
            :current-value (:name shape)
            :fix-available false}))]
    (rule/make-outcome results {:violations (count results)})))

(def duplicate-rule
  {:id "naming/duplicate"
   :category "naming"
   :title "Duplicate sibling names"
   :description "Sibling layers should have distinct names."
   :severity :info
   :evaluate evaluate-duplicate})

(def rules
  [default-name-rule duplicate-rule])
