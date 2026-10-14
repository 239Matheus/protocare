;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.governance.rules-test
  "One positive and one negative case per rule of the design system,
   accessibility, interface quality, layer semantics and usability areas."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.governance.context :as gctx]
   [app.governance.engine :as engine]
   [app.governance.fix :as fix]
   [app.governance.rules.accessibility :as a11y]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- token
  [set-id name type value]
  #(ctob/add-token % set-id (ctob/make-token :name name :type type :value value)))

(defn- navigate-to
  [label]
  [{:event-type :click
    :action-type :navigate
    :destination (if (keyword? label) (thi/id label) label)
    :preserve-scroll false}])

(defn- text
  [file label content {:keys [size weight line-height color] :as params}]
  (-> file
      (tho/add-text label content :text-params (-> params
                                                   (dissoc :size :weight :line-height :color)
                                                   (assoc :name content)))
      (cond->
       size        (ths/update-shape-text label :font-size (str size))
       weight      (ths/update-shape-text label :font-weight (str weight))
       line-height (ths/update-shape-text label :line-height (str line-height))
       color       (ths/update-shape-text label :fills [{:fill-color color :fill-opacity 1}]))))

(defn- setup-file
  []
  (let [set-id (thi/new-id! :core)
        lib-fn (fn [lib]
                 (reduce (fn [lib f] (f lib))
                         (-> lib
                             (ctob/add-set (ctob/make-token-set :id set-id :name "core"))
                             (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :light)
                                                                    :name "light"
                                                                    :sets #{"core"})))
                         [(token set-id "color.text.primary" :color "#111827")
                          (token set-id "color.bg.surface" :color "#ffffff")
                          (token set-id "space.2" :spacing "8")
                          (token set-id "space.4" :spacing "16")
                          (token set-id "radius.md" :border-radius "8")
                          (token set-id "typography.body.md" :typography
                                 {:font-size "16" :font-weight "400" :line-height "1.5"})]))]
    (-> (tht/sample-file-with-tokens
         :lib-fn lib-fn
         :status-fn #(ctos/set-tokens-status % #{(thi/id :light)} #{set-id}))

        ;; Screen with a known white background
        (tho/add-frame :screen :name "Screen" :x 0 :y 0 :width 2000 :height 1200
                       :fills [{:fill-color "#ffffff" :fill-opacity 1}])

        ;; token/spacing
        (tho/add-frame :card-layout :name "Card" :parent-label :screen
                       :x 40 :y 40 :width 200 :height 60 :fills []
                       :layout :flex :layout-flex-dir :row
                       :layout-gap-type :multiple :layout-gap {:row-gap 0 :column-gap 8}
                       :layout-align-items :start :layout-justify-content :start
                       :layout-align-content :stretch :layout-wrap-type :nowrap
                       :layout-padding-type :multiple :layout-padding {:p1 8 :p2 16 :p3 8 :p4 16})
        (tho/add-frame :odd-layout :name "Odd layout" :parent-label :screen
                       :x 300 :y 40 :width 200 :height 60 :fills []
                       :layout :flex :layout-flex-dir :row
                       :layout-gap-type :multiple :layout-gap {:row-gap 0 :column-gap 0}
                       :layout-align-items :start :layout-justify-content :start
                       :layout-align-content :stretch :layout-wrap-type :nowrap
                       :layout-padding-type :multiple :layout-padding {:p1 5 :p2 0 :p3 0 :p4 0})

        ;; token/radius
        (ths/add-sample-shape :rounded :name "Rounded" :parent-label :screen
                              :x 560 :y 40 :width 40 :height 40 :fills [] :r1 8 :r2 8 :r3 8 :r4 8)
        (ths/add-sample-shape :odd-radius :name "Odd radius" :parent-label :screen
                              :x 640 :y 40 :width 40 :height 40 :fills [] :r1 5 :r2 5 :r3 5 :r4 5)

        ;; token/typography, contrast, text size
        (text :body "Body" {:parent-label :screen :x 720 :y 40 :width 100 :height 24
                            :size 16 :weight 400 :line-height 1.5 :color "#111827"})
        (text :odd-text "Odd" {:parent-label :screen :x 840 :y 40 :width 100 :height 24
                               :size 13 :weight 400 :line-height 1.5 :color "#111827"})
        (text :low-contrast "Low" {:parent-label :screen :x 960 :y 40 :width 100 :height 24
                                   :size 14 :color "#9ca3af"})
        (text :tiny "Tiny" {:parent-label :screen :x 1080 :y 40 :width 100 :height 24
                            :size 10 :color "#111827"})

        ;; touch target + usability
        (tho/add-frame :other :name "Other screen" :x 3000 :y 0 :width 390 :height 844)
        (ths/add-sample-shape :tap :name "Tap" :parent-label :screen
                              :x 1200 :y 40 :width 20 :height 20 :fills []
                              :interactions (navigate-to :other))
        (ths/add-sample-shape :big-tap :name "Big tap" :parent-label :screen
                              :x 1260 :y 40 :width 48 :height 48 :fills []
                              :interactions (navigate-to (uuid/next)))

        ;; layout/alignment
        (tho/add-frame :align-box :name "Align box" :parent-label :screen
                       :x 0 :y 400 :width 400 :height 400 :fills [])
        (ths/add-sample-shape :a1 :name "A1" :parent-label :align-box :x 20 :y 420 :width 50 :height 20 :fills [])
        (ths/add-sample-shape :a2 :name "A2" :parent-label :align-box :x 22 :y 520 :width 50 :height 20 :fills [])
        (ths/add-sample-shape :a3 :name "A3" :parent-label :align-box :x 20 :y 620 :width 50 :height 20 :fills [])
        (ths/add-sample-shape :a4 :name "A4" :parent-label :align-box :x 200 :y 720 :width 50 :height 20 :fills [])

        ;; layout/spacing
        (tho/add-frame :stack :name "Stack" :parent-label :screen
                       :x 500 :y 400 :width 400 :height 400 :fills [])
        (ths/add-sample-shape :s1 :name "S1" :parent-label :stack :x 520 :y 420 :width 50 :height 10 :fills [])
        (ths/add-sample-shape :s2 :name "S2" :parent-label :stack :x 520 :y 438 :width 50 :height 10 :fills [])
        (ths/add-sample-shape :s3 :name "S3" :parent-label :stack :x 520 :y 456 :width 50 :height 10 :fills [])
        (ths/add-sample-shape :s4 :name "S4" :parent-label :stack :x 520 :y 472 :width 50 :height 10 :fills [])
        (ths/add-sample-shape :s5 :name "S5" :parent-label :stack :x 520 :y 502 :width 50 :height 10 :fills [])

        ;; naming
        (ths/add-sample-shape :default-name :name "Rectangle 2" :parent-label :screen
                              :x 1400 :y 40 :width 10 :height 10 :fills [])
        (ths/add-sample-shape :dup1 :name "Item" :parent-label :screen
                              :x 1460 :y 40 :width 10 :height 10 :fills [])
        (ths/add-sample-shape :dup2 :name "Item" :parent-label :screen
                              :x 1520 :y 40 :width 10 :height 10 :fills []))))

(defn- by-rule
  [audit rule-id]
  (filter #(= rule-id (:rule-id %)) (:results audit)))

(defn- nodes
  [results]
  (set (map :node-id results)))

(defn- run
  []
  (let [file (setup-file)]
    [file (engine/run-audit (gctx/build-context file))]))

(t/deftest design-system-rules
  (let [[_ audit] (run)]
    (t/testing "token/spacing"
      (let [rs (by-rule audit "token/spacing")
            card (first (filter #(= (thi/id :card-layout) (:node-id %)) rs))
            odd  (first (filter #(= (thi/id :odd-layout) (:node-id %)) rs))]
        (t/is (= 2 (count rs)))
        (t/is (true? (:fix-available card)))
        (t/is (= {:p1 "space.2" :p2 "space.4" :p3 "space.2" :p4 "space.4" :column-gap "space.2"}
                 (get-in card [:fix :tokens])))
        (t/is (false? (:fix-available odd)))))

    (t/testing "token/radius"
      (let [rs (by-rule audit "token/radius")]
        (t/is (= #{(thi/id :rounded) (thi/id :odd-radius)} (nodes rs)))
        (t/is (= ["radius.md"]
                 (->> rs (filter :fix-available) (map :expected-value))))))

    (t/testing "token/typography"
      (let [rs   (by-rule audit "token/typography")
            body (first (filter #(= (thi/id :body) (:node-id %)) rs))
            odd  (first (filter #(= (thi/id :odd-text) (:node-id %)) rs))]
        (t/is (= "typography.body.md" (:expected-value body)))
        (t/is (true? (:fix-available body)))
        (t/is (false? (:fix-available odd)))))))

(t/deftest accessibility-rules
  (let [[_ audit] (run)]
    (t/testing "contrast ratio math"
      (t/is (< 20.9 (a11y/contrast-ratio "#000000" "#ffffff") 21.1))
      (t/is (< 2.4 (a11y/contrast-ratio "#9ca3af" "#ffffff") 2.6)))

    (t/testing "accessibility/contrast"
      (t/is (= #{(thi/id :low-contrast)} (nodes (by-rule audit "accessibility/contrast")))))

    (t/testing "accessibility/text-size"
      (t/is (= #{(thi/id :tiny)} (nodes (by-rule audit "accessibility/text-size")))))

    (t/testing "accessibility/touch-target"
      (t/is (= #{(thi/id :tap)} (nodes (by-rule audit "accessibility/touch-target")))))))

(t/deftest interface-quality-rules
  (let [[_ audit] (run)]
    (t/testing "layout/alignment reports only the odd one out"
      (let [[r :as rs] (by-rule audit "layout/alignment")]
        (t/is (= #{(thi/id :a2)} (nodes rs)))
        (t/is (= "2px" (:current-value r)))
        (t/is (= ["A2" "2px" "left" "A1"] (:message-args r)))))

    (t/testing "layout/spacing reports the gap that almost matches the rhythm"
      (let [[r :as rs] (by-rule audit "layout/spacing")]
        (t/is (= 1 (count rs)))
        (t/is (= (thi/id :s4) (:node-id r)))
        (t/is (= "6px" (:current-value r)))
        (t/is (= "8px" (:expected-value r)))))))

(t/deftest layer-semantics-rules
  (let [[_ audit] (run)]
    (t/testing "naming/default-name"
      (let [ns (nodes (by-rule audit "naming/default-name"))]
        (t/is (contains? ns (thi/id :default-name)))
        (t/is (not (contains? ns (thi/id :tap))))))

    (t/testing "naming/duplicate reports only the second sibling"
      (let [rs (by-rule audit "naming/duplicate")]
        (t/is (= 1 (count rs)))
        (t/is (contains? #{(thi/id :dup1) (thi/id :dup2)} (:node-id (first rs))))))))

(t/deftest usability-rules
  (let [[_ audit] (run)]
    (t/testing "usability/broken-link"
      (t/is (= #{(thi/id :big-tap)} (nodes (by-rule audit "usability/broken-link")))))

    (t/testing "usability/flow"
      (t/is (= #{(thi/id :screen)} (nodes (by-rule audit "usability/flow")))))

    (t/testing "usability/dead-end"
      (t/is (= #{(thi/id :other)} (nodes (by-rule audit "usability/dead-end")))))

    (t/testing "summary groups results by area"
      (let [by-area (get-in audit [:summary :by-area])]
        (t/is (every? pos? (map #(get by-area % 0)
                                ["design-system" "accessibility" "ui-quality"
                                 "layer-semantics" "usability"])))))))

(t/deftest numeric-fixes-bind-tokens
  (let [[file audit] (run)
        ctx     (gctx/build-context file)
        fixable (->> (:results audit)
                     (filter :fix-available)
                     (filter #(contains? #{"token/spacing" "token/radius" "token/typography"} (:rule-id %))))
        changes (-> (pcb/empty-changes nil (thf/current-page-id file))
                    (fix/generate-fix-changes ctx fixable))
        file'   (thf/apply-changes file changes)
        audit'  (engine/run-audit (gctx/build-context file'))]
    (t/is (= 3 (count fixable)))
    (t/is (= "space.2" (get-in (ths/get-shape file' :card-layout) [:applied-tokens :p1])))
    (t/is (= "radius.md" (get-in (ths/get-shape file' :rounded) [:applied-tokens :r1])))
    (t/is (= "typography.body.md" (get-in (ths/get-shape file' :body) [:applied-tokens :typography])))
    (t/is (empty? (filter :fix-available (by-rule audit' "token/radius"))))
    (t/is (= 1 (count (by-rule audit' "token/spacing"))))))
