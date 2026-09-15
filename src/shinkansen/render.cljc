(ns shinkansen.render
  "Rendering modes — the rendering-modes increment (maturity axis D).

  Per-document :mode on the manifest. Fail-closed defaults:

    :ssg  (default) — the document was fully rendered at publish; the
          edge serves bytes. No runtime render fn required.
    :ssr  — the document declares a :render-fn; the edge calls it per
          request with (params, data) and serves the HTML. The render fn
          is injected — shinkansen never owns a DOM.
    :isr  — :ssg plus a :revalidate-seconds window recorded on the
          manifest; the edge decides staleness, the framework records
          the contract.

  The mode is DECLARED, checked against what the document actually
  carries, and fail-closed: :ssr without a :render-fn is a manifest
  error, not a runtime 500. Pure .cljc.")

(def ^:const valid-modes #{:ssg :ssr :isr})

(defn check-mode
  "Validate one document's mode declaration. Returns
    {:ok true :mode mode}
  or {:ok false :reason ...} naming the exact inconsistency:
    :unknown-mode       the mode is not one of :ssg/:ssr/:isr
    :ssr-needs-render-fn :ssr declared but no :render-fn supplied
    :ssr-with-data-cid   :ssr declared but the document froze a data CID
                         (that is :ssg/:isr behavior — declare honestly)"
  [{:keys [mode render-fn data-cid]}]
  (let [mode (or mode :ssg)]
    (cond
      (not (contains? valid-modes mode))
      {:ok false :reason :unknown-mode :mode mode}
      (and (= mode :ssr) (not (fn? render-fn)))
      {:ok false :reason :ssr-needs-render-fn :mode mode}
      (and (= mode :ssr) data-cid)
      {:ok false :reason :ssr-with-data-cid :mode mode}
      :else {:ok true :mode mode})))

(defn render-document
  "Render per the mode. :ssg/:isr return the frozen HTML verbatim
  (the publish output IS the artifact); :ssr calls the render-fn with
  (params, data). Fail-closed: an :ssr render that throws answers
  {:ok false :reason :render-failed :error ...} — a rendering error is a
  named error, never a half-HTML response."
  [{:keys [mode render-fn html params data]}]
  (let [chk (check-mode {:mode mode :render-fn render-fn})]
    (if-not (:ok chk)
      chk
      (case (:mode chk)
        :ssr (try
               {:ok true :mode :ssr :html (render-fn (or params {}) data)}
               (catch :default e
                 {:ok false :reason :render-failed
                  :error (or (ex-message e) (str e))}))
        ;; :ssg / :isr — the published bytes are the artifact
        {:ok true :mode (:mode chk) :html html}))))

(defn isr-manifest-fields
  "The :isr fields a manifest must record so the edge can honor the
  revalidate window without re-reading the app. Returns nil when the
  mode is not :isr."
  [{:keys [mode revalidate-seconds]}]
  (when (= mode :isr)
    (cond
      (not (pos? (or revalidate-seconds 0)))
      {:ok false :reason :isr-needs-revalidate-window}
      :else {:ok true :revalidate-seconds revalidate-seconds})))
