;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.fix
  "Turns fixable audit results into regular (undoable) file changes.

   Flow enforced by the product: Detect -> Explain -> Preview -> Approve
   -> Apply. This namespace only produces the changes; who applies them
   (and when) is up to the caller, after the user approved."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.types.text :as txt]
   [app.common.types.token :as cto]
   [app.governance.context :as gctx]))

(defmulti apply-fix-to-shape
  "Returns the shape with `fix` applied. Pure."
  (fn [fix _shape _ctx] (:type fix)))

(defmethod apply-fix-to-shape :default
  [fix _ _]
  (throw (ex-info "unknown fix type" {:fix fix})))

(defn- replace-color
  "Replaces every `key` whose normalized value equals `from` by `to`."
  [items key from to]
  (mapv (fn [item]
          (if (= from (gctx/normalize-color (get item key)))
            (assoc item key to)
            item))
        items))

(defmethod apply-fix-to-shape :apply-color-token
  [{:keys [token-name attribute current-value]} shape ctx]
  (let [token    (get-in ctx [:tokens token-name])
        resolved (gctx/resolve-color ctx token-name)
        from     (gctx/normalize-color current-value)]
    (when (nil? token)
      (throw (ex-info "token not active" {:token-name token-name})))
    (when (nil? resolved)
      (throw (ex-info "token does not resolve to a color" {:token-name token-name})))
    (let [shape (cto/apply-token-to-shape {:shape shape
                                           :token token
                                           :attributes [attribute]})]
      (case attribute
        :fill
        (if (cfh/text-shape? shape)
          (txt/update-text-content shape txt/is-text-node?
                                   (fn [node _]
                                     (update node :fills replace-color :fill-color from resolved))
                                   nil)
          (update shape :fills replace-color :fill-color from resolved))

        :stroke-color
        (update shape :strokes replace-color :stroke-color from resolved)))))

(defn generate-fix-changes
  "Appends to `changes` the operations that apply the fixes of `results`
   (only those with `:fix-available`). Changes are grouped per page and
   carry undo operations."
  [changes ctx results]
  (reduce (fn [changes [page-id results]]
            (let [objects (gctx/page-objects ctx page-id)
                  changes (-> changes
                              (pcb/with-page-id page-id)
                              (pcb/with-objects objects))]
              (reduce (fn [changes {:keys [node-id fix]}]
                        (pcb/update-shapes changes [node-id] #(apply-fix-to-shape fix % ctx)))
                      changes
                      results)))
          changes
          (group-by :page-id (filter :fix-available results))))
