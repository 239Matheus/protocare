;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns common-tests.governance.token-color-test
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
   [app.governance.areas :as areas]
   [app.governance.context :as gctx]
   [app.governance.engine :as engine]
   [app.governance.fix :as fix]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private purple "#6750A4")

(defn- stroke
  [color]
  {:stroke-style :solid
   :stroke-alignment :inner
   :stroke-width 1
   :stroke-color color
   :stroke-opacity 1})

(defn- setup-file
  []
  (-> (tht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :core)
                                                       :name "core"))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :light)
                                                           :name "light"
                                                           :sets #{"core"}))
                    (ctob/add-token (thi/id :core)
                                    (ctob/make-token :id (thi/new-id! :brand-purple)
                                                     :name "color.brand.purple"
                                                     :type :color
                                                     :value "#6750a4"))
                    (ctob/add-token (thi/id :core)
                                    (ctob/make-token :id (thi/new-id! :action-primary)
                                                     :name "color.action.primary"
                                                     :type :color
                                                     :value "{color.brand.purple}"))
                    (ctob/add-token (thi/id :core)
                                    (ctob/make-token :id (thi/new-id! :surface)
                                                     :name "color.surface"
                                                     :type :color
                                                     :value "rgb(255, 255, 255)")))
       :status-fn #(ctos/set-tokens-status % #{(thi/id :light)} #{(thi/id :core)}))
      (tho/add-frame :frame1 :fills [])
      ;; literal fill that matches a token
      (ths/add-sample-shape :rect-literal
                            :parent-label :frame1
                            :fills [{:fill-color purple :fill-opacity 1}])
      ;; literal fill with no token
      (ths/add-sample-shape :rect-unknown
                            :parent-label :frame1
                            :fills [{:fill-color "#123456" :fill-opacity 1}])
      ;; stroke literal
      (ths/add-sample-shape :rect-stroke
                            :parent-label :frame1
                            :fills []
                            :strokes [(stroke purple)])
      ;; library color reference
      (ths/add-sample-shape :rect-library
                            :parent-label :frame1
                            :fills [{:fill-color purple
                                     :fill-opacity 1
                                     :fill-color-ref-id (uuid/next)
                                     :fill-color-ref-file (thi/id :file1)}])
      ;; tokenized fill
      (ths/add-sample-shape :rect-tokenized
                            :parent-label :frame1
                            :fills [{:fill-color "#ffffff" :fill-opacity 1}])
      (tht/apply-token-to-shape :rect-tokenized "color.surface" [:fill] [:fill] "#ffffff")
      ;; text with literal fills in its content
      (tho/add-text :text1 "Hello")
      (ths/update-shape-text :text1 :fills [{:fill-color purple :fill-opacity 1}])))

(defn- results-for
  [audit label]
  (filter #(= (thi/id label) (:node-id %)) (:results audit)))

(t/deftest context-builds-color-index
  (let [file (setup-file)
        ctx  (gctx/build-context file)]
    (t/is (= 3 (count (:tokens ctx))))
    (t/is (= ["color.action.primary" "color.brand.purple"]
             (get-in ctx [:color-index "#6750a4"])))
    (t/is (= ["color.surface"]
             (get-in ctx [:color-index "#ffffff"])))))

(t/deftest normalize-color-test
  (t/is (= "#6750a4" (gctx/normalize-color "#6750A4")))
  (t/is (= "#aabbcc" (gctx/normalize-color "#abc")))
  (t/is (= "#6750a4" (gctx/normalize-color "#6750A4ff")))
  (t/is (= "#ff0000" (gctx/normalize-color "rgba(255, 0, 0, 0.5)")))
  (t/is (nil? (gctx/normalize-color "{color.brand.purple}")))
  (t/is (nil? (gctx/normalize-color nil))))

(t/deftest detects-literal-colors
  (let [file  (setup-file)
        ctx   (gctx/build-context file)
        audit (engine/run-audit ctx)]

    (t/testing "literal fill matching a token is fixable"
      (let [[r :as rs] (results-for audit :rect-literal)]
        (t/is (= 1 (count rs)))
        (t/is (= "token/color" (:rule-id r)))
        (t/is (= :fill (:attribute r)))
        (t/is (= purple (:current-value r)))
        (t/is (= "color.action.primary" (:expected-value r)))
        (t/is (true? (:fix-available r)))
        (t/is (= :apply-color-token (get-in r [:fix :type])))))

    (t/testing "literal with no token is reported but not fixable"
      (let [[r :as rs] (results-for audit :rect-unknown)]
        (t/is (= 1 (count rs)))
        (t/is (nil? (:expected-value r)))
        (t/is (false? (:fix-available r)))))

    (t/testing "stroke literal is reported on the stroke attribute"
      (let [[r :as rs] (results-for audit :rect-stroke)]
        (t/is (= 1 (count rs)))
        (t/is (= :stroke-color (:attribute r)))
        (t/is (true? (:fix-available r)))))

    (t/testing "text fills inside content are audited"
      (let [[r :as rs] (results-for audit :text1)]
        (t/is (= 1 (count rs)))
        (t/is (= :fill (:attribute r)))
        (t/is (true? (:fix-available r)))))

    (t/testing "tokenized and library colors are governed"
      (t/is (empty? (results-for audit :rect-tokenized)))
      (t/is (empty? (results-for audit :rect-library))))

    (t/testing "summary"
      (let [summary (:summary audit)]
        (t/is (= 4 (:total summary)))
        (t/is (= 3 (:fixable summary)))
        (t/is (= {:medium 4} (:by-severity summary)))
        (t/is (= {"token" 4} (:by-category summary)))
        (t/is (number? (:token-adherence summary)))))))

(t/deftest library-colors-can-be-forbidden
  (let [file  (setup-file)
        ctx   (gctx/build-context file :options {:allow-library-colors? false})
        audit (engine/run-audit ctx)]
    (t/is (= 1 (count (results-for audit :rect-library))))))

(t/deftest rules-can-be-configured
  (let [file (setup-file)
        ctx  (gctx/build-context file)]
    (t/testing "severity override"
      (let [audit (engine/run-audit ctx :config {"token/color" {:severity :critical}})]
        (t/is (= {:critical 4} (get-in audit [:summary :by-severity])))))
    (t/testing "disabled rule"
      (let [audit (engine/run-audit ctx :config {"token/color" {:enabled? false}})]
        (t/is (= 0 (get-in audit [:summary :total])))))))

(t/deftest fix-replaces-literal-by-token
  (let [file    (setup-file)
        ctx     (gctx/build-context file)
        audit   (engine/run-audit ctx)
        page-id (thf/current-page-id file)
        changes (-> (pcb/empty-changes nil page-id)
                    (fix/generate-fix-changes ctx (:results audit)))
        file'   (thf/apply-changes file changes)
        audit'  (engine/run-audit (gctx/build-context file'))]

    (t/testing "only the unfixable violation remains"
      (t/is (= 1 (get-in audit' [:summary :total])))
      (t/is (= 1 (count (results-for audit' :rect-unknown)))))

    (t/testing "shapes now carry the token name and the resolved value"
      (let [rect (ths/get-shape file' :rect-literal)
            strk (ths/get-shape file' :rect-stroke)
            text (ths/get-shape file' :text1)]
        (t/is (= "color.action.primary" (get-in rect [:applied-tokens :fill])))
        (t/is (= "#6750a4" (get-in rect [:fills 0 :fill-color])))
        (t/is (= "color.action.primary" (get-in strk [:applied-tokens :stroke-color])))
        (t/is (= "#6750a4" (get-in strk [:strokes 0 :stroke-color])))
        (t/is (= "color.action.primary" (get-in text [:applied-tokens :fill])))))

    (t/testing "undo restores the original state"
      (let [file''  (thf/apply-undo-changes file' changes)
            audit'' (engine/run-audit (gctx/build-context file''))]
        (t/is (= 4 (get-in audit'' [:summary :total])))
        (t/is (nil? (get-in (ths/get-shape file'' :rect-literal) [:applied-tokens :fill])))))))

(t/deftest audit-areas-classify-rules
  (t/testing "rule categories map to audit areas"
    (t/is (= :design-system (areas/area-of "token")))
    (t/is (= :accessibility (areas/area-of "accessibility")))
    (t/is (= :layer-semantics (areas/area-of "naming")))
    (t/is (nil? (areas/area-of "unknown"))))

  (t/testing "an area is active only when it has rules"
    (t/is (true? (areas/active? :design-system)))
    (t/is (false? (areas/active? :accessibility)))
    (t/is (= ["token/color"] (mapv :id (areas/rules-of :design-system)))))

  (t/testing "a new rule activates its area without other changes"
    (let [rules [{:id "a11y/contrast" :category "accessibility"}]]
      (t/is (true? (areas/active? :accessibility rules))))))
