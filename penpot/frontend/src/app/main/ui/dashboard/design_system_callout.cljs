;; Proto Care — bundled design system
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.ui.dashboard.design-system-callout
  "Callout on the Libraries page that installs the bundled Proto Care DS
   in the team as a shared library."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.notifications :as ntf]
   [app.main.data.team :as dtm]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def design-system-name
  "Must match `app.rpc.commands.governance/design-system-name`."
  "Proto Care DS")

(defn installed?
  [files]
  (boolean (some #(= design-system-name (:name %)) files)))

(mf/defc design-system-callout*
  [{:keys [team-id]}]
  (let [installing* (mf/use-state false)
        installing  (deref installing*)

        on-install
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (reset! installing* true)
           (->> (rp/cmd! :install-design-system {:team-id team-id})
                (rx/subs!
                 (fn [_]
                   (reset! installing* false)
                   (st/emit! (dtm/fetch-shared-files team-id)
                             (ntf/success (tr "dashboard.protocare-ds.installed"))))
                 (fn [_]
                   (reset! installing* false)
                   (st/emit! (ntf/error (tr "dashboard.protocare-ds.error"))))))))]

    [:section {:class (stl/css :callout)}
     [:img {:class (stl/css :callout-image)
            :src (dm/str cf/public-uri "images/thumbnails/template-protocare-ds.jpg")
            :alt ""}]
     [:div {:class (stl/css :callout-text)}
      [:h2 {:class (stl/css :callout-title)} (tr "dashboard.protocare-ds.title")]
      [:p {:class (stl/css :callout-description)} (tr "dashboard.protocare-ds.description")]]
     [:> button* {:variant "primary"
                  :disabled installing
                  :on-click on-install}
      (if installing
        (tr "dashboard.protocare-ds.installing")
        (tr "dashboard.protocare-ds.add"))]]))
