;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.main.data.workspace.governance
  "Workspace events for the governance panel: run audits, navigate to a
   result and apply approved fixes through the regular change pipeline."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.governance.context :as gctx]
   [app.governance.fix :as gfix]
   [app.main.data.changes :as dch]
   [app.main.data.common :as dcm]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.pages :as-alias dwpg]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.zoom :as dwz]
   [app.main.repo :as rp]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def default-filters
  {:area "all"
   :severity "all"
   :status "open"})

(def default-state
  {:status :idle
   :audit nil
   :filters default-filters})

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-governance #(or % default-state)))))

;; --- Audit

(defn- audit-loaded
  [audit]
  (ptk/reify ::audit-loaded
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-governance
              (fn [local]
                (-> (or local default-state)
                    (assoc :status :loaded
                           :audit audit)))))))

(defn- audit-failed
  []
  (ptk/reify ::audit-failed
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-governance :status] :error))))

(defn run-audit
  "Runs the Governance Engine on the backend over the saved version of the
   current file and loads the results."
  []
  (ptk/reify ::run-audit
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-governance
              #(assoc (or % default-state) :status :running)))

    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :run-audit {:file-id file-id})
             (rx/map audit-loaded)
             (rx/catch (fn [_]
                         (rx/of (audit-failed)
                                (ntf/error (tr "workspace.governance.run-error"))))))))))

(defn fetch-latest-audit
  "Loads the most recent audit of the file, if any, without running a new one."
  []
  (ptk/reify ::fetch-latest-audit
    ptk/WatchEvent
    (watch [_ state _]
      (let [file-id (:current-file-id state)]
        (->> (rp/cmd! :get-file-audits {:file-id file-id :limit 1})
             (rx/mapcat (fn [audits]
                          (if-let [audit (first audits)]
                            (->> (rp/cmd! :get-audit {:id (:id audit)})
                                 (rx/map audit-loaded))
                            (rx/empty))))
             (rx/catch (fn [_] (rx/empty))))))))

(defn set-filter
  [key value]
  (ptk/reify ::set-filter
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-governance :filters key] value))))

;; --- Navigation

(defn go-to-result
  "Selects the node of a result and centers the viewport on it, switching
   page first when needed."
  [{:keys [page-id node-id]}]
  (ptk/reify ::go-to-result
    ptk/WatchEvent
    (watch [_ state stream]
      (let [current-page-id (:current-page-id state)
            ids             (d/ordered-set node-id)

            select-and-zoom
            (fn []
              (rx/of (dws/select-shapes ids)
                     dwz/zoom-to-selected-shape))

            exists?
            (fn [page-id]
              (contains? (dsh/lookup-page-objects state page-id) node-id))]

        (cond
          (= page-id current-page-id)
          (if (exists? page-id)
            (select-and-zoom)
            (rx/of (ntf/warn (tr "workspace.governance.node-missing"))))

          :else
          (rx/merge
           (->> stream
                (rx/filter (ptk/type? ::dwpg/initialize-page))
                (rx/take 1)
                (rx/observe-on :async)
                (rx/mapcat (fn [_] (select-and-zoom))))
           (rx/of (dcm/go-to-workspace :page-id page-id))))))))

;; --- Fix

(defn- result-updated
  [result]
  (ptk/reify ::result-updated
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-governance :audit :results]
                 (fn [results]
                   (mapv #(if (= (:id %) (:id result)) (merge % result) %) results))))))

(defn set-result-status
  "Marks a result as open, fixed or ignored on the backend."
  [result-id status]
  (ptk/reify ::set-result-status
    ptk/WatchEvent
    (watch [_ _ _]
      (->> (rp/cmd! :update-audit-result-status {:id result-id :status status})
           (rx/map result-updated)
           (rx/catch (fn [_]
                       (rx/of (ntf/error (tr "workspace.governance.status-error")))))))))

(defn apply-fix
  "Applies the fix of a result (after the user approved it) as an undoable
   change, then marks the result as fixed."
  [result]
  (ptk/reify ::apply-fix
    ptk/WatchEvent
    (watch [it state _]
      (let [file-id   (:current-file-id state)
            file      (dsh/lookup-file state file-id)
            libraries (dsh/lookup-libraries state)
            page-id   (:page-id result)
            undo-id   (js/Symbol)]
        (try
          (let [ctx     (gctx/build-context file
                                            :libraries libraries
                                            :page-id page-id)
                changes (-> (pcb/empty-changes it page-id)
                            (gfix/generate-fix-changes ctx [result]))]
            (rx/concat
             (rx/of (dwu/start-undo-transaction undo-id)
                    (dch/commit-changes changes)
                    (dwu/commit-undo-transaction undo-id))
             (rx/of (set-result-status (:id result) "fixed"))
             (rx/of (ntf/success (tr "workspace.governance.fix-applied")))))
          (catch :default _
            (rx/of (ntf/error (tr "workspace.governance.fix-error")))))))))
