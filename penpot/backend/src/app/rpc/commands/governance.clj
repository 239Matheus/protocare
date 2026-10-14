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
   [app.governance.areas :as areas]
   [app.governance.context :as gctx]
   [app.governance.engine :as engine]
   [app.governance.rule :as rule]
   [app.loggers.audit :as-alias audit]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.management :as mgmt]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.permissions :as perms]
   [app.util.services :as sv]
   [clojure.java.io :as io]))

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

(def ^:private schema:get-governance-permissions
  [:map {:title "get-governance-permissions"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::get-governance-permissions
  "Governance role and flags of the current profile in a team, so the UI
   can show the design health dashboard and the policy settings only to
   who can use them. Any team member."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-governance-permissions
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id]}]
  (teams/check-read-permissions! conn profile-id team-id)
  (-> (get-governance-permissions conn profile-id team-id)
      (select-keys [:governance-role :can-manage-policy :can-view-health :is-admin :is-owner])
      (assoc :team-id team-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EVIDENCE EVENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def event-types
  #{"audit-run" "finding-resolved" "result-status"
    "policy-updated" "design-system-installed"})

(defn- area-name
  [category]
  (some-> category areas/area-of name))

(defn- seconds-between
  [from to]
  (when (and from to)
    (quot (- (inst-ms to) (inst-ms from)) 1000)))

(defn record-event!
  "Appends one governance evidence event (see docs/03-analytics-plan.md).
   Runs in the caller transaction, so the event exists only when the
   change it describes was committed."
  [conn {:keys [type team-id project-id file-id audit-id result-id
                profile-id rule-id area severity props]}]
  (assert (contains? event-types type) type)
  (db/insert! conn :governance-event
              {:id (uuid/next)
               :type type
               :team-id team-id
               :project-id project-id
               :file-id file-id
               :audit-id audit-id
               :result-id result-id
               :profile-id profile-id
               :rule-id rule-id
               :area area
               :severity (some-> severity name)
               :props (db/json (or props {}))}
              {::db/return-keys false}))

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
    (record-event! conn {:type "policy-updated"
                         :team-id team-id
                         :profile-id profile-id
                         :props {:rules (into (sorted-map)
                                              (map (fn [[id cfg]]
                                                     [id (-> (select-keys cfg [:enabled? :severity])
                                                             (d/update-when :severity name))]))
                                              rules)
                                 :options (into (sorted-map)
                                                (map (fn [[k v]] [(name k) v]))
                                                options)}})
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
          (merge (select-keys row [:id :audit-id :status :first-seen-at]))
          (assoc :node-id (:node-id row)
                 :page-id (:page-id row)
                 :area (or (:area row) (area-name (:category row))))))))

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

(defn- page-scoped
  "Keeps the previous results the new audit can tell about: a page audit
   says nothing about the other pages."
  [results page-id]
  (if page-id
    (filterv #(= page-id (:page-id %)) results)
    results))

(defn- persist-audit!
  [conn {:keys [file project-id team-id profile-id page-id audit previous]}]
  (let [now           (ct/now)
        prev-results  (if previous (get-audit-results conn (:id previous)) [])
        prev-status   (into {} (map (juxt result-key :status)) prev-results)
        prev-seen     (into {} (map (juxt result-key #(or (:first-seen-at %) (:created-at previous))))
                            prev-results)
        results       (:results audit)
        current-keys  (into #{} (map result-key) results)
        ;; A previous finding counts as resolved when it is no longer
        ;; detected, whether the user fixed it through the panel (status
        ;; "fixed") or by hand (still "open"). Ignored findings are not
        ;; resolutions, and a page audit only resolves findings of its page.
        resolved-res  (->> (page-scoped prev-results page-id)
                           (remove #(= "ignored" (:status %)))
                           (remove (comp current-keys result-key)))
        resolved      (count resolved-res)
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
                                 :area (area-name (:category result))
                                 :first-seen-at (get prev-seen (result-key result) now)
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

    ;; Evidence: one event for the run and one per finding that is no
    ;; longer detected (fixed through the panel or by hand), with how
    ;; long it stayed open.
    (record-event! conn {:type "audit-run"
                         :team-id team-id
                         :project-id project-id
                         :file-id (:id file)
                         :audit-id audit-id
                         :profile-id profile-id
                         :props {:engine-version engine/version
                                 :page-id (some-> page-id str)
                                 :total (:total summary 0)
                                 :fixable (:fixable summary 0)
                                 :resolved-since-previous resolved
                                 :token-adherence (:token-adherence summary)
                                 :by-area (:by-area summary {})
                                 :by-severity (update-keys (:by-severity summary {}) name)}})
    (doseq [prev resolved-res]
      (record-event! conn {:type "finding-resolved"
                           :team-id team-id
                           :project-id project-id
                           :file-id (:id file)
                           :audit-id audit-id
                           :result-id (:id prev)
                           :profile-id profile-id
                           :rule-id (:rule-id prev)
                           :area (or (:area prev) (area-name (:category prev)))
                           :severity (:severity prev)
                           :props {:node-id (str (:node-id prev))
                                   :previous-status (:status prev)
                                   :fix-available (boolean (:fix-available prev))
                                   :open-seconds (seconds-between
                                                  (or (:first-seen-at prev) (:created-at previous))
                                                  now)}}))

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
    (when (not= status (:status result))
      (record-event! conn {:type "result-status"
                           :team-id (:team-id audit)
                           :project-id (:project-id audit)
                           :file-id (:file-id audit)
                           :audit-id (:id audit)
                           :result-id id
                           :profile-id profile-id
                           :rule-id (:rule-id result)
                           :area (or (:area result) (area-name (:category result)))
                           :severity (:severity result)
                           :props {:from (:status result)
                                   :to status
                                   :fix-available (boolean (:fix-available result))
                                   :open-seconds (seconds-between
                                                  (or (:first-seen-at result) (:created-at audit))
                                                  (ct/now))}}))
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
          a.summary, f.name AS file_name, p.name AS project_name,
          p.is_default AS project_is_default
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
                                         :project-name (:project-name (first rows))
                                         :is-default (boolean (:project-is-default (first rows))))))))]
    (-> (health-from conn audits)
        (assoc :team-id team-id
               :projects projects
               :recent (recent-audits conn "team_id" team-id 30)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EVIDENCE (events and metrics, see docs/03-analytics-plan.md)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private default-period-days 90)

(defn- since-or-default
  [since]
  (or since (ct/minus (ct/now) {:days default-period-days})))

(defn- check-project-in-team!
  [conn project-id team-id]
  (when (and project-id (not= team-id (perms/get-team-id-for-project conn project-id)))
    (ex/raise :type :not-found :code :object-not-found :hint "not found")))

(def ^:private sql:events
  "SELECT id, created_at, type, team_id, project_id, file_id, audit_id,
          result_id, profile_id, rule_id, area, severity, props
     FROM governance_event
    WHERE team_id = ?
      AND created_at >= ?
      AND (?::uuid IS NULL OR project_id = ?)
      AND (?::uuid IS NULL OR file_id = ?)
      AND (?::text[] IS NULL OR type = ANY(?::text[]))
    ORDER BY created_at DESC
    LIMIT ?")

(def ^:private schema:get-governance-events
  [:map {:title "get-governance-events"}
   [:team-id {:optional true} ::sm/uuid]
   [:project-id {:optional true} ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]
   [:types {:optional true} [:vector [::sm/one-of {:format "string"} event-types]]]
   [:since {:optional true} ct/schema:inst]
   [:limit {:optional true} [::sm/int {:min 1 :max 500}]]])

(sv/defmethod ::get-governance-events
  "Evidence trail of governance (most recent first): audits run, findings
   resolved, status changes, policy updates and design system installs.
   For a file, anyone who can read it; for a team or project, team
   owners/admins, `design-ops` and `ds-admin` roles."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-governance-events
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id project-id file-id types since limit]
                        :or {limit 100}}]
  (let [team-id (cond
                  file-id
                  (do (files/check-read-permissions! conn profile-id file-id)
                      (:team-id (db/exec-one! conn ["SELECT p.team_id FROM file AS f
                                                      JOIN project AS p ON (p.id = f.project_id)
                                                     WHERE f.id = ?" file-id])))

                  team-id
                  (do (check-health-permissions! conn profile-id team-id)
                      (check-project-in-team! conn project-id team-id)
                      team-id)

                  :else
                  (ex/raise :type :validation
                            :code :missing-scope
                            :hint "team-id or file-id is required"))
        types   (when (seq types) (db/create-array conn "text" types))]
    (->> (db/exec! conn [sql:events team-id (since-or-default since)
                         project-id project-id file-id file-id types types limit])
         (mapv #(update % :props db/decode-json-pgobject)))))

(def ^:private sql:resolution-by-area
  "SELECT area, type, props->>'to' AS to_status, count(*) AS total,
          percentile_cont(0.5) WITHIN GROUP
            (ORDER BY (props->>'open-seconds')::numeric) AS median_open_seconds
     FROM governance_event
    WHERE team_id = ?
      AND created_at >= ?
      AND (?::uuid IS NULL OR project_id = ?)
      AND type IN ('finding-resolved', 'result-status')
    GROUP BY area, type, props->>'to'")

(def ^:private sql:weekly
  "SELECT date_trunc('week', created_at) AS week,
          count(*) FILTER (WHERE type = 'audit-run') AS audits,
          count(DISTINCT file_id) FILTER (WHERE type = 'audit-run') AS files,
          coalesce(sum((props->>'total')::int) FILTER (WHERE type = 'audit-run'), 0) AS findings,
          count(*) FILTER (WHERE type = 'finding-resolved') AS resolved
     FROM governance_event
    WHERE team_id = ?
      AND created_at >= ?
      AND (?::uuid IS NULL OR project_id = ?)
    GROUP BY 1
    ORDER BY 1")

(def ^:private sql:latest-audit-ids
  "SELECT DISTINCT ON (a.file_id) a.id
     FROM governance_audit AS a
     JOIN file AS f ON (f.id = a.file_id)
    WHERE a.team_id = ?
      AND (?::uuid IS NULL OR a.project_id = ?)
      AND f.deleted_at IS NULL
    ORDER BY a.file_id, a.created_at DESC")

(def ^:private sql:open-by-rule
  "SELECT rule_id, category, severity, count(*) AS total
     FROM governance_audit_result
    WHERE audit_id = ANY(?)
      AND status = 'open'
    GROUP BY rule_id, category, severity")

(def ^:private sql:resolution-by-rule
  "SELECT rule_id, area, type, props->>'to' AS to_status, count(*) AS total
     FROM governance_event
    WHERE team_id = ?
      AND created_at >= ?
      AND (?::uuid IS NULL OR project_id = ?)
      AND type IN ('finding-resolved', 'result-status')
      AND rule_id IS NOT NULL
    GROUP BY rule_id, area, type, props->>'to'")

(defn- ratio
  [n d]
  (when (pos? d) (double (/ n d))))

(defn- area-metrics
  [resolution open-by-area]
  (let [area-ids (into (sorted-set)
                       (concat (map (comp name :id) areas/areas)
                               (keep :area resolution)))]
    (into {}
          (map (fn [area]
                 (let [rows     (filter #(= area (:area %)) resolution)
                       resolved (some #(when (= "finding-resolved" (:type %)) %) rows)
                       status   (fn [to] (or (some #(when (and (= "result-status" (:type %))
                                                               (= to (:to-status %)))
                                                      (:total %))
                                                   rows)
                                             0))
                       n-res    (or (:total resolved) 0)
                       n-open   (get open-by-area area 0)]
                   [area {:open n-open
                          :resolved n-res
                          :fixed-in-panel (status "fixed")
                          :ignored (status "ignored")
                          :reopened (status "open")
                          :fix-rate (ratio n-res (+ n-res n-open))
                          :median-resolve-seconds (some-> (:median-open-seconds resolved) double)}])))
          area-ids)))

(defn- rule-metrics
  "{rule-id {:area :open :resolved :fixed-in-panel :ignored :reopened
   :ignore-rate}}: what each rule finds and what people do with it. A
   high ignore rate is the signal that a rule needs calibration."
  [open resolution]
  (let [open-by  (reduce (fn [acc {:keys [rule-id category total]}]
                           (-> acc
                               (update-in [rule-id :open] (fnil + 0) total)
                               (assoc-in [rule-id :area] (area-name category))))
                         {} open)
        count-of (fn [rule-id type to]
                   (reduce + 0 (keep #(when (and (= rule-id (:rule-id %))
                                                 (= type (:type %))
                                                 (or (nil? to) (= to (:to-status %))))
                                        (:total %))
                                     resolution)))
        rule-ids (into (sorted-set) (concat (keys open-by) (keep :rule-id resolution)))]
    (into {}
          (map (fn [rule-id]
                 (let [n-open    (get-in open-by [rule-id :open] 0)
                       n-res     (count-of rule-id "finding-resolved" nil)
                       n-ignored (count-of rule-id "result-status" "ignored")]
                   [rule-id {:area (or (get-in open-by [rule-id :area])
                                       (some #(when (= rule-id (:rule-id %)) (:area %)) resolution))
                             :open n-open
                             :resolved n-res
                             :fixed-in-panel (count-of rule-id "result-status" "fixed")
                             :ignored n-ignored
                             :reopened (count-of rule-id "result-status" "open")
                             :ignore-rate (ratio n-ignored (+ n-open n-res n-ignored))}])))
          rule-ids)))

(def ^:private schema:get-governance-metrics
  [:map {:title "get-governance-metrics"}
   [:team-id ::sm/uuid]
   [:project-id {:optional true} ::sm/uuid]
   [:since {:optional true} ct/schema:inst]])

(sv/defmethod ::get-governance-metrics
  "Evidence metrics of a team (or one of its projects) over a period
   (default 90 days): per area, open findings, findings resolved, fix
   rate and median time to resolve; per rule, the same plus ignore rate;
   open findings per severity; per week, audits, findings and
   resolutions. Aggregated only: no per-person numbers. Team
   owners/admins, `design-ops` and `ds-admin` roles."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:get-governance-metrics
   ::db/transaction true}
  [{:keys [::db/conn]} {:keys [::rpc/profile-id team-id project-id since]}]
  (check-health-permissions! conn profile-id team-id)
  (check-project-in-team! conn project-id team-id)
  (let [since      (since-or-default since)
        resolution (db/exec! conn [sql:resolution-by-area team-id since project-id project-id])
        audit-ids  (mapv :id (db/exec! conn [sql:latest-audit-ids team-id project-id project-id]))
        open       (if (seq audit-ids)
                     (db/exec! conn [sql:open-by-rule (db/create-array conn "uuid" audit-ids)])
                     [])
        by-rule    (rule-metrics open (db/exec! conn [sql:resolution-by-rule team-id since
                                                      project-id project-id]))
        open-area  (reduce (fn [acc {:keys [category total]}]
                             (update acc (or (area-name category) "other") (fnil + 0) total))
                           {} open)
        weekly     (db/exec! conn [sql:weekly team-id since project-id project-id])
        by-area    (area-metrics resolution open-area)
        n-open     (reduce + 0 (map :open (vals by-area)))
        n-res      (reduce + 0 (map :resolved (vals by-area)))]
    {:team-id team-id
     :project-id project-id
     :since since
     :files-audited (count audit-ids)
     :audits (reduce + 0 (map :audits weekly))
     :open n-open
     :resolved n-res
     :fix-rate (ratio n-res (+ n-res n-open))
     :by-area by-area
     :by-severity (reduce (fn [acc {:keys [severity total]}]
                            (update acc severity (fnil + 0) total))
                          {} open)
     :by-rule by-rule
     :weekly (mapv #(select-keys % [:week :audits :files :findings :resolved]) weekly)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BUNDLED DESIGN SYSTEM
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def design-system-name
  "Name of the file the bundled design system is imported as."
  "Proto Care DS")

(def ^:private design-system-resource
  "app/onboarding/protocare-ds.penpot")

(def ^:private sql:team-design-system
  "SELECT f.id, f.is_shared
     FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?
      AND f.name = ?
      AND f.deleted_at IS NULL
      AND p.deleted_at IS NULL
    ORDER BY f.is_shared DESC, f.created_at ASC
    LIMIT 1")

(def ^:private sql:team-default-project
  "SELECT id FROM project
    WHERE team_id = ?
      AND is_default IS TRUE
      AND deleted_at IS NULL
    LIMIT 1")

(defn- mark-shared!
  [pool file-id]
  (db/update! pool :file
              {:is-shared true :modified-at (ct/now)}
              {:id file-id}
              {::db/return-keys false}))

(def ^:private schema:install-design-system
  [:map {:title "install-design-system"}
   [:team-id ::sm/uuid]])

(sv/defmethod ::install-design-system
  "Install the bundled Proto Care DS in a team, in its default project,
   and publish it as a shared library. Idempotent: when the team already
   has it, it only makes sure it is shared. Requires team edition."
  {::doc/added "2.17"
   ::doc/module :governance
   ::sm/params schema:install-design-system}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id team-id] :as params}]
  (teams/check-edition-permissions! pool profile-id team-id)
  (if-let [existing (db/exec-one! pool [sql:team-design-system team-id design-system-name])]
    (do
      (when-not (:is-shared existing)
        (mark-shared! pool (:id existing)))
      {:file-id (:id existing) :created false})

    (let [project-id (:id (db/exec-one! pool [sql:team-default-project team-id]))
          _          (when-not project-id
                       (ex/raise :type :not-found
                                 :code :object-not-found
                                 :hint "team has no default project"))
          resource   (io/resource design-system-resource)
          _          (when-not resource
                       (ex/raise :type :internal
                                 :code :design-system-not-bundled
                                 :hint "the bundled design system file is missing"))
          result     (with-open [input (io/input-stream resource)]
                       (mgmt/clone-template cfg
                                            (assoc params
                                                   :project-id project-id
                                                   :profile-id profile-id)
                                            input))
          file-ids   (if (map? result) (:file-ids result) result)
          file-id    (first file-ids)]
      (mark-shared! pool file-id)
      (record-event! pool {:type "design-system-installed"
                           :team-id team-id
                           :project-id project-id
                           :file-id file-id
                           :profile-id profile-id
                           :props {:name design-system-name}})
      (rph/with-meta {:file-id file-id :created true}
        {::audit/props {:team-id team-id :file-id file-id}}))))
