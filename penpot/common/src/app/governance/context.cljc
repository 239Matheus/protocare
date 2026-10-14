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
   [app.common.types.tokens-status :as ctos]
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

(defn active-tokens
  "Returns an ordered map name -> token with the tokens active in the
   file. Honors `:tokens-source` when the file takes tokens from a library."
  [file libraries]
  (let [fdata      (ctf/file-data file)
        source-id  (cfo/get-effective-tokens-source fdata)
        source     (if (= source-id (:id fdata))
                     fdata
                     (some-> (get libraries source-id) ctf/file-data))
        tokens-lib (some-> source cfo/get-tokens-lib)
        status     (or (cfo/get-tokens-status fdata)
                       (ctos/make-tokens-status))]
    (if (some? tokens-lib)
      (cfo/get-tokens-in-active-sets status tokens-lib)
      (d/ordered-map))))

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

;; --- Context

(def default-options
  {;; Library colors (styles) count as governed values.
   :allow-library-colors? true
   ;; Hidden layers are still audited.
   :skip-hidden? false})

(defn- page->ctx-page
  [page]
  {:id (:id page)
   :name (:name page)
   :objects (:objects page)})

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
        tokens (active-tokens file libraries)]
    {:file-id (:id file)
     :file-name (:name file)
     :pages (mapv page->ctx-page pages)
     :tokens tokens
     :color-index (color-index tokens)
     :options (merge default-options options)}))

(defn page-objects
  [ctx page-id]
  (some (fn [page] (when (= page-id (:id page)) (:objects page)))
        (:pages ctx)))
