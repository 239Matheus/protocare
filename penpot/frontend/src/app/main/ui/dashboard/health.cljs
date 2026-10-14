;; Proto Care — design health dashboard
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.dashboard.health
  "\"Saúde do design\": aggregated governance metrics of a team or one of
   its projects (docs/03-analytics-plan.md, section 4).

   Tabs: Overview, Trends, Projects (files when a project is selected),
   Areas and Rules. Data comes from `get-team-health`,
   `get-project-health` and `get-governance-metrics`. Everything is
   aggregated: no numbers per person."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.governance.areas :as areas]
   [app.main.repo :as rp]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.workspace.governance-texts :as texts]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

;; --- Formatting

(def ^:private dash "—")

(defn- fmt-int
  [n]
  (if (some? n) (str (int n)) dash))

(defn- fmt-pct
  [ratio]
  (if (some? ratio)
    (str (js/Math.round (* 100 ratio)) "%")
    dash))

(defn- fmt-duration
  [seconds]
  (cond
    (nil? seconds)     dash
    (< seconds 60)     (tr "dashboard.health.duration.seconds" (js/Math.round seconds))
    (< seconds 3600)   (tr "dashboard.health.duration.minutes" (js/Math.round (/ seconds 60)))
    (< seconds 86400)  (tr "dashboard.health.duration.hours" (js/Math.round (/ seconds 3600)))
    :else              (tr "dashboard.health.duration.days" (js/Math.round (/ seconds 86400)))))

(defn- fmt-week
  "Day/month of the start of a week. Weeks start on Monday 00:00 UTC
   (Postgres `date_trunc`), so the date is read in UTC."
  [inst]
  (when inst
    (let [date (js/Date. inst)
          pad  #(.padStart (str %) 2 "0")]
      (str (pad (.getUTCDate date)) "/" (pad (inc (.getUTCMonth date)))))))

(defn- project-name
  "The default project is stored as \"Drafts\"; show it as the
   dashboard does."
  [project]
  (if (:is-default project)
    (tr "labels.drafts")
    (:project-name project)))

(def ^:private severity-order
  ["critical" "high" "medium" "low" "info"])

(def ^:private area-ids
  (mapv (comp name :id) areas/areas))

;; --- Data

(defn- since-days
  "ISO 8601 start of the period. `get-*` commands travel as GET query
   params, where a Date would be stringified in a format the backend
   does not parse."
  [days]
  (.toISOString (js/Date. (- (js/Date.now) (* days 24 60 60 1000)))))

(defn- fill-weeks
  "Weekly rows for every week of the period, with zeros where there was
   no activity, so the charts show gaps instead of hiding them."
  [weekly days]
  (let [week-ms (* 7 24 60 60 1000)
        by-week (into {} (map (fn [row] [(.getTime (js/Date. (:week row))) row])) weekly)
        monday  (fn [ms]
                  (let [date (js/Date. ms)
                        day  (mod (+ 6 (.getUTCDay date)) 7)]
                    (- (js/Date.UTC (.getUTCFullYear date) (.getUTCMonth date) (.getUTCDate date))
                       (* day 24 60 60 1000))))
        last-w  (monday (js/Date.now))
        first-w (monday (- (js/Date.now) (* days 24 60 60 1000)))]
    (->> (range first-w (+ last-w 1) week-ms)
         (mapv (fn [ms]
                 (or (some-> (get by-week ms) (assoc :week (js/Date. ms)))
                     {:week (js/Date. ms) :audits 0 :files 0 :findings 0 :resolved 0}))))))

(defn- load!
  [state* {:keys [team-id project-id days]}]
  (swap! state* assoc :loading true :error nil)
  (->> (rp/cmd! :get-governance-permissions {:team-id team-id})
       (rx/mapcat
        (fn [perms]
          (if-not (:can-view-health perms)
            (rx/of {:perms perms})
            (->> (rx/zip (rp/cmd! :get-team-health {:team-id team-id})
                         (if project-id
                           (rp/cmd! :get-project-health {:project-id project-id})
                           (rx/of nil))
                         (rp/cmd! :get-governance-metrics
                                  (cond-> {:team-id team-id :since (since-days days)}
                                    project-id (assoc :project-id project-id))))
                 (rx/map (fn [[team-health project-health metrics]]
                           {:perms perms
                            :team-health team-health
                            :project-health project-health
                            :metrics metrics}))))))
       (rx/subs!
        (fn [data]
          (reset! state* (assoc data :loading false)))
        (fn [_]
          (swap! state* assoc :loading false :error true)))))

;; --- Small pieces

(mf/defc kpi*
  {::mf/private true}
  [{:keys [label value hint tone]}]
  [:div {:class (stl/css-case :kpi true
                              :kpi-alert (= tone "alert")
                              :kpi-good (= tone "good"))}
   [:span {:class (stl/css :kpi-value)} value]
   [:span {:class (stl/css :kpi-label)} label]
   (when hint
     [:span {:class (stl/css :kpi-hint)} hint])])

(mf/defc hbar-list*
  "Horizontal bars: rows of {:id :label :value}."
  {::mf/private true}
  [{:keys [rows]}]
  (let [top (max 1 (reduce max 0 (map :value rows)))]
    [:ul {:class (stl/css :hbar-list)}
     (for [{:keys [id label value]} rows]
       [:li {:key id :class (stl/css :hbar-row)}
        [:span {:class (stl/css :hbar-label)} label]
        [:span {:class (stl/css :hbar-track)}
         [:span {:class (stl/css :hbar-fill)
                 :style {:width (str (* 100 (/ value top)) "%")}}]]
        [:span {:class (stl/css :hbar-value)} (fmt-int value)]])]))

(mf/defc column-chart*
  "Vertical bars, one per week."
  {::mf/private true}
  [{:keys [title rows value-fn format-fn]}]
  (let [values (mapv value-fn rows)
        top    (max 1 (reduce max 0 (remove nil? values)))]
    [:figure {:class (stl/css :chart)}
     [:figcaption {:class (stl/css :chart-title)} title]
     [:div {:class (stl/css :chart-columns)}
      (for [[idx row] (d/enumerate rows)]
        (let [value (nth values idx)]
          [:div {:key idx
                 :class (stl/css :chart-column)
                 :title (dm/str (fmt-week (:week row)) ": " (format-fn value))}
           [:span {:class (stl/css :chart-value)} (format-fn value)]
           [:span {:class (stl/css :chart-bar)
                   :style {:height (str (* 100 (/ (or value 0) top)) "%")}}]
           [:span {:class (stl/css :chart-label)} (fmt-week (:week row))]]))]]))

;; --- Tabs

(mf/defc overview-tab*
  {::mf/private true}
  [{:keys [health metrics]}]
  (let [by-severity (merge (:by-severity health) (:by-severity metrics))
        urgent      (+ (get by-severity "critical" 0) (get by-severity "high" 0))
        a11y-open   (get-in metrics [:by-area "accessibility" :open] 0)
        area-rows   (mapv (fn [area] {:id area
                                      :label (texts/area-label area)
                                      :value (get-in metrics [:by-area area :open] 0)})
                          area-ids)
        sev-rows    (mapv (fn [sev] {:id sev
                                     :label (texts/severity-label sev)
                                     :value (get by-severity sev 0)})
                          severity-order)]
    [:*
     [:div {:class (stl/css :kpis)}
      [:> kpi* {:label (tr "dashboard.health.kpi.files")
                :value (fmt-int (:files-audited health))}]
      [:> kpi* {:label (tr "dashboard.health.kpi.open")
                :value (fmt-int (:open metrics))}]
      [:> kpi* {:label (tr "dashboard.health.kpi.urgent")
                :value (fmt-int urgent)
                :tone (when (pos? urgent) "alert")}]
      [:> kpi* {:label (tr "dashboard.health.kpi.accessibility")
                :value (fmt-int a11y-open)
                :tone (when (pos? a11y-open) "alert")}]
      [:> kpi* {:label (tr "dashboard.health.kpi.adherence")
                :value (fmt-pct (:token-adherence health))}]
      [:> kpi* {:label (tr "dashboard.health.kpi.fix-rate")
                :value (fmt-pct (:fix-rate metrics))
                :hint (tr "dashboard.health.kpi.fix-rate.hint")
                :tone (when (some-> (:fix-rate metrics) (>= 0.5)) "good")}]
      [:> kpi* {:label (tr "dashboard.health.kpi.resolved")
                :value (fmt-int (:resolved metrics))
                :tone (when (pos? (:resolved metrics 0)) "good")}]
      [:> kpi* {:label (tr "dashboard.health.kpi.audits")
                :value (fmt-int (:audits metrics))}]]

     [:div {:class (stl/css :grid-2)}
      [:section {:class (stl/css :card)}
       [:h2 {:class (stl/css :card-title)} (tr "dashboard.health.open-by-area")]
       [:> hbar-list* {:rows area-rows}]]
      [:section {:class (stl/css :card)}
       [:h2 {:class (stl/css :card-title)} (tr "dashboard.health.open-by-severity")]
       [:> hbar-list* {:rows sev-rows}]]]

     [:section {:class (stl/css :card)}
      [:h2 {:class (stl/css :card-title)} (tr "dashboard.health.recent")]
      [:ul {:class (stl/css :recent-list)}
       (for [audit (take 8 (:recent health))]
         [:li {:key (dm/str (:id audit)) :class (stl/css :recent-row)}
          [:span {:class (stl/css :recent-file)} (:file-name audit)]
          [:span {:class (stl/css :recent-total)}
           (tr "dashboard.health.recent.findings" (fmt-int (get-in audit [:summary :total])))]
          [:span {:class (stl/css :recent-when)} (ct/timeago (:created-at audit))]])]]]))

(mf/defc trends-tab*
  {::mf/private true}
  [{:keys [weekly]}]
  (if (every? #(zero? (:audits %)) weekly)
    [:> empty-state* {:icon i/clock :text (tr "dashboard.health.trends.empty")}]
    [:div {:class (stl/css :charts)}
     [:> column-chart* {:title (tr "dashboard.health.trends.resolved")
                        :rows weekly
                        :value-fn :resolved
                        :format-fn fmt-int}]
     [:> column-chart* {:title (tr "dashboard.health.trends.average")
                        :rows weekly
                        :value-fn (fn [{:keys [audits findings]}]
                                    (when (pos? audits) (/ findings audits)))
                        :format-fn (fn [v] (if (some? v) (str (js/Math.round v)) dash))}]
     [:> column-chart* {:title (tr "dashboard.health.trends.audits")
                        :rows weekly
                        :value-fn :audits
                        :format-fn fmt-int}]]))

(mf/defc projects-tab*
  {::mf/private true}
  [{:keys [projects on-select]}]
  [:table {:class (stl/css :table)}
   [:thead
    [:tr
     [:th (tr "dashboard.health.col.project")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.files")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.open")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.critical")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.fixable")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.adherence")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.resolved")]
     [:th (tr "dashboard.health.col.last-audit")]]]
   [:tbody
    (for [p (sort-by (comp - :open-violations) projects)]
      [:tr {:key (dm/str (:project-id p))
            :class (stl/css :clickable)
            :data-project-id (dm/str (:project-id p))
            :on-click on-select}
       [:td {:class (stl/css :strong)} (project-name p)]
       [:td {:class (stl/css :num)} (fmt-int (:files-audited p))]
       [:td {:class (stl/css :num)} (fmt-int (:open-violations p))]
       [:td {:class (stl/css :num)} (fmt-int (:critical-violations p))]
       [:td {:class (stl/css :num)} (fmt-int (:fixable-violations p))]
       [:td {:class (stl/css :num)} (fmt-pct (:token-adherence p))]
       [:td {:class (stl/css :num)} (fmt-int (:resolved-since-previous p))]
       [:td (ct/timeago (:last-audit-at p))]])]])

(mf/defc files-tab*
  {::mf/private true}
  [{:keys [files]}]
  [:table {:class (stl/css :table)}
   [:thead
    [:tr
     [:th (tr "dashboard.health.col.file")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.open")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.fixable")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.adherence")]
     [:th {:class (stl/css :num)} (tr "dashboard.health.col.resolved")]
     [:th (tr "dashboard.health.col.last-audit")]]]
   [:tbody
    (for [f (sort-by #(- (get-in % [:summary :total] 0)) files)]
      [:tr {:key (dm/str (:file-id f))}
       [:td {:class (stl/css :strong)} (:file-name f)]
       [:td {:class (stl/css :num)} (fmt-int (get-in f [:summary :total]))]
       [:td {:class (stl/css :num)} (fmt-int (get-in f [:summary :fixable]))]
       [:td {:class (stl/css :num)} (fmt-pct (get-in f [:summary :token-adherence]))]
       [:td {:class (stl/css :num)} (fmt-int (get-in f [:summary :resolved-since-previous]))]
       [:td (ct/timeago (:created-at f))]])]])

(mf/defc areas-tab*
  {::mf/private true}
  [{:keys [by-area]}]
  [:div {:class (stl/css :area-cards)}
   (for [area area-ids]
     (let [m (get by-area area)]
       [:section {:key area :class (stl/css :card)}
        [:h2 {:class (stl/css :card-title)} (texts/area-label area)]
        [:p {:class (stl/css :card-desc)} (texts/area-description area)]
        [:dl {:class (stl/css :area-stats)}
         [:dt (tr "dashboard.health.area.open")]
         [:dd (fmt-int (:open m 0))]
         [:dt (tr "dashboard.health.area.resolved")]
         [:dd (fmt-int (:resolved m 0))]
         [:dt (tr "dashboard.health.area.fix-rate")]
         [:dd (fmt-pct (:fix-rate m))]
         [:dt (tr "dashboard.health.area.mttr")]
         [:dd (fmt-duration (:median-resolve-seconds m))]
         [:dt (tr "dashboard.health.area.fixed-in-panel")]
         [:dd (fmt-int (:fixed-in-panel m 0))]
         [:dt (tr "dashboard.health.area.ignored")]
         [:dd (fmt-int (:ignored m 0))]]]))])

(mf/defc rules-tab*
  {::mf/private true}
  [{:keys [by-rule]}]
  [:*
   [:p {:class (stl/css :tab-hint)} (tr "dashboard.health.rules.hint")]
   [:table {:class (stl/css :table)}
    [:thead
     [:tr
      [:th (tr "dashboard.health.col.rule")]
      [:th (tr "dashboard.health.col.area")]
      [:th {:class (stl/css :num)} (tr "dashboard.health.col.open")]
      [:th {:class (stl/css :num)} (tr "dashboard.health.col.resolved")]
      [:th {:class (stl/css :num)} (tr "dashboard.health.col.ignored")]
      [:th {:class (stl/css :num)} (tr "dashboard.health.col.ignore-rate")]]]
    [:tbody
     (for [[rule-id m] (sort-by (fn [[_ m]] (- (+ (:open m) (:resolved m)))) by-rule)]
       [:tr {:key rule-id}
        [:td {:class (stl/css :strong)} (texts/rule-title rule-id rule-id)]
        [:td (texts/area-label (:area m))]
        [:td {:class (stl/css :num)} (fmt-int (:open m))]
        [:td {:class (stl/css :num)} (fmt-int (:resolved m))]
        [:td {:class (stl/css :num)} (fmt-int (:ignored m))]
        [:td {:class (stl/css-case :num true
                                   :warn (some-> (:ignore-rate m) (>= 0.3)))}
         (fmt-pct (:ignore-rate m))]])]]])

;; --- Page

(def ^:private period-options
  [{:id "30" :label "30"} {:id "90" :label "90"} {:id "180" :label "180"}])

(mf/defc health-page*
  [{:keys [team]}]
  (let [team-id     (:id team)
        state*      (mf/use-state {:loading true})
        state       (deref state*)
        tab*        (mf/use-state "overview")
        tab         (deref tab*)
        days*       (mf/use-state 90)
        days        (deref days*)
        project-id* (mf/use-state nil)
        project-id  (deref project-id*)

        {:keys [loading error perms team-health project-health metrics]} state
        health      (or project-health team-health)
        weekly      (mf/with-memo [metrics days]
                      (fill-weeks (:weekly metrics) days))

        project-options
        (mf/with-memo [team-health]
          (into [{:id "all" :label (tr "dashboard.health.project.all")}]
                (map (fn [p] {:id (dm/str (:project-id p)) :label (project-name p)}))
                (sort-by (juxt (complement :is-default) :project-name) (:projects team-health))))

        period-opts
        (mf/with-memo []
          (mapv (fn [{:keys [id]}] {:id id :label (tr "dashboard.health.period" id)})
                period-options))

        tabs
        (mf/with-memo [project-id]
          [{:id "overview" :label (tr "dashboard.health.tab.overview")}
           {:id "trends" :label (tr "dashboard.health.tab.trends")}
           {:id "projects" :label (if project-id
                                    (tr "dashboard.health.tab.files")
                                    (tr "dashboard.health.tab.projects"))}
           {:id "areas" :label (tr "dashboard.health.tab.areas")}
           {:id "rules" :label (tr "dashboard.health.tab.rules")}])

        on-period  (mf/use-fn #(reset! days* (js/parseInt % 10)))
        on-project (mf/use-fn #(reset! project-id* (when (not= "all" %) (uuid/parse* %))))
        on-select-project
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event) (dom/get-data "project-id"))]
             (reset! project-id* (uuid/parse* id)))))]

    (mf/with-effect [team]
      (let [tname (if (:is-default team)
                    (tr "dashboard.personal-projects")
                    (:name team))]
        (dom/set-html-title (tr "title.dashboard.health" tname))))

    (mf/with-effect [team-id project-id days]
      (when team-id
        (load! state* {:team-id team-id :project-id project-id :days days})))

    [:*
     [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
      [:div#dashboard-health-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.health.title")]]
      (when (:can-view-health perms)
        [:div {:class (stl/css :filters)}
         [:> select* {:options project-options
                      :default-selected (if project-id (dm/str project-id) "all")
                      :on-change on-project}]
         [:> select* {:options period-opts
                      :default-selected (str days)
                      :on-change on-period}]])]

     [:section {:class (stl/css :health-container)}
      (cond
        (and loading (nil? perms))
        [:div {:class (stl/css :centered)}
         [:> loader* {:title (tr "labels.loading") :overlay false}]]

        error
        [:> empty-state* {:icon i/status-alert :text (tr "dashboard.health.error")}]

        (not (:can-view-health perms))
        [:> empty-state* {:icon i/lock :text (tr "dashboard.health.no-access")}]

        (zero? (:files-audited team-health 0))
        [:> empty-state* {:icon i/status-alert :text (tr "dashboard.health.empty")}]

        :else
        [:div {:class (stl/css-case :health-content true :reloading loading)}
         [:p {:class (stl/css :subtitle)} (tr "dashboard.health.subtitle")]
         [:> tab-switcher* {:tabs tabs
                            :selected tab
                            :on-change #(reset! tab* %)}
          (case tab
            "overview" [:> overview-tab* {:health health :metrics metrics}]
            "trends"   [:> trends-tab* {:weekly weekly}]
            "projects" (if project-id
                         [:> files-tab* {:files (:files project-health)}]
                         [:> projects-tab* {:projects (:projects team-health)
                                            :on-select on-select-project}])
            "areas"    [:> areas-tab* {:by-area (:by-area metrics)}]
            "rules"    [:> rules-tab* {:by-rule (:by-rule metrics)}]
            nil)]
         [:p {:class (stl/css :privacy)} (tr "dashboard.health.privacy")]])]]))
