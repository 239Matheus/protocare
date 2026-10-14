;; Proto Care — design health dashboard
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.dashboard.health
  "\"Saúde do design\": aggregated governance metrics of a team or one of
   its projects (docs/03-analytics-plan.md, section 4).

   Tabs: Overview, Trends, Explore (macro to micro: projects, files,
   screens, findings), Areas, Rules and People. Filters: project, design
   system theme (only themes in use) and period. Data comes from
   `get-team-health`, `get-project-health`, `get-governance-metrics`,
   `get-file-health` and `get-governance-people`. Quality numbers are
   aggregated, never attributed to people."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.governance.areas :as areas]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
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
  [state* {:keys [team-id project-id file-id theme days]}]
  (swap! state* assoc :loading true :error nil)
  (->> (rp/cmd! :get-governance-permissions {:team-id team-id})
       (rx/mapcat
        (fn [perms]
          (if-not (:can-view-health perms)
            (rx/of {:perms perms})
            (->> (rx/zip (rp/cmd! :get-team-health
                                  (cond-> {:team-id team-id}
                                    theme (assoc :theme theme)))
                         (if project-id
                           (rp/cmd! :get-project-health
                                    (cond-> {:project-id project-id}
                                      theme (assoc :theme theme)))
                           (rx/of nil))
                         (rp/cmd! :get-governance-metrics
                                  (cond-> {:team-id team-id :since (since-days days)}
                                    project-id (assoc :project-id project-id)
                                    theme (assoc :theme theme)))
                         (rp/cmd! :get-governance-people
                                  (cond-> {:team-id team-id :since (since-days days)}
                                    project-id (assoc :project-id project-id)
                                    file-id (assoc :file-id file-id)))
                         (if file-id
                           (rp/cmd! :get-file-health {:file-id file-id})
                           (rx/of nil)))
                 (rx/map (fn [[team-health project-health metrics people file-health]]
                           {:perms perms
                            :team-health team-health
                            :project-health project-health
                            :metrics metrics
                            :people people
                            :file-health file-health}))))))
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
  [{:keys [files on-select]}]
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
      [:tr {:key (dm/str (:file-id f))
            :class (stl/css :clickable)
            :data-file-id (dm/str (:file-id f))
            :on-click on-select}
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

(defn- status-label
  [status]
  (case status
    "fixed"   (tr "workspace.governance.status.fixed")
    "ignored" (tr "workspace.governance.status.ignored")
    (tr "workspace.governance.status.open")))

(defn- role-label
  [role]
  (case role
    "design-ops" (tr "dashboard.health.role.design-ops")
    "ds-admin"   (tr "dashboard.health.role.ds-admin")
    (tr "dashboard.health.role.designer")))

(def ^:private no-board "none")

(defn- board-key
  [board-id]
  (if board-id (dm/str board-id) no-board))

(defn- board-label
  [board-name]
  (or board-name (tr "dashboard.health.no-board")))

(mf/defc breadcrumb*
  "Where the Explore tab is, from the team to one screen."
  {::mf/private true}
  [{:keys [items on-select]}]
  [:nav {:class (stl/css :breadcrumb) :aria-label (tr "dashboard.health.tab.explore")}
   (for [[idx {:keys [level label]}] (d/enumerate items)]
     (let [last? (= idx (dec (count items)))]
       [:span {:key level :class (stl/css :crumb)}
        (if last?
          [:span {:class (stl/css :crumb-current)} label]
          [:button {:type "button"
                    :class (stl/css :crumb-link)
                    :data-level level
                    :on-click on-select}
           label])
        (when-not last?
          [:span {:class (stl/css :crumb-sep)} "/"])]))])

(mf/defc file-boards*
  "One file: its screens (top-level boards) with open findings."
  {::mf/private true}
  [{:keys [file-health on-select on-open]}]
  (let [audit  (:audit file-health)
        boards (:boards file-health)]
    [:*
     [:div {:class (stl/css :file-head)}
      [:div {:class (stl/css :file-meta)}
       [:h2 {:class (stl/css :card-title)} (:file-name file-health)]
       (if audit
         [:p {:class (stl/css :card-desc)}
          (tr "dashboard.health.file.last-audit"
              (ct/timeago (:created-at audit))
              (fmt-int (get-in audit [:summary :total])))]
         [:p {:class (stl/css :card-desc)} (tr "dashboard.health.file.no-audit")])
       (when (seq (:themes audit))
         [:div {:class (stl/css :chips)}
          (for [theme (:themes audit)]
            [:span {:key theme :class (stl/css :chip)} theme])])]
      [:> button* {:variant "secondary" :on-click on-open}
       (tr "dashboard.health.file.open")]]
     (when (seq boards)
       [:table {:class (stl/css :table)}
        [:thead
         [:tr
          [:th (tr "dashboard.health.col.board")]
          [:th {:class (stl/css :num)} (tr "dashboard.health.col.open")]
          [:th {:class (stl/css :num)} (tr "dashboard.health.col.fixable")]
          [:th (tr "dashboard.health.col.areas")]]]
        [:tbody
         (for [b boards]
           [:tr {:key (board-key (:board-id b))
                 :class (stl/css :clickable)
                 :data-board (board-key (:board-id b))
                 :on-click on-select}
            [:td {:class (stl/css :strong)} (board-label (:board-name b))]
            [:td {:class (stl/css :num)} (fmt-int (:open b))]
            [:td {:class (stl/css :num)} (fmt-int (:fixable b))]
            [:td (->> (:by-area b)
                      (sort-by (comp - val))
                      (map (fn [[area n]] (dm/str (texts/area-label area) " " n)))
                      (interpose " · ")
                      (apply str))]])]])]))

(mf/defc board-findings*
  "One screen: every finding of the latest audit on it."
  {::mf/private true}
  [{:keys [results on-open]}]
  (let [results (sort-by (fn [r] [(if (= "open" (:status r)) 0 1)
                                  (.indexOf (clj->js severity-order) (texts/severity-name (:severity r)))])
                         results)]
    [:*
     [:div {:class (stl/css :file-head)}
      [:p {:class (stl/css :card-desc)}
       (tr "dashboard.health.board.count"
           (fmt-int (count (filter #(= "open" (:status %)) results)))
           (fmt-int (count results)))]
      [:> button* {:variant "secondary" :on-click on-open}
       (tr "dashboard.health.file.open")]]
     [:ul {:class (stl/css :findings)}
      (for [r results]
        [:li {:key (dm/str (:id r))
              :class (stl/css-case :finding true
                                   :finding-closed (not= "open" (:status r)))}
         [:div {:class (stl/css :finding-head)}
          [:span {:class (stl/css-case :sev true
                                       :sev-critical (= "critical" (texts/severity-name (:severity r)))
                                       :sev-high (= "high" (texts/severity-name (:severity r))))}
           (texts/severity-label (:severity r))]
          [:span {:class (stl/css :finding-rule)} (texts/rule-title (:rule-id r) (:rule-id r))]
          [:span {:class (stl/css :finding-area)} (texts/area-label (:area r))]
          (when (not= "open" (:status r))
            [:span {:class (stl/css :finding-status)} (status-label (:status r))])]
         [:p {:class (stl/css :finding-layer)} (:node-name r)]
         [:p {:class (stl/css :finding-message)} (texts/message r)]])]]))

(mf/defc people-tab*
  {::mf/private true}
  [{:keys [people]}]
  [:*
   [:p {:class (stl/css :tab-hint)} (tr "dashboard.health.people.hint")]
   (if (empty? people)
     [:> empty-state* {:icon i/user :text (tr "dashboard.health.people.empty")}]
     [:table {:class (stl/css :table)}
      [:thead
       [:tr
        [:th (tr "dashboard.health.col.person")]
        [:th (tr "dashboard.health.col.role")]
        [:th {:class (stl/css :num)} (tr "dashboard.health.col.files")]
        [:th (tr "dashboard.health.col.last-activity")]]]
      [:tbody
       (for [p people]
         [:tr {:key (dm/str (:profile-id p))}
          [:td {:class (stl/css :strong)}
           [:span {:class (stl/css :person)}
            [:span {:class (stl/css :avatar) :aria-hidden true}
             (->> (str (:fullname p))
                  (re-seq #"\S+")
                  (take 2)
                  (map first)
                  (apply str)
                  (.toUpperCase))]
            (:fullname p)]]
          [:td (role-label (:role p))]
          [:td {:class (stl/css :num)} (fmt-int (:files p))]
          [:td (ct/timeago (:last-activity-at p))]])]])])

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
        theme*      (mf/use-state nil)
        theme       (deref theme*)
        file-id*    (mf/use-state nil)
        file-id     (deref file-id*)
        board*      (mf/use-state nil)
        board       (deref board*)

        {:keys [loading error perms team-health project-health metrics people file-health]} state
        health      (or project-health team-health)
        weekly      (mf/with-memo [metrics days]
                      (fill-weeks (:weekly metrics) days))

        project-options
        (mf/with-memo [team-health]
          (into [{:id "all" :label (tr "dashboard.health.project.all")}]
                (map (fn [p] {:id (dm/str (:project-id p)) :label (project-name p)}))
                (sort-by (juxt (complement :is-default) :project-name) (:projects team-health))))

        theme-options
        (mf/with-memo [team-health]
          (into [{:id "all" :label (tr "dashboard.health.theme.all")}]
                (map (fn [t] {:id t :label t}))
                (:themes team-health)))

        period-opts
        (mf/with-memo []
          (mapv (fn [{:keys [id]}] {:id id :label (tr "dashboard.health.period" id)})
                period-options))

        tabs
        (mf/with-memo [project-id]
          [{:id "overview" :label (tr "dashboard.health.tab.overview")}
           {:id "trends" :label (tr "dashboard.health.tab.trends")}
           {:id "projects" :label (tr "dashboard.health.tab.explore")}
           {:id "areas" :label (tr "dashboard.health.tab.areas")}
           {:id "rules" :label (tr "dashboard.health.tab.rules")}
           {:id "people" :label (tr "dashboard.health.tab.people")}])

        on-period  (mf/use-fn #(reset! days* (js/parseInt % 10)))
        on-project (mf/use-fn
                    (fn [id]
                      (reset! project-id* (when (not= "all" id) (uuid/parse* id)))
                      (reset! file-id* nil)
                      (reset! board* nil)))
        on-theme   (mf/use-fn #(reset! theme* (when (not= "all" %) %)))
        on-select-project
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event) (dom/get-data "project-id"))]
             (reset! project-id* (uuid/parse* id))
             (reset! file-id* nil)
             (reset! board* nil))))
        on-select-file
        (mf/use-fn
         (fn [event]
           (let [id (-> (dom/get-current-target event) (dom/get-data "file-id"))]
             (reset! file-id* (uuid/parse* id))
             (reset! board* nil))))
        on-select-board
        (mf/use-fn
         (fn [event]
           (reset! board* (-> (dom/get-current-target event) (dom/get-data "board")))))
        on-crumb
        (mf/use-fn
         (fn [event]
           (case (-> (dom/get-current-target event) (dom/get-data "level"))
             "team"    (do (reset! project-id* nil) (reset! file-id* nil) (reset! board* nil))
             "project" (do (reset! file-id* nil) (reset! board* nil))
             "file"    (reset! board* nil)
             nil)))
        on-open-file
        (mf/use-fn
         (mf/deps team-id file-health board)
         (fn []
           (let [page-id (or (some #(when (= board (board-key (:board-id %))) (:page-id %))
                                   (:boards file-health))
                             (some :page-id (:results file-health)))]
             (st/emit! (rt/nav :workspace (cond-> {:team-id team-id
                                                   :file-id (:file-id file-health)}
                                            page-id (assoc :page-id page-id)))))))

        crumbs
        (cond-> [{:level "team" :label (tr "dashboard.health.project.all")}]
          project-id
          (conj {:level "project"
                 :label (or (some #(when (= project-id (:project-id %)) (project-name %))
                                  (:projects team-health))
                            (tr "dashboard.health.tab.projects"))})
          (and file-id file-health)
          (conj {:level "file" :label (:file-name file-health)})
          (and board file-health)
          (conj {:level "board"
                 :label (board-label (some #(when (= board (board-key (:board-id %))) (:board-name %))
                                           (:boards file-health)))}))

        explore
        (cond
          (and board file-health)
          (mf/html [:> board-findings* {:results (filterv #(= board (board-key (:board-id %)))
                                                          (:results file-health))
                                        :on-open on-open-file}])

          (and file-id file-health)
          (mf/html [:> file-boards* {:file-health file-health
                                     :on-select on-select-board
                                     :on-open on-open-file}])

          project-id
          (mf/html [:> files-tab* {:files (:files project-health)
                                   :on-select on-select-file}])

          :else
          (mf/html [:> projects-tab* {:projects (:projects team-health)
                                      :on-select on-select-project}]))]

    (mf/with-effect [team]
      (let [tname (if (:is-default team)
                    (tr "dashboard.personal-projects")
                    (:name team))]
        (dom/set-html-title (tr "title.dashboard.health" tname))))

    (mf/with-effect [team-id project-id file-id theme days]
      (when team-id
        (load! state* {:team-id team-id :project-id project-id :file-id file-id
                       :theme theme :days days})))

    [:*
     [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
      [:div#dashboard-health-title {:class (stl/css :dashboard-title)}
       [:h1 (tr "dashboard.health.title")]]
      (when (:can-view-health perms)
        [:div {:class (stl/css :filters)}
         [:> select* {:options project-options
                      :default-selected (if project-id (dm/str project-id) "all")
                      :on-change on-project}]
         (when (seq (:themes team-health))
           [:> select* {:options theme-options
                        :default-selected (or theme "all")
                        :on-change on-theme}])
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
            "projects" [:div {:class (stl/css :explore)}
                        [:> breadcrumb* {:items crumbs :on-select on-crumb}]
                        explore]
            "areas"    [:> areas-tab* {:by-area (:by-area metrics)}]
            "rules"    [:> rules-tab* {:by-rule (:by-rule metrics)}]
            "people"   [:> people-tab* {:people people}]
            nil)]
         [:p {:class (stl/css :privacy)} (tr "dashboard.health.privacy")]])]]))
