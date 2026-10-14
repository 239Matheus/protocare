;; Proto Care — fills a file with the sign up / login / password recovery
;; flow built with the Proto Care DS (library component copies and DS
;; tokens), to exercise the governance features on a realistic file.
;;
;; Run inside the devenv backend REPL (nREPL on port 6064):
;;
;;   (load-file "/home/penpot/penpot/protocare/scripts/telas_de_cadastro.clj")
;;   (protocare.telas-de-cadastro/run! {:file-id #uuid "..." :library-id #uuid "..."
;;                                      :profile-id #uuid "..."})
;;
;; What it does, in one transaction:
;;   1. publishes the DS file as a shared library and links it to the file;
;;   2. takes the tokens from the DS (tokens source + its theme status);
;;   3. renames the first page to "Fluxo de acesso" and draws five mobile
;;      screens with DS component copies and tokenized texts;
;;   4. wires the prototype (navigate interactions) and three flows.
;;
;; The target page must be empty: the script refuses to draw over work.

(ns protocare.telas-de-cadastro
  (:refer-clojure :exclude [run!])
  (:require
   [app.binfile.common :as bfc]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.libraries :as cll]
   [app.common.logic.tokens :as clt]
   [app.common.types.shape :as cts]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.governance.context :as gctx]
   [app.srepl.helpers :as h]
   [app.system :as sys]))

;; --- Tokens of the DS

(defn- ds-context
  [library]
  (gctx/build-context library))

(defn- color
  [ctx token]
  (or (gctx/resolve-color ctx token)
      (throw (ex-info "color token not found" {:token token}))))

(defn- typo
  [ctx token]
  (or (get-in ctx [:typography-index token])
      (throw (ex-info "typography token not found" {:token token}))))

;; --- Shapes

(def W 390)
(def H 844)
(def X 24)
(def INNER (- W (* 2 X)))
(def GAP 80)
(def CONTROL 40)

(defn- text-style
  [hex {:keys [size weight line-height]} align]
  {:fills [{:fill-color hex :fill-opacity 1}]
   :font-id "sourcesanspro"
   :font-family "sourcesanspro"
   :font-variant-id (case (str weight) "700" "bold" "600" "600" "regular")
   :font-size (str size)
   :font-weight (str weight)
   :font-style "normal"
   :line-height (str line-height)
   :letter-spacing "0"
   :text-transform "none"
   :text-align (name align)
   :text-decoration "none"
   :text-direction "ltr"})

(defn- content
  [text style v-align]
  {:type "root"
   :vertical-align (name v-align)
   :children [{:type "paragraph-set"
               :children [(merge {:type "paragraph"} style
                                 {:children [(merge {:text text} style)]})]}]})

(defn- line-height-px
  [{:keys [size line-height]}]
  (long (Math/ceil (* (double size) (Double/parseDouble (str line-height))))))

(defn- text-shape
  [ctx board {:keys [name text x y width color-token typo-token align]
              :or {align :left width INNER}}]
  (let [t (typo ctx typo-token)]
    (cts/setup-shape
     {:id (uuid/next)
      :type :text
      :name name
      :x (+ (:x board) x)
      :y (+ (:y board) y)
      :width width
      :height (line-height-px t)
      :grow-type :fixed
      :parent-id (:id board)
      :frame-id (:id board)
      :content (content text (text-style (color ctx color-token) t align) :top)
      :applied-tokens {:fill color-token :typography typo-token}})))

(defn- board-shape
  [ctx {:keys [id name index]}]
  (cts/setup-shape
   {:id id
    :type :frame
    :name name
    :x (* index (+ W GAP))
    :y 0
    :width W
    :height H
    :fills [{:fill-color (color ctx "color.bg.canvas") :fill-opacity 1}]
    :strokes []
    :parent-id uuid/zero
    :frame-id uuid/zero
    :applied-tokens {:fill "color.bg.canvas"}}))

(defn- navigate
  [destination]
  [(assoc ctsi/default-interaction :destination destination)])

;; --- File data plumbing

(defn- page-of
  [data]
  (get-in data [:pages-index (first (:pages data))]))

(defn- apply-changes
  [data changes]
  (cfc/process-changes data (:redo-changes changes) false))

(defn- add-shapes
  [data shapes]
  (let [page    (page-of data)
        changes (reduce (fn [changes shape] (pcb/add-object changes shape {:ignore-touched true}))
                        (-> (pcb/empty-changes)
                            (pcb/with-page page)
                            (pcb/with-objects (:objects page)))
                        shapes)]
    (apply-changes data changes)))

(defn- component-id
  [library path name]
  (or (some (fn [c] (when (and (= path (:path c)) (= name (:name c))) (:id c)))
            (vals (get-in library [:data :components])))
      (throw (ex-info "component not found" {:path path :name name}))))

(defn- update-objects
  [data f]
  (let [page-id (:id (page-of data))]
    (update-in data [:pages-index page-id :objects] f)))

(defn- resize
  "Width of a shape, recomputing its geometry."
  [shape width]
  (-> shape
      (assoc :width width)
      (dissoc :selrect :points)
      (cts/setup-shape)))

(defn- instance!
  "Adds a copy of a DS component to `board` and applies `overrides`:
   {:label text :width px :interactions [...] :name str}. Returns the new
   data."
  [data {:keys [library libraries ctx]} board {:keys [path cname x y label width interactions name]}]
  (let [page          (page-of data)
        objects       (:objects page)
        [root changes]
        (cll/generate-instantiate-component
         (-> (pcb/empty-changes)
             (pcb/with-page page)
             (pcb/with-objects objects))
         objects
         (:id library)
         (component-id library path cname)
         (gpt/point (+ (:x board) x) (+ (:y board) y))
         page
         libraries
         nil
         (:id board)
         (:id board)
         {})
        data          (apply-changes data changes)
        root-id       (:id root)]
    (update-objects
     data
     (fn [objects]
       (let [root     (get objects root-id)
             children (map #(get objects %) (:shapes root))
             text     (some #(when (= :text (:type %)) %) children)
             width    (or width (:width root))
             objects  (assoc objects root-id
                             (cond-> root
                               (not= width (:width root)) (-> (resize width)
                                                              (update :touched (fnil conj #{}) :geometry-group))
                               name         (assoc :name name)
                               interactions (assoc :interactions interactions)))]
         (if (and text (or label (not= width (:width root))))
           (let [pad   (- (:x text) (:x root))
                 style (-> text :content :children first :children first
                           (select-keys [:fills :font-id :font-family :font-variant-id :font-size
                                         :font-weight :font-style :line-height :letter-spacing
                                         :text-transform :text-align :text-decoration
                                         :text-direction]))
                 vt    (get-in text [:content :vertical-align] "center")]
             (assoc objects (:id text)
                    (cond-> text
                      label
                      (-> (assoc :content (content label style (keyword vt)))
                          (update :touched (fnil conj #{}) :text-content-group))

                      (not= width (:width root))
                      (-> (resize (- width (* 2 pad)))
                          (update :touched (fnil conj #{}) :geometry-group)))))
           objects))))))

;; --- Screens

(defn- label+input
  "A field: label text and an Input copy, starting at `y`. Returns
   [data next-y]."
  [data env board ctx y label placeholder]
  (let [label-h (line-height-px (typo ctx "typography.label.md"))
        data    (add-shapes data [(text-shape ctx board {:name (str "Rótulo " label)
                                                         :text label
                                                         :x X :y y
                                                         :color-token "color.text.primary"
                                                         :typo-token "typography.label.md"})])
        data    (instance! data env board {:path "Input" :cname "Default"
                                           :name (str "Campo " label)
                                           :x X :y (+ y label-h 8)
                                           :label placeholder})]
    [data (+ y label-h 8 CONTROL 16)]))

(defn- title+subtitle
  [data ctx board title subtitle]
  (let [title-h (line-height-px (typo ctx "typography.heading.lg"))]
    [(add-shapes data [(text-shape ctx board {:name "Título" :text title :x X :y 96
                                              :color-token "color.text.primary"
                                              :typo-token "typography.heading.lg"})
                       (text-shape ctx board {:name "Subtítulo" :text subtitle :x X :y (+ 96 title-h 8)
                                              :color-token "color.text.secondary"
                                              :typo-token "typography.body.md"})])
     (+ 96 title-h 8 (line-height-px (typo ctx "typography.body.md")) 32)]))

(defn- link
  [ctx board {:keys [name text y destination align]}]
  (assoc (text-shape ctx board {:name name :text text :x X :y y
                                :color-token "color.text.link"
                                :typo-token "typography.label.md"
                                :align (or align :left)})
         :interactions (navigate destination)))

(defn- draw-screens
  [data env ids]
  (let [{:keys [ctx]} env
        boards  (into {}
                      (map-indexed
                       (fn [i [k name]] [k (board-shape ctx {:id (get ids k) :name name :index i})]))
                      [[:login "Login"]
                       [:signup "Criar conta"]
                       [:signup-done "Conta criada"]
                       [:recover "Recuperar senha"]
                       [:recover-done "Link enviado"]])
        data    (add-shapes data (map boards [:login :signup :signup-done :recover :recover-done]))
        link-h  (line-height-px (typo ctx "typography.label.md"))

        ;; Login
        b       (:login boards)
        [data y] (title+subtitle data ctx b "Entrar" "Acesse sua conta Proto Care")
        [data y] (label+input data env b ctx y "E-mail" "seu@email.com")
        [data y] (label+input data env b ctx y "Senha" "••••••••")
        data    (add-shapes data [(link ctx b {:name "Link esqueci minha senha"
                                               :text "Esqueci minha senha"
                                               :y (- y 8) :destination (:recover ids)})])
        y       (+ y -8 link-h 24)
        data    (instance! data env b {:path "Button" :cname "Primary" :name "Botão entrar"
                                       :x X :y y :width INNER :label "Entrar"
                                       :interactions (navigate (:signup-done ids))})
        data    (instance! data env b {:path "Button" :cname "Secondary" :name "Botão criar conta"
                                       :x X :y (+ y CONTROL 16) :width INNER :label "Criar conta"
                                       :interactions (navigate (:signup ids))})

        ;; Criar conta
        b       (:signup boards)
        [data y] (title+subtitle data ctx b "Criar conta" "Leva menos de um minuto")
        [data y] (label+input data env b ctx y "Nome" "Seu nome completo")
        [data y] (label+input data env b ctx y "E-mail" "seu@email.com")
        [data y] (label+input data env b ctx y "Senha" "Mínimo de 8 caracteres")
        data    (instance! data env b {:path "Button" :cname "Primary" :name "Botão criar conta"
                                       :x X :y (+ y 8) :width INNER :label "Criar conta"
                                       :interactions (navigate (:signup-done ids))})
        data    (add-shapes data [(link ctx b {:name "Link já tenho conta" :text "Já tenho uma conta"
                                               :y (+ y 8 CONTROL 24) :align :center
                                               :destination (:login ids)})])

        ;; Conta criada
        b       (:signup-done boards)
        data    (instance! data env b {:path "Badge" :cname "Sucesso" :name "Selo sucesso" :x X :y 96})
        [data y] (title+subtitle data ctx
                                 (update b :y + 40) "Conta criada!" "Tudo pronto. Agora é só entrar com seu e-mail e senha.")
        data    (instance! data env b {:path "Button" :cname "Primary" :name "Botão ir para o login"
                                       :x X :y (+ y 40) :width INNER :label "Ir para o login"
                                       :interactions (navigate (:login ids))})

        ;; Recuperar senha
        b       (:recover boards)
        [data y] (title+subtitle data ctx b "Recuperar senha" "Enviaremos um link para criar uma nova senha")
        [data y] (label+input data env b ctx y "E-mail" "seu@email.com")
        data    (instance! data env b {:path "Button" :cname "Primary" :name "Botão enviar link"
                                       :x X :y (+ y 8) :width INNER :label "Enviar link"
                                       :interactions (navigate (:recover-done ids))})
        data    (add-shapes data [(link ctx b {:name "Link voltar para o login" :text "Voltar para o login"
                                               :y (+ y 8 CONTROL 24) :align :center
                                               :destination (:login ids)})])

        ;; Link enviado
        b       (:recover-done boards)
        data    (instance! data env b {:path "Badge" :cname "Info" :name "Selo info" :x X :y 96})
        [data y] (title+subtitle data ctx (update b :y + 40) "Confira seu e-mail"
                                 "Se existir uma conta com esse e-mail, o link chega em alguns minutos.")
        data    (instance! data env b {:path "Button" :cname "Secondary" :name "Botão voltar para o login"
                                       :x X :y (+ y 40) :width INNER :label "Voltar para o login"
                                       :interactions (navigate (:login ids))})]
    data))

(defn- add-flows
  [data ids]
  (let [page-id (:id (page-of data))]
    (reduce (fn [data [name start]]
              (let [id (uuid/next)]
                (cfc/process-changes data [{:type :set-flow :page-id page-id :id id
                                            :params {:id id :name name :starting-frame start}}]
                                     false)))
            data
            [["Entrar" (:login ids)]
             ["Criar conta" (:signup ids)]
             ["Esqueci minha senha" (:recover ids)]])))

(defn- set-tokens-source
  [data library]
  (apply-changes data (clt/generate-set-tokens-source
                       (-> (pcb/empty-changes) (pcb/with-library-data data))
                       library)))

(defn- build
  [file libraries library-id]
  (let [data    (:data file)
        page    (page-of data)
        _       (when (> (count (:objects page)) 1)
                  (throw (ex-info "the first page is not empty, refusing to draw over it"
                                  {:page (:name page)})))
        library (get libraries library-id)
        env     {:library library :libraries libraries :ctx (ds-context library)}
        ids     (zipmap [:login :signup :signup-done :recover :recover-done] (repeatedly uuid/next))
        data    (-> data
                    (set-tokens-source library)
                    (assoc-in [:pages-index (:id page) :name] "Fluxo de acesso")
                    (draw-screens env ids)
                    (add-flows ids))]
    (assoc file :data data)))

(defn run!
  "`profile-id` is recorded as the author of the snapshot taken before
   drawing (the file owner)."
  [{:keys [file-id library-id profile-id]}]
  (db/tx-run! sys/system
              (fn [{:keys [::db/conn] :as cfg}]
                (db/update! conn :file {:is-shared true} {:id library-id})
                (when-not (db/get* conn :file-library-rel {:file-id file-id :library-file-id library-id})
                  (db/insert! conn :file-library-rel {:file-id file-id :library-file-id library-id}))
                (h/process-file! cfg file-id
                                 (fn [file libraries _opts] (build file libraries library-id))
                                 ::h/with-libraries? true
                                 ::h/profile-id profile-id
                                 ::h/snapshot-label "Antes das telas de cadastro"))))
