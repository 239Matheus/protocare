;; Proto Care — Governance Engine
;;
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns app.governance.context
  "Builds the analyzable context the rules read.

   The context is a plain map derived from a file (and optionally its
   libraries). Rules never touch raw file data; everything they need
   is precomputed here so the same engine runs on JVM and JS."
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.governance.shapes :as gsh]
   [cuerdas.core :as str]))

;; --- Colors

(def ^:private hex-re
  #"^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

(def ^:private rgb-re
  #"^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*[0-9.]+\s*)?\)$")

(defn- int->hex2
  [v]
  (let [s #?(:clj (Integer/toHexString (int v))
             :cljs (.toString (int v) 16))]
    (if (= 1 (count s)) (str "0" s) s)))

(defn normalize-color
  "Returns the color as lowercase `#rrggbb`, or nil when it is not a
   plain color. Alpha channels are dropped: opacity lives in its own
   attribute on shapes."
  [value]
  (when (string? value)
    (let [value (str/trim value)]
      (cond
        (re-matches hex-re value)
        (let [body (subs value 1)]
          (case (count body)
            3 (str "#" (str/lower (apply str (mapcat (fn [c] [c c]) body))))
            6 (str/lower value)
            8 (str/lower (str "#" (subs body 0 6)))))

        (re-matches rgb-re value)
        (let [[_ r g b] (re-matches rgb-re value)]
          (str "#" (int->hex2 (parse-long r)) (int->hex2 (parse-long g)) (int->hex2 (parse-long b))))

        :else nil))))

;; --- Tokens

(def ^:private ref-re
  #"^\{([^}]+)\}$")

(defn resolve-token-value
  "Follows `{reference}` chains inside `tokens` (name -> token) up to a
   fixed depth. Returns the final raw value or nil on broken/circular refs.
   Only full references are resolved; math expressions are left as is."
  [tokens token-name]
  (loop [name token-name
         depth 0]
    (when (< depth 16)
      (when-let [token (get tokens name)]
        (let [value (:value token)]
          (if-let [[_ ref] (and (string? value) (re-matches ref-re value))]
            (recur ref (inc depth))
            value))))))

(defn- tokens-lib-and-status
  "[tokens-lib tokens-status] of the file. Honors `:tokens-source` when
   the file takes tokens from a library."
  [file libraries]
  (let [fdata      (ctf/file-data file)
        source-id  (cfo/get-effective-tokens-source fdata)
        source     (if (= source-id (:id fdata))
                     fdata
                     (some-> (get libraries source-id) ctf/file-data))
        tokens-lib (some-> source cfo/get-tokens-lib)
        ;; Files without an activation status (legacy or tool-built) use
        ;; the themes marked as active in the lib, like Penpot's migration.
        status     (or (cfo/get-tokens-status fdata)
                       (some-> tokens-lib cfo/make-tokens-status-from-lib)
                       (ctos/make-tokens-status))]
    [tokens-lib status]))

(defn active-tokens
  "Returns an ordered map name -> token with the tokens active in the
   file. Honors `:tokens-source` when the file takes tokens from a library."
  [file libraries]
  (let [[tokens-lib status] (tokens-lib-and-status file libraries)]
    (if (some? tokens-lib)
      (cfo/get-tokens-in-active-sets status tokens-lib)
      (d/ordered-map))))

(defn active-themes
  "Sorted paths (\"Mode/Light\") of the token themes active in the file,
   so audits can be filtered by design system theme."
  [file libraries]
  (let [[tokens-lib status] (tokens-lib-and-status file libraries)]
    (if (some? tokens-lib)
      (->> (ctos/get-active-theme-ids status)
           (remove #(= % ctob/hidden-theme-id))
           (keep #(ctob/get-theme tokens-lib %))
           (map (fn [theme]
                  (let [group (:group theme)]
                    (if (str/blank? group)
                      (:name theme)
                      (str group "/" (:name theme))))))
           (sort)
           (vec))
      [])))

(defn color-index
  "Builds a map normalized-hex -> sorted vector of token names, for every
   active color token whose value resolves to a plain color."
  [tokens]
  (let [index (->> tokens
                   (filter (fn [[_ token]] (= :color (:type token))))
                   (reduce (fn [index [name _]]
                             (if-let [hex (normalize-color (resolve-token-value tokens name))]
                               (update index hex (fnil conj []) name)
                               index))
                           {}))]
    (d/update-vals index (comp vec sort))))

(defn resolve-color
  "Normalized hex of a color token, or nil."
  [ctx token-name]
  (normalize-color (resolve-token-value (:tokens ctx) token-name)))

(defn- resolve-part
  "Resolves a value that may be a full `{reference}`."
  [tokens value]
  (if-let [[_ ref] (and (string? value) (re-matches ref-re value))]
    (resolve-token-value tokens ref)
    value))

(defn number-index
  "Map number -> sorted token names, for active tokens of `types` whose
   value resolves to a plain number."
  [tokens types]
  (let [index (->> tokens
                   (filter (fn [[_ token]] (contains? types (:type token))))
                   (reduce (fn [index [name _]]
                             (if-let [n (gsh/->num (resolve-token-value tokens name))]
                               (update index n (fnil conj []) name)
                               index))
                           {}))]
    (d/update-vals index (comp vec sort))))

(defn typography-index
  "Map token name -> {:size :weight :line-height} for active typography
   tokens (composite values with references resolved)."
  [tokens]
  (->> tokens
       (filter (fn [[_ token]] (= :typography (:type token))))
       (keep (fn [[name _]]
               (let [value (resolve-token-value tokens name)]
                 (when (map? value)
                   (let [part #(resolve-part tokens (get value %))]
                     [name {:size (gsh/->num (part :font-size))
                            :weight (gsh/->weight (part :font-weight))
                            :line-height (gsh/->num (part :line-height))}])))))
       (into (sorted-map))))

(defn lookup-number
  "Token names whose value equals `n` in a number index."
  [index n]
  (when (some? n)
    (some (fn [[k names]] (when (gsh/same-num? k n) names)) index)))

;; --- Context

(def default-options
  {;; Library colors (styles) count as governed values.
   :allow-library-colors? true
   ;; Hidden layers are still audited.
   :skip-hidden? false
   ;; accessibility/text-size: smallest allowed font size (px).
   :min-font-size 12
   ;; accessibility/touch-target: WCAG 2.2 AA 2.5.8 minimum (px).
   :min-touch-target 24
   ;; layout/alignment: edges closer than this (px) but not equal.
   :alignment-tolerance 3
   ;; layout/spacing: largest gap (px) checked against the spacing scale.
   :max-rhythm-gap 48})

(defn- page->ctx-page
  [page]
  {:id (:id page)
   :name (:name page)
   :objects (:objects page)
   :flows (:flows page)
   :background (:background page)})

(defn- components-of
  "Map component-id -> {:name :path} for the file and its libraries."
  [file libraries]
  (into {}
        (comp (mapcat (fn [f] (vals (:components (ctf/file-data f)))))
              (map (fn [c] [(:id c) (select-keys c [:name :path])])))
        (cons file (vals libraries))))

(defn build-context
  "Builds the audit context for `file`.

   Options:
   - `:libraries` map library-id -> file, for shared tokens/components.
   - `:page-id`   restrict the audit to one page.
   - `:options`   rule options, merged over `default-options`."
  [file & {:keys [libraries page-id options]}]
  (let [fdata  (ctf/file-data file)
        pages  (cond->> (ctpl/pages-seq fdata)
                 (some? page-id) (filter #(= page-id (:id %))))
        tokens  (active-tokens file libraries)
        spacing (number-index tokens #{:spacing})]
    {:file-id (:id file)
     :file-name (:name file)
     :pages (mapv page->ctx-page pages)
     :components (components-of file libraries)
     :tokens tokens
     :color-index (color-index tokens)
     :spacing-index spacing
     :spacing-scale (into (sorted-set) (keys spacing))
     :radius-index (number-index tokens #{:border-radius})
     :typography-index (typography-index tokens)
     :themes (active-themes file libraries)
     :options (merge default-options options)}))

(defn page-objects
  [ctx page-id]
  (some (fn [page] (when (= page-id (:id page)) (:objects page)))
        (:pages ctx)))
