;; Proto Care — workspace modes
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.workspace.mode-switcher
  "Segmented control with the workspace modes (Design, Prototype,
   Inspect, Audit). Lives in the floating toolbar and drives the right
   sidebar content, replacing the sidebar tab switcher."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn set-mode!
  "Switches the workspace mode. Inspect is read-only, like the sidebar
   tab it replaces; every other mode can edit."
  [mode]
  (st/emit! (dw/set-options-mode mode))
  (if (= mode :inspect)
    (st/emit! :interrupt (dwc/set-workspace-read-only true))
    (st/emit! :interrupt (dwc/set-workspace-read-only false))))

(defn- modes
  []
  (cond-> [{:id :design :label (tr "workspace.options.design")}
           {:id :prototype :label (tr "workspace.options.prototype")}
           {:id :inspect :label (tr "workspace.options.inspect")}
           {:id :audit :label (tr "workspace.options.audit")}]
    (dbg/enabled? :shape-panel)
    (conj {:id :debug :label "Debug"})))

(mf/defc mode-switcher*
  []
  (let [current  (mf/deref refs/options-mode-global)
        _dbg     (mf/deref dbg/state)

        on-click
        (mf/use-fn
         (fn [event]
           (let [mode (-> (dom/get-current-target event)
                          (dom/get-data "mode")
                          (keyword))]
             (dom/blur! (dom/get-current-target event))
             (set-mode! mode))))]

    [:div {:role "tablist"
           :aria-label (tr "workspace.governance.modes")
           :class (stl/css :mode-switcher)}
     (for [{:keys [id label]} (modes)]
       [:button {:key (name id)
                 :role "tab"
                 :type "button"
                 :aria-selected (= id current)
                 :data-mode (name id)
                 :class (stl/css-case :mode true
                                      :mode-selected (= id current))
                 :on-click on-click}
        label])]))
