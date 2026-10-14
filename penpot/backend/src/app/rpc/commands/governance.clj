;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.rpc.commands.governance
  "RPC surface of the Governance Engine: team policy, product roles,
   audits over files and aggregated health for projects and teams.

   Permission model (fail closed, `:not-found` like the rest of the API):
   - run/read audits on a file: file read permission (run needs edit);
   - policy: team owner/admin or `ds-admin` governance role;
   - aggregated health: team owner/admin, `design-ops` or `ds-admin`."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.governance.context :as gctx]
   [app.governance.engine :as engine]
   [app.governance.rule :as rule]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROLES AND PERMISSIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-roles
  #{"designer" "design-ops" "ds-admin"})

(defn get-governance-role
  "Product role of `profile-id` in `team-id`, or \"designer\" when none
   is set and the profile is a team member."
  [conn profile-id team-id]
  (or (some-> (db/get* conn :governance-role {:team-id team-id :profile-id profile-id})
              (:role))
      "designer"))

(defn get-governance-permissions
  "Team permissions extended with the governance role flags."
  [conn profile-id team-id]
  (when-let [tperms (teams/get-permissions conn profile-id team-id)]
    (let [role (get-governance-role conn profile-id team-id)]
      (assoc tperms
             :governance-role role
             :can-manage-policy (boolean (or (:is-admin tperms) (= role "ds-admin")))
             :can-view-health (boolean (or (:is-admin tperms)
                                           (contains? #{"design-ops" "ds-admin"} role)))))))

(def ^:private can-manage-policy?
  (fn check
    ([perms] (:can-manage-policy perms))
    ([conn & args] (check (apply get-governance-permissions conn args)))))

(def ^:private can-view-health?
  (fn check
    ([perms] (:can-view-health perms))
    ([conn & args] (check (apply get-governance-permissions conn args)))))

(def check-policy-permissions!
  (perms/make-check-fn can-manage-policy?))

(def check-health-permissions!
  (perms/make-check-fn can-view-health?))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POLICY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:rule-config
  [:map {:title "GovernanceRuleConfig"}
   [:enabled? {:optional true} :boolean]
   [:severity {:optional true} rule/schema:severity]
   [:options {:optional true} [:map-of :keyword :any]]])

(def schema:policy-rules
  [:map-of :string schema:rule-config])

(def schema:policy-options
  [:map-of :keyword :any])

(def schema:policy
  [:map {:title "GovernancePolicy"}
   [:team-id ::sm/uuid]
   [:rules schema:policy-rules]
   [:options schema:policy-options]
   [:available-rules [:vector [:map
                               [:id :string]
                               [:category :string]
                               [:title :string]
                               [:description :string]
                               [:severity rule/schema:severity]]]]])

(defn- default-policy
  [team-id]
  {:team-id team-id
   :rules {}
   :options {}})

(defn- decode-policy-row
  [{:keys [rules options] :as row}]
  (when row
    (-> row
        (assoc :rules (or (some-> rules db/decode-transit-pgobject) {}))
        (assoc :options (or (some-> options db/decode-transit-pgobject) {})))))

(defn- available-rules
  []
  (mapv #(select-keys % [:id :category :title :description :severity])
        engine/default-rules))

(defn get-policy
  [conn team-id]
  (-> (or (decode-policy-row (db/get* conn :governance-policy {:team-id team-id}))
          (default-policy team-id))
      (select-keys [:team-id :rules :options :modified-at])
      (assoc :available-rules (available-rules))))

(def ^:private schema:get-governance-policy
  [:map {:title "get-governance-policy"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-governance-policy
  "Return the governance policy of a team (rules, severities, options).
   Any team member can read it."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-governance-policy
   ::sm/result schema:policy
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id]}]
  (teams/check-read-permissions! conn profile-id team-id)
  (get-policy conn team-id))

(def ^:private schema:update-governance-policy
  [:map {:title "update-governance-policy"}
   [:team-id ::sm/uuid]
   [:rules {:optional true} schema:policy-rules]
   [:options {:optional true} schema:policy-options]])

(defn- validate-rules!
  [rules]
  (let [known (into #{} (map :id) engine/default-rules)
        unknown (remove known (keys rules))]
    (when (seq unknown)
      (ex/raise :type :validation
                :code :unknown-governance-rule
                :hint (str "unknown rules: " (pr-str (vec unknown)))
                :rules (vec unknown)))))

(sv/defmethod ::update-governance-policy
  "Create or replace the governance policy of a team. Team owners and
   admins, or members with the `ds-admin` role."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:update-governance-policy
   ::sm/result schema:policy
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id rules options]
                        :or {rules {} options {}}}]
  (check-policy-permissions! conn profile-id team-id)
  (validate-rules! rules)
  (let [row {:team-id team-id
             :rules (db/tjson rules)
             :options (db/tjson options)}]
    (if (db/get* conn :governance-policy {:team-id team-id})
      (db/update! conn :governance-policy
                  (-> row (dissoc :team-id) (assoc :modified-at (ct/now)))
                  {:team-id team-id})
      (db/insert! conn :governance-policy row))
    (rph/with-meta (get-policy conn team-id)
      {::audit/props {:team-id team-id
                      :rules (vec (keys rules))}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROLES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:team-governance-roles
  "SELECT tpr.profile_id,
          tpr.is_owner,
          tpr.is_admin,
          tpr.can_edit,
          COALESCE(gr.role, 'designer') AS role
     FROM team_profile_rel AS tpr
     LEFT JOIN governance_role AS gr
            ON (gr.team_id = tpr.team_id AND gr.profile_id = tpr.profile_id)
    WHERE tpr.team_id = ?")

(def ^:private schema:get-governance-roles
  [:map {:title "get-governance-roles"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-governance-roles
  "List the governance role of every team member."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-governance-roles
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id]}]
  (teams/check-read-permissions! conn profile-id team-id)
  (db/exec! conn [sql:team-governance-roles team-id]))

(def ^:private schema:set-governance-role
  [:map {:title "set-governance-role"}
   [:team-id ::sm/uuid]
   [:profile-id ::sm/uuid]
   [:role [::sm/one-of {:format "string"} valid-roles]]])

(sv/defmethod ::set-governance-role
  "Assign a governance role to a team member. Team owners and admins only.
   Setting `designer` removes any explicit role."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:set-governance-role
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id role] :as params}]
  (teams/check-admin-permissions! conn profile-id team-id)
  (let [member-id (:profile-id params)]
    (when-not (teams/get-permissions conn member-id team-id)
      (ex/raise :type :not-found
                :code :object-not-found
                :hint "profile is not a team member"))
    (db/delete! conn :governance-role {:team-id team-id :profile-id member-id})
    (when (not= role "designer")
      (db/insert! conn :governance-role {:team-id team-id
                                         :profile-id member-id
                                         :role role}))
    (rph/with-meta {:team-id team-id :profile-id member-id :role role}
      {::audit/props {:team-id team-id :member-id member-id :role role}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; AUDITS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-result-status
  #{"open" "fixed" "ignored"})

(defn- decode-audit-row
  [{:keys [summary stats] :as row}]
  (when row
    (-> row
        (d/update-when :summary db/decode-transit-pgobject)
        (d/update-when :stats db/decode-transit-pgobject)
        (cond-> (nil? summary) (assoc :summary {})
                (nil? stats) (assoc :stats {})))))

(defn- decode-result-row
  [{:keys [payload] :as row}]
  (when row
    (let [payload (some-> payload db/decode-transit-pgobject)]
      (-> payload
          (merge (select-keys row [:id :audit-id :status]))
          (assoc :node-id (:node-id row)
                 :page-id (:page-id row))))))

(defn- result-key
  [result]
  [(:rule-id result) (:node-id result) (:attribute result)])

(def ^:private sql:file-audits
  "SELECT * FROM governance_audit
    WHERE file_id = ?
    ORDER BY created_at DESC
    LIMIT ?")

(defn- get-latest-audit
  [conn file-id]
  (->> (db/exec! conn [sql:file-audits file-id 1])
       (first)
       (decode-audit-row)))

(defn- get-audit-results
  [conn audit-id]
  (->> (db/query conn :governance-audit-result {:audit-id audit-id})
       (mapv decode-result-row)))

(defn- load-libraries
  "Loads the libraries a file depends on, as id -> file, for the
   audit context (shared tokens source)."
  [cfg file-id]
  (let [ids (bfc/get-libraries cfg [file-id])]
    (into {} (map (fn [id] [id (bfc/get-file cfg id :realize? true)])) ids)))

(defn- persist-audit!
  [conn {:keys [file project-id team-id profile-id page-id audit previous]}]
  (let [prev-results  (if previous (get-audit-results conn (:id previous)) [])
        prev-status   (into {} (map (juxt result-key :status)) prev-results)
        ;; A previous finding counts as resolved when it is no longer
        ;; detected, whether the user fixed it through the panel (status
        ;; "fixed") or by hand (still "open"). Ignored findings are not
        ;; resolutions.
        prev-active   (into #{} (comp (remove #(= "ignored" (:status %))) (map result-key)) prev-results)
        results       (:results audit)
        current-keys  (into #{} (map result-key) results)
        resolved      (count (remove current-keys prev-active))
        summary       (assoc (:summary audit)
                             :resolved-since-previous resolved
                             :previous-audit-id (:id previous))
        row           (db/insert! conn :governance-audit
                                  {:id (uuid/next)
                                   :file-id (:id file)
                                   :project-id project-id
                                   :team-id team-id
                                   :profile-id profile-id
                                   :file-revn (:revn file 0)
                                   :file-vern (:vern file 0)
                                   :page-id page-id
                                   :engine-version engine/version
                                   :summary (db/tjson summary)
                                   :stats (db/tjson (:stats audit))})
        audit-id      (:id row)
        rows          (mapv (fn [result]
                              (let [status (get prev-status (result-key result) "open")
                                    status (if (= status "ignored") "ignored" "open")]
                                {:id (uuid/next)
                                 :audit-id audit-id
                                 :rule-id (:rule-id result)
                                 :category (:category result)
                                 :severity (name (:severity result))
                                 :page-id (:page-id result)
                                 :node-id (:node-id result)
                                 :node-name (:node-name result)
                                 :attribute (some-> (:attribute result) name)
                                 :fix-available (boolean (:fix-available result))
                                 :status status
                                 :payload (db/tjson result)}))
                            results)]
    (run! #(db/insert! conn :governance-audit-result %) rows)
    (-> (decode-audit-row row)
        (assoc :results (get-audit-results conn audit-id)))))

(def ^:private schema:run-audit
  [:map {:title "run-audit"}
   [:file-id ::sm/uuid]
   [:page-id {:optional true} ::sm/uuid]])

(sv/defmethod ::run-audit
  "Run the Governance Engine over a file (or one page of it) with the
   team policy, persist the audit and return it with its results.
   Requires edit permission on the file."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:run-audit
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id file-id page-id]}]
  (files/check-edition-permissions! conn profile-id file-id)
  (let [file      (bfc/get-file cfg file-id :realize? true)
        project   (bfc/get-project cfg (:project-id file))
        team-id   (:team-id project)
        policy    (get-policy conn team-id)
        libraries (load-libraries cfg file-id)
        ctx       (gctx/build-context file
                                      :libraries libraries
                                      :page-id page-id
                                      :options (:options policy))
        audit     (engine/run-audit ctx :config (:rules policy))
        previous  (get-latest-audit conn file-id)
        result    (persist-audit! conn {:file file
                                        :project-id (:id project)
                                        :team-id team-id
                                        :profile-id profile-id
                                        :page-id page-id
                                        :audit audit
                                        :previous previous})]
    (rph/with-meta result
      {::audit/props {:file-id file-id
                      :team-id team-id
                      :total (get-in result [:summary :total])
                      :fixable (get-in result [:summary :fixable])}})))

(def ^:private schema:get-audit
  [:map {:title "get-audit"}
   [:id ::sm/uuid]])

(sv/defmethod ::get-audit
  "Return one audit with its results. Requires read permission on the file."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-audit
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id]}]
  (let [audit (decode-audit-row (db/get conn :governance-audit {:id id}))]
    (files/check-read-permissions! conn profile-id (:file-id audit))
    (assoc audit :results (get-audit-results conn id))))

(def ^:private schema:get-file-audits
  [:map {:title "get-file-audits"}
   [:file-id ::sm/uuid]
   [:limit {:optional true} [::sm/int {:min 1 :max 100}]]])

(sv/defmethod ::get-file-audits
  "Return the audit history of a file (most recent first), without results."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-file-audits
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id file-id limit] :or {limit 20}}]
  (files/check-read-permissions! conn profile-id file-id)
  (->> (db/exec! conn [sql:file-audits file-id limit])
       (mapv decode-audit-row)))

(def ^:private schema:update-audit-result-status
  [:map {:title "update-audit-result-status"}
   [:id ::sm/uuid]
   [:status [::sm/one-of {:format "string"} valid-result-status]]])

(sv/defmethod ::update-audit-result-status
  "Mark a result as fixed, ignored or open again. Requires edit
   permission on the audited file."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:update-audit-result-status
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id id status]}]
  (let [result (db/get conn :governance-audit-result {:id id})
        audit  (db/get conn :governance-audit {:id (:audit-id result)})]
    (files/check-edition-permissions! conn profile-id (:file-id audit))
    (db/update! conn :governance-audit-result {:status status} {:id id})
    (decode-result-row (db/get conn :governance-audit-result {:id id}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HEALTH (aggregated, no per-person ranking)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:latest-audits-by-project
  "SELECT DISTINCT ON (a.file_id)
          a.id, a.file_id, a.project_id, a.team_id, a.created_at,
          a.summary, f.name AS file_name
     FROM governance_audit AS a
     JOIN file AS f ON (f.id = a.file_id)
    WHERE a.project_id = ?
      AND f.deleted_at IS NULL
    ORDER BY a.file_id, a.created_at DESC")

(def ^:private sql:latest-audits-by-team
  "SELECT DISTINCT ON (a.file_id)
          a.id, a.file_id, a.project_id, a.team_id, a.created_at,
          a.summary, f.name AS file_name, p.name AS project_name
     FROM governance_audit AS a
     JOIN file AS f ON (f.id = a.file_id)
     JOIN project AS p ON (p.id = a.project_id)
    WHERE a.team_id = ?
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL
    ORDER BY a.file_id, a.created_at DESC")

(def ^:private sql:result-counts
  "SELECT audit_id, severity, category, status, fix_available, count(*) AS total
     FROM governance_audit_result
    WHERE audit_id = ANY(?)
    GROUP BY audit_id, severity, category, status, fix_available")

(def ^:private sql:recent-audits
  "SELECT a.id, a.file_id, a.project_id, a.created_at, a.summary, f.name AS file_name
     FROM governance_audit AS a
     JOIN file AS f ON (f.id = a.file_id)
    WHERE a.%s = ?
      AND f.deleted_at IS NULL
    ORDER BY a.created_at DESC
    LIMIT ?")

(defn- count-by
  [rows k]
  (reduce (fn [acc row] (update acc (get row k) (fnil + 0) (:total row))) {} rows))

(defn- health-from
  "Aggregates the latest audit of each file into one health map."
  [conn audits]
  (let [audits    (mapv decode-audit-row audits)
        audit-ids (mapv :id audits)
        counts    (if (seq audit-ids)
                    (db/exec! conn [sql:result-counts (db/create-array conn "uuid" audit-ids)])
                    [])
        open      (filter #(= "open" (:status %)) counts)
        adherence (->> audits (keep #(get-in % [:summary :token-adherence])) (seq))]
    {:files-audited (count audits)
     :open-violations (reduce + 0 (map :total open))
     :critical-violations (get (count-by open :severity) "critical" 0)
     :fixable-violations (reduce + 0 (map :total (filter :fix-available open)))
     :by-severity (count-by open :severity)
     :by-category (count-by open :category)
     :by-status (count-by counts :status)
     :token-adherence (when adherence (/ (reduce + adherence) (count adherence)))
     :resolved-since-previous (reduce + 0 (keep #(get-in % [:summary :resolved-since-previous]) audits))
     :last-audit-at (some->> audits (map :created-at) (sort) (last))
     :files (mapv #(select-keys % [:id :file-id :file-name :project-id :project-name :created-at :summary])
                  audits)}))

(defn- recent-audits
  [conn column id limit]
  (->> (db/exec! conn [(format sql:recent-audits column) id limit])
       (mapv decode-audit-row)
       (mapv #(select-keys % [:id :file-id :file-name :project-id :created-at :summary]))))

(def ^:private schema:get-project-health
  [:map {:title "get-project-health"}
   [:project-id ::sm/uuid]])

(sv/defmethod ::get-project-health
  "Aggregated design health of a project, from the latest audit of each
   file. Team owners/admins, `design-ops` and `ds-admin` roles."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-project-health
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id project-id]}]
  (let [team-id (perms/get-team-id-for-project conn project-id)]
    (when (nil? team-id)
      (ex/raise :type :not-found :code :object-not-found :hint "not found"))
    (check-health-permissions! conn profile-id team-id)
    (-> (health-from conn (db/exec! conn [sql:latest-audits-by-project project-id]))
        (assoc :project-id project-id
               :team-id team-id
               :recent (recent-audits conn "project_id" project-id 30)))))

(def ^:private schema:get-team-health
  [:map {:title "get-team-health"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-team-health
  "Aggregated design health of a team, overall and per project."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-team-health
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id]}]
  (check-health-permissions! conn profile-id team-id)
  (let [audits   (db/exec! conn [sql:latest-audits-by-team team-id])
        projects (->> (group-by :project-id audits)
                      (mapv (fn [[project-id rows]]
                              (-> (health-from conn rows)
                                  (dissoc :files)
                                  (assoc :project-id project-id
                                         :project-name (:project-name (first rows)))))))]
    (-> (health-from conn audits)
        (assoc :team-id team-id
               :projects projects
               :recent (recent-audits conn "team_id" team-id 30)))))
