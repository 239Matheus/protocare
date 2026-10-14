;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.governance-texts
  "Localized titles, messages and recommendations of the governance
   rules. The engine returns English text plus `:message-args` and
   `:recommendation-args`; here they are rendered with the translation
   of the current locale.

   Results without `:message-args` (persisted by an older engine) and
   rules unknown to this build keep the engine English text. Variants
   of the token rules (a matching token exists or not) are told apart
   by `:fix-available`.

   Keys are literal on purpose: the translation tooling only detects
   `(tr \"key\")` call sites."
  (:require
   [app.util.i18n :refer [tr]]))

(defn rule-title
  "Title of a rule id, or `fallback` (the engine title) when unknown."
  [rule-id fallback]
  (case rule-id
    "token/color"                (tr "workspace.governance.rule.token-color.title")
    "token/spacing"              (tr "workspace.governance.rule.token-spacing.title")
    "token/radius"               (tr "workspace.governance.rule.token-radius.title")
    "token/typography"           (tr "workspace.governance.rule.token-typography.title")
    "accessibility/contrast"     (tr "workspace.governance.rule.accessibility-contrast.title")
    "accessibility/text-size"    (tr "workspace.governance.rule.accessibility-text-size.title")
    "accessibility/touch-target" (tr "workspace.governance.rule.accessibility-touch-target.title")
    "layout/alignment"           (tr "workspace.governance.rule.layout-alignment.title")
    "layout/spacing"             (tr "workspace.governance.rule.layout-spacing.title")
    "naming/default-name"        (tr "workspace.governance.rule.naming-default-name.title")
    "naming/duplicate"           (tr "workspace.governance.rule.naming-duplicate.title")
    "usability/broken-link"      (tr "workspace.governance.rule.usability-broken-link.title")
    "usability/flow"             (tr "workspace.governance.rule.usability-flow.title")
    "usability/dead-end"         (tr "workspace.governance.rule.usability-dead-end.title")
    (or fallback rule-id)))

(defn- attribute-label
  [attribute]
  (if (= "stroke" (some-> attribute name))
    (tr "workspace.governance.attribute.stroke")
    (tr "workspace.governance.attribute.fill")))

(defn- edge-label
  [edge]
  (if (= "top" edge)
    (tr "workspace.governance.edge.top")
    (tr "workspace.governance.edge.left")))

(defn message
  [{:keys [rule-id message message-args fix-available attribute]}]
  (let [[a b c d] message-args]
    (if (empty? message-args)
      message
      (case rule-id
        "token/color"
        (if fix-available
          (tr "workspace.governance.rule.token-color.message" (attribute-label attribute) a)
          (tr "workspace.governance.rule.token-color.message-missing" (attribute-label attribute) a))

        "token/spacing"              (tr "workspace.governance.rule.token-spacing.message" a)
        "token/radius"               (tr "workspace.governance.rule.token-radius.message" a)
        "token/typography"           (tr "workspace.governance.rule.token-typography.message" a)
        "accessibility/contrast"     (tr "workspace.governance.rule.accessibility-contrast.message" a b c d)
        "accessibility/text-size"    (tr "workspace.governance.rule.accessibility-text-size.message" a b)
        "accessibility/touch-target" (tr "workspace.governance.rule.accessibility-touch-target.message" a b)
        "layout/alignment"           (tr "workspace.governance.rule.layout-alignment.message" a b (edge-label c) d)
        "layout/spacing"             (tr "workspace.governance.rule.layout-spacing.message" a b c)
        "naming/default-name"        (tr "workspace.governance.rule.naming-default-name.message" a)
        "naming/duplicate"           (tr "workspace.governance.rule.naming-duplicate.message" a)
        "usability/broken-link"      (tr "workspace.governance.rule.usability-broken-link.message" a)
        "usability/flow"             (tr "workspace.governance.rule.usability-flow.message" a)
        "usability/dead-end"         (tr "workspace.governance.rule.usability-dead-end.message" a)
        message))))

(defn recommendation
  [{:keys [rule-id recommendation recommendation-args message-args fix-available]}]
  (let [[a] recommendation-args]
    (if (empty? message-args)
      recommendation
      (case rule-id
        "token/color"
        (if fix-available
          (tr "workspace.governance.rule.token-color.recommendation" a)
          (tr "workspace.governance.rule.token-color.recommendation-missing"))

        "token/spacing"
        (if fix-available
          (tr "workspace.governance.rule.token-spacing.recommendation")
          (tr "workspace.governance.rule.token-spacing.recommendation-scale"))

        "token/radius"
        (if fix-available
          (tr "workspace.governance.rule.token-radius.recommendation")
          (tr "workspace.governance.rule.token-radius.recommendation-scale"))

        "token/typography"
        (if fix-available
          (tr "workspace.governance.rule.token-typography.recommendation" a)
          (tr "workspace.governance.rule.token-typography.recommendation-scale"))

        "accessibility/contrast"     (tr "workspace.governance.rule.accessibility-contrast.recommendation")
        "accessibility/text-size"    (tr "workspace.governance.rule.accessibility-text-size.recommendation")
        "accessibility/touch-target" (tr "workspace.governance.rule.accessibility-touch-target.recommendation")
        "layout/alignment"           (tr "workspace.governance.rule.layout-alignment.recommendation")
        "layout/spacing"             (tr "workspace.governance.rule.layout-spacing.recommendation" a)
        "naming/default-name"        (tr "workspace.governance.rule.naming-default-name.recommendation")
        "naming/duplicate"           (tr "workspace.governance.rule.naming-duplicate.recommendation")
        "usability/broken-link"      (tr "workspace.governance.rule.usability-broken-link.recommendation")
        "usability/flow"             (tr "workspace.governance.rule.usability-flow.recommendation")
        "usability/dead-end"         (tr "workspace.governance.rule.usability-dead-end.recommendation")
        recommendation))))
