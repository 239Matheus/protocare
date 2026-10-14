;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.governance
  "Audit mode: right-sidebar panel with the audit areas and the results
   of the current file."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.governance.areas :as areas]
   [app.governance.engine :as engine]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.governance :as dwg]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.workspace.governance-texts :as texts]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private governance-ref
  (l/derived :workspace-governance st/state))

(def ^:private rule-titles
  (into {} (map (juxt :id :title)) engine/default-rules))

(def ^:private severity-order
  ["critical" "high" "medium" "low" "info"])

;; --- Labels (shared with the design health dashboard)

(def ^:private area-label texts/area-label)
(def ^:private area-description texts/area-description)
(def ^:private severity-name texts/severity-name)
(def ^:private severity-label texts/severity-label)

(defn- status-label
  [status]
  (case status
    "fixed"   (tr "workspace.governance.status.fixed")
    "ignored" (tr "workspace.governance.status.ignored")
    (tr "workspace.governance.status.open")))

;; --- Filtering

(defn- result-area
  [result]
  (areas/area-of (:category result)))

(defn- visible?
  [{:keys [severity status] :as result} filters]
  (and (or (= "all" (:area filters))
           (= (:area filters) (some-> (result-area result) name)))
       (or (= "all" (:severity filters))
           (= (:severity filters) (severity-name severity)))
       (or (= "all" (:status filters))
           (= (:status filters) status))))

;; --- Fix approval

(defn- confirm-fix!
  [result]
  (st/emit!
   (modal/show {:type :confirm
                :title (tr "workspace.governance.fix.title")
                :message (tr "workspace.governance.fix.message"
                             (str (:current-value result))
                             (str (:expected-value result))
                             (str (:node-name result)))
                :accept-label (tr "workspace.governance.fix.accept")
                :accept-style :primary
                :on-accept #(st/emit! (dwg/apply-fix result))})))

;; --- Components

(mf/defc area-row*
  {::mf/private true}
  [{:keys [area-id open-count selected on-select]}]
  (let [active?  (areas/active? area-id engine/default-rules)
        on-click (mf/use-fn (mf/deps area-id on-select)
                            #(on-select (name area-id)))]
    [:li
     [:button {:type "button"
               :disabled (not active?)
               :aria-pressed selected
               :class (stl/css-case :area-row true
                                    :area-selected selected
                                    :area-planned (not active?))
               :on-click on-click}
      [:span {:class (stl/css :area-text)}
       [:span {:class (stl/css :area-name)} (area-label area-id)]
       [:span {:class (stl/css :area-desc)} (area-description area-id)]]
      (if active?
        [:span {:class (stl/css-case :area-count true
                                     :area-count-zero (zero? open-count))}
         (str open-count)]
        [:span {:class (stl/css :area-planned-pill)}
         (tr "workspace.governance.area.planned")])]]))

(mf/defc result-card*
  {::mf/private true}
  [{:keys [result page-name]}]
  (let [severity  (severity-name (:severity result))
        status    (:status result)
        open?     (= "open" status)
        fixable?  (and open? (:fix-available result))

        on-go-to  (mf/use-fn (mf/deps result) #(st/emit! (dwg/go-to-result result)))
        on-fix    (mf/use-fn (mf/deps result) #(confirm-fix! result))
        on-ignore (mf/use-fn (mf/deps result)
                             #(st/emit! (dwg/set-result-status (:id result) "ignored")))
        on-reopen (mf/use-fn (mf/deps result)
                             #(st/emit! (dwg/set-result-status (:id result) "open")))]

    [:article {:class (stl/css-case :result-card true
                                    :result-closed (not open?))}
     [:header {:class (stl/css :result-header)}
      [:span {:class (stl/css-case :severity-pill true
                                   :severity-critical (= severity "critical")
                                   :severity-high (= severity "high")
                                   :severity-medium (= severity "medium")
                                   :severity-low (= severity "low")
                                   :severity-info (= severity "info"))}
       (severity-label severity)]
      [:span {:class (stl/css :rule-title)}
       (texts/rule-title (:rule-id result) (get rule-titles (:rule-id result)))]
      (when-not open?
        [:span {:class (stl/css :status-pill)} (status-label status)])]

     [:div {:class (stl/css :result-location)}
      [:span {:class (stl/css :node-name)} (:node-name result)]
      (when page-name
        [:span {:class (stl/css :page-name)} (dm/str " · " page-name)])]

     [:p {:class (stl/css :result-message)} (texts/message result)]

     (when (some? (:current-value result))
       [:dl {:class (stl/css :result-values)}
        [:dt (tr "workspace.governance.current")]
        [:dd (str (:current-value result))]
        (when (some? (:expected-value result))
          [:*
           [:dt (tr "workspace.governance.expected")]
           [:dd {:class (stl/css :expected-value)} (str (:expected-value result))]])])

     (when-let [recommendation (texts/recommendation result)]
       [:p {:class (stl/css :result-recommendation)} recommendation])

     [:footer {:class (stl/css :result-actions)}
      [:> icon-button* {:variant "ghost"
                        :icon i/locate
                        :aria-label (tr "workspace.governance.go-to")
                        :on-click on-go-to}]
      (when fixable?
        [:> button* {:variant "primary" :on-click on-fix}
         (tr "workspace.governance.fix")])
      (if open?
        [:> button* {:variant "ghost" :on-click on-ignore}
         (tr "workspace.governance.ignore")]
        [:> button* {:variant "ghost" :on-click on-reopen}
         (tr "workspace.governance.reopen")])]]))

(defn- on-run
  []
  (st/emit! (dwg/run-audit)))

(mf/defc governance-panel*
  []
  (let [local    (mf/deref governance-ref)
        status   (:status local)
        audit    (:audit local)
        filters  (merge dwg/default-filters (:filters local))
        results  (:results audit)
        data     (mf/deref refs/workspace-data)
        pages    (:pages-index data)

        visible  (mf/with-memo [results filters]
                   (filter #(visible? % filters) results))

        open-results (filter #(= "open" (:status %)) results)
        open-by-area (frequencies (keep result-area open-results))
        fixable      (count (filter :fix-available open-results))
        adherence    (get-in audit [:summary :token-adherence])

        severity-options
        (mf/with-memo []
          (into [{:id "all" :label (tr "workspace.governance.filter.all")}]
                (map (fn [s] {:id s :label (severity-label s)}))
                severity-order))

        status-options
        (mf/with-memo []
          [{:id "open" :label (tr "workspace.governance.status.open")}
           {:id "fixed" :label (tr "workspace.governance.status.fixed")}
           {:id "ignored" :label (tr "workspace.governance.status.ignored")}
           {:id "all" :label (tr "workspace.governance.filter.all")}])

        on-area     (mf/use-fn
                     (mf/deps filters)
                     (fn [area]
                       (st/emit! (dwg/set-filter :area (if (= area (:area filters)) "all" area)))))
        on-severity (mf/use-fn #(st/emit! (dwg/set-filter :severity %)))
        on-status   (mf/use-fn #(st/emit! (dwg/set-filter :status %)))]

    (mf/with-effect []
      (st/emit! (dwg/initialize) (dwg/fetch-latest-audit)))

    [:div {:class (stl/css :governance-panel)}
     [:header {:class (stl/css :panel-header)}
      [:h2 {:class (stl/css :panel-title)} (tr "workspace.governance.title")]
      [:> button* {:variant "primary"
                   :disabled (= status :running)
                   :on-click on-run}
       (if (= status :running)
         (tr "workspace.governance.running")
         (tr "workspace.governance.run"))]
      [:p {:class (stl/css :saved-note)} (tr "workspace.governance.saved-note")]
      (when audit
        [:div {:class (stl/css :summary)}
         [:span (tr "workspace.governance.summary.open" (str (count open-results)))]
         [:span (tr "workspace.governance.summary.fixable" (str fixable))]
         (when (number? adherence)
           [:span (tr "workspace.governance.summary.adherence"
                      (str (js/Math.round (* 100 adherence)) "%"))])])]

     [:div {:class (stl/css :panel-body)}
      [:section {:class (stl/css :areas)}
       [:h3 {:class (stl/css :section-title)} (tr "workspace.governance.areas")]
       [:ul {:class (stl/css :area-list)}
        (for [{:keys [id]} areas/areas]
          [:> area-row* {:key (name id)
                         :area-id id
                         :open-count (get open-by-area id 0)
                         :selected (= (:area filters) (name id))
                         :on-select on-area}])]]

      (when audit
        [:div {:class (stl/css :filters)}
         [:> select* {:options severity-options
                      :default-selected (:severity filters)
                      :on-change on-severity}]
         [:> select* {:options status-options
                      :default-selected (:status filters)
                      :on-change on-status}]])

      [:section {:class (stl/css :results)}
       (cond
         (nil? audit)
         [:> empty-state* {:icon i/status-alert
                           :text (tr "workspace.governance.empty")}]

         (empty? visible)
         [:> empty-state* {:icon i/status-tick
                           :text (tr "workspace.governance.clean")}]

         :else
         (for [result visible]
           [:> result-card* {:key (dm/str (:id result))
                             :result result
                             :page-name (get-in pages [(:page-id result) :name])}]))]]]))
