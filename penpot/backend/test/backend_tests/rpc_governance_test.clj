;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns backend-tests.rpc-governance-test
  (:require
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private purple "#6750A4")

(defn- seed-file!
  "Creates a file with one color token set active and two rects: one
   with a literal fill matching the token and one tokenized."
  [profile]
  (let [file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        page-id  (uuid/next)
        set-id   (uuid/next)
        theme-id (uuid/next)
        literal  (uuid/next)
        tokenized (uuid/next)
        lib      (-> (ctob/make-tokens-lib)
                     (ctob/add-set (ctob/make-token-set :id set-id :name "core"))
                     (ctob/add-theme (ctob/make-token-theme :id theme-id
                                                            :name "light"
                                                            :sets #{"core"}))
                     (ctob/add-token set-id (ctob/make-token :name "color.action.primary"
                                                             :type :color
                                                             :value "#6750a4")))]
    (th/update-file! :profile-id (:id profile)
                     :file-id (:id file)
                     :changes
                     [{:type :add-page :id page-id :name "audited"}
                      {:type :set-tokens-lib :tokens-lib lib}
                      {:type :set-tokens-status :theme-ids #{theme-id} :set-ids #{set-id}}
                      {:type :add-obj
                       :page-id page-id
                       :id literal
                       :parent-id uuid/zero
                       :frame-id uuid/zero
                       :obj (cts/setup-shape {:id literal
                                              :name "literal"
                                              :type :rect
                                              :frame-id uuid/zero
                                              :parent-id uuid/zero
                                              :fills [{:fill-color purple :fill-opacity 1}]})}
                      {:type :add-obj
                       :page-id page-id
                       :id tokenized
                       :parent-id uuid/zero
                       :frame-id uuid/zero
                       :obj (cts/setup-shape {:id tokenized
                                              :name "tokenized"
                                              :type :rect
                                              :frame-id uuid/zero
                                              :parent-id uuid/zero
                                              :fills [{:fill-color "#6750a4" :fill-opacity 1}]
                                              :applied-tokens {:fill "color.action.primary"}})}])
    {:file file :page-id page-id :literal literal :tokenized tokenized}))

(t/deftest run-audit-and-read-back
  (let [profile (th/create-profile* 1 {:is-active true})
        {:keys [file literal]} (seed-file! profile)]

    (t/testing "run audit finds the literal fill"
      (let [out (th/command! {::th/type :run-audit
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)})]
        (t/is (nil? (:error out)))
        (let [result (:result out)
              [r :as rs] (:results result)]
          (t/is (uuid? (:id result)))
          (t/is (= 1 (get-in result [:summary :total])))
          (t/is (= 1 (get-in result [:summary :fixable])))
          (t/is (= 0 (get-in result [:summary :resolved-since-previous])))
          (t/is (= 1 (count rs)))
          (t/is (= literal (:node-id r)))
          (t/is (= "token/color" (:rule-id r)))
          (t/is (= "open" (:status r)))
          (t/is (= "color.action.primary" (:expected-value r)))

          (t/testing "get-audit returns the same audit"
            (let [out (th/command! {::th/type :get-audit
                                    ::rpc/profile-id (:id profile)
                                    :id (:id result)})]
              (t/is (nil? (:error out)))
              (t/is (= (:id result) (get-in out [:result :id])))
              (t/is (= 1 (count (get-in out [:result :results]))))))

          (t/testing "get-file-audits lists it"
            (let [out (th/command! {::th/type :get-file-audits
                                    ::rpc/profile-id (:id profile)
                                    :file-id (:id file)})]
              (t/is (nil? (:error out)))
              (t/is (= [(:id result)] (mapv :id (:result out))))))

          (t/testing "ignored status is carried over to the next audit"
            (let [out (th/command! {::th/type :update-audit-result-status
                                    ::rpc/profile-id (:id profile)
                                    :id (:id r)
                                    :status "ignored"})]
              (t/is (nil? (:error out)))
              (t/is (= "ignored" (get-in out [:result :status]))))
            (let [out (th/command! {::th/type :run-audit
                                    ::rpc/profile-id (:id profile)
                                    :file-id (:id file)})]
              (t/is (nil? (:error out)))
              (t/is (= ["ignored"] (mapv :status (get-in out [:result :results]))))
              (t/is (= (:id result) (get-in out [:result :summary :previous-audit-id]))))))))))

(t/deftest policy-disables-rule
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id profile)
        {:keys [file]} (seed-file! profile)]

    (t/testing "default policy exposes available rules"
      (let [out (th/command! {::th/type :get-governance-policy
                              ::rpc/profile-id (:id profile)
                              :team-id team-id})]
        (t/is (nil? (:error out)))
        (t/is (= {} (get-in out [:result :rules])))
        (t/is (= ["token/color"] (mapv :id (get-in out [:result :available-rules]))))))

    (t/testing "owner can update the policy"
      (let [out (th/command! {::th/type :update-governance-policy
                              ::rpc/profile-id (:id profile)
                              :team-id team-id
                              :rules {"token/color" {:enabled? false}}})]
        (t/is (nil? (:error out)))
        (t/is (false? (get-in out [:result :rules "token/color" :enabled?])))))

    (t/testing "unknown rule is rejected"
      (let [out (th/command! {::th/type :update-governance-policy
                              ::rpc/profile-id (:id profile)
                              :team-id team-id
                              :rules {"token/nope" {:enabled? true}}})]
        (t/is (th/ex-of-code? (:error out) :unknown-governance-rule))))

    (t/testing "audit honors the policy"
      (let [out (th/command! {::th/type :run-audit
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)})]
        (t/is (nil? (:error out)))
        (t/is (= 0 (get-in out [:result :summary :total])))))))

(t/deftest roles-and-health-permissions
  (let [owner   (th/create-profile* 1 {:is-active true})
        member  (th/create-profile* 2 {:is-active true})
        team-id (:default-team-id owner)
        proj-id (:default-project-id owner)
        _       (th/create-team-role* {:team-id team-id
                                       :profile-id (:id member)
                                       :role :editor})
        {:keys [file]} (seed-file! owner)]

    (th/command! {::th/type :run-audit
                  ::rpc/profile-id (:id owner)
                  :file-id (:id file)})

    (t/testing "a plain editor cannot read project health"
      (let [out (th/command! {::th/type :get-project-health
                              ::rpc/profile-id (:id member)
                              :project-id proj-id})]
        (t/is (th/ex-of-type? (:error out) :not-found))))

    (t/testing "a plain editor cannot change the policy"
      (let [out (th/command! {::th/type :update-governance-policy
                              ::rpc/profile-id (:id member)
                              :team-id team-id
                              :rules {}})]
        (t/is (th/ex-of-type? (:error out) :not-found))))

    (t/testing "owner assigns design-ops role"
      (let [out (th/command! {::th/type :set-governance-role
                              ::rpc/profile-id (:id owner)
                              :team-id team-id
                              :profile-id (:id member)
                              :role "design-ops"})]
        (t/is (nil? (:error out)))
        (t/is (= "design-ops" (get-in out [:result :role]))))
      (let [out (th/command! {::th/type :get-governance-roles
                              ::rpc/profile-id (:id owner)
                              :team-id team-id})]
        (t/is (nil? (:error out)))
        (t/is (= {(:id owner) "designer" (:id member) "design-ops"}
                 (into {} (map (juxt :profile-id :role)) (:result out))))))

    (t/testing "design-ops reads project and team health"
      (let [out (th/command! {::th/type :get-project-health
                              ::rpc/profile-id (:id member)
                              :project-id proj-id})]
        (t/is (nil? (:error out)))
        (t/is (= 1 (get-in out [:result :files-audited])))
        (t/is (= 1 (get-in out [:result :open-violations])))
        (t/is (= 1 (get-in out [:result :fixable-violations])))
        (t/is (= {"medium" 1} (get-in out [:result :by-severity])))
        (t/is (= 1 (count (get-in out [:result :recent])))))
      (let [out (th/command! {::th/type :get-team-health
                              ::rpc/profile-id (:id member)
                              :team-id team-id})]
        (t/is (nil? (:error out)))
        (t/is (= 1 (get-in out [:result :files-audited])))
        (t/is (= [proj-id] (mapv :project-id (get-in out [:result :projects]))))))

    (t/testing "design-ops still cannot change the policy"
      (let [out (th/command! {::th/type :update-governance-policy
                              ::rpc/profile-id (:id member)
                              :team-id team-id
                              :rules {}})]
        (t/is (th/ex-of-type? (:error out) :not-found))))

    (t/testing "ds-admin can change the policy"
      (th/command! {::th/type :set-governance-role
                    ::rpc/profile-id (:id owner)
                    :team-id team-id
                    :profile-id (:id member)
                    :role "ds-admin"})
      (let [out (th/command! {::th/type :update-governance-policy
                              ::rpc/profile-id (:id member)
                              :team-id team-id
                              :rules {"token/color" {:severity :critical}}})]
        (t/is (nil? (:error out)))
        (t/is (= :critical (get-in out [:result :rules "token/color" :severity])))))))

(t/deftest install-design-system-is-idempotent
  (let [owner   (th/create-profile* 1 {:is-active true})
        member  (th/create-profile* 2 {:is-active true})
        team-id (:default-team-id owner)
        _       (th/create-team-role* {:team-id team-id
                                       :profile-id (:id member)
                                       :role :viewer})
        out1    (th/command! {::th/type :install-design-system
                              ::rpc/profile-id (:id owner)
                              :team-id team-id})
        file-id (get-in out1 [:result :file-id])]

    (t/testing "first call imports the DS as a shared library"
      (t/is (nil? (:error out1)))
      (t/is (true? (get-in out1 [:result :created])))
      (let [file (th/db-get :file {:id file-id})]
        (t/is (= "Proto Care DS" (:name file)))
        (t/is (true? (:is-shared file)))))

    (t/testing "second call reuses it"
      (let [out2 (th/command! {::th/type :install-design-system
                               ::rpc/profile-id (:id owner)
                               :team-id team-id})]
        (t/is (nil? (:error out2)))
        (t/is (false? (get-in out2 [:result :created])))
        (t/is (= file-id (get-in out2 [:result :file-id])))))

    (t/testing "the imported file audits to the five intentional findings"
      (let [out (th/command! {::th/type :run-audit
                              ::rpc/profile-id (:id owner)
                              :file-id file-id})]
        (t/is (nil? (:error out)))
        (t/is (= 5 (get-in out [:result :summary :total])))
        (t/is (= 4 (get-in out [:result :summary :fixable])))))

    (t/testing "a viewer cannot install it"
      (let [out (th/command! {::th/type :install-design-system
                              ::rpc/profile-id (:id member)
                              :team-id team-id})]
        (t/is (th/ex-of-type? (:error out) :not-found))))))
