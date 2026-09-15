(ns shinkansen.locale
  "Cookie-based, path-independent locale negotiation — the framework default.

  Owner decision (measured live on app-kotoba.cloud): locale selection by
  URL path (`/ja/about`) creates dead links and IA fragmentation — 22
  locales × path copies per page. The shinkansen default is instead:

    one document per language-agnostic route; the locale is negotiated
    by cookie (with Accept-Language fallback); the language switch
    writes the cookie client-side.

  This matches the content-addressed document model: each locale variant
  of a document is still its own CID (identity is content), but the ENTRY
  route never forks per locale — the host/edge negotiates. This namespace
  is the pure contract for that negotiation; apps must not re-derive it
  (app-kotoba.cloud's kb_locale cookie + /js/language-selector.js is the
  same pattern this replaces with a framework default).

  Pure .cljc, no js/ (dual-render per shitsuke). Fail-closed: a cookie
  value outside :supported is never trusted."
  (:require [clojure.string :as str]
            [shinkansen.publish :as publish]))

(def ^:const defaults
  "The framework-default negotiation configuration. :cookie-attrs are the
  ONE place the Set-Cookie attributes live — apps must not re-derive them
  (the drift this prevents: one app with Missing SameSite, another with a
  different max-age, a third session-scoped)."
  {:cookie-name "shinkansen_locale"
   :cookie-attrs {:path "/" :same-site "Lax" :secure true :max-age 31536000}})

(defn- locale-name
  "The wire form of a locale: :ja → \"ja\", \"ja\" → \"ja\". (str :ja) is
  \":ja\" and would never match a cookie — always go through here."
  [l]
  (if (keyword? l) (name l) (str l)))

(defn- supported-set
  [supported]
  (set (map locale-name supported)))

(defn- parse-q
  "Pure, engine-neutral numeric parse of a regex-validated q string
  ([0-9.]+): integer part + fractional part summed via an explicit digit
  map. No Double/parseDouble (clj-only), no js/ (cljs-only), and no char
  arithmetic (nbb's `int` on a char is not the code point) — dual-render."
  [s]
  (let [d {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9}
        [int-part frac-part] (str/split s #"\." 2)
        digits (fn [str']
                 (reduce (fn [acc ch] (+ (* acc 10) (get d ch 0))) 0 str'))]
    (+ (digits (or int-part "0"))
       (/ (digits (or frac-part "0"))
          (Math/pow 10 (count (or frac-part "0")))))))

(defn- parse-accept-language
  "\"en;q=0.3,ja;q=0.9\" → [[\"ja\" 0.9] [\"en\" 0.3]] — q-descending, ties
  keep header order (HTTP precedence). Malformed tags/weights are skipped,
  not fatal."
  [al]
  (when (string? al)
    (->> (str/split al #",")
         (map #(str/split % #";" 2))
         (keep (fn [[tag params]]
                 (when-let [t (some-> tag str/trim str/lower-case not-empty)]
                   (let [q (if-let [m (and params (re-find #"q\s*=\s*([0-9.]+)" params))]
                             (parse-q (second m))
                             1.0)]
                     [t (if (pos? q) q 1.0)]))))
         (sort-by second >))))

(defn negotiate
  "Resolve the locale for one request.

    (negotiate {:supported [:en :ja] :default :en
                :cookie-value \"ja\"
                :accept-language \"en;q=0.3,ja;q=0.9\"})
    → {:locale :ja :source :cookie}

  Precedence: explicit cookie > Accept-Language q-values > :default.
  The cookie value is NEVER trusted blindly: it must be in :supported or
  it is ignored (fail-closed) and negotiation falls through to
  Accept-Language, then the default — a stale/forged cookie cannot pin a
  locale the host does not serve. The result records its :source so the
  host can decide whether to (re)write the cookie."
  [{:keys [supported default cookie-value accept-language]}]
  (let [sup (supported-set supported)]
    (cond
      (and (string? cookie-value)
           (contains? sup (str/trim cookie-value)))
      {:locale (some (fn [l] (when (= (locale-name l) (str/trim cookie-value)) l)) supported)
       :source :cookie}

      :else
      (if-let [hit (->> (parse-accept-language accept-language)
                        (keep (fn [[t _]]
                                (some (fn [l]
                                        (when (= (str/lower-case (locale-name l)) t) l))
                                      supported)))
                        first)]
        {:locale hit :source :accept-language}
        {:locale default :source :default}))))

(defn- attr-str
  "One attribute → its string, or nil when the attribute must be omitted:
  valued attrs (:path :domain :same-site :max-age) need a non-nil value;
  flag attrs (:secure :http-only) are emitted only when true — a false
  flag is an omission, never the literal \"false\"."
  [[k v]]
  (case k
    (:path :domain :same-site :max-age) (when (and (some? v) (not (false? v)))
                                          (str (case k
                                                 :path "Path=" :domain "Domain="
                                                 :same-site "SameSite=" :max-age "Max-Age=")
                                               v))
    (:secure :http-only) (when v (if (= k :secure) "Secure" "HttpOnly"))
    nil))

(defn set-cookie-header
  "Build the Set-Cookie header string from :cookie-attrs — the one place
  the attributes are serialized. Keys: :path :domain :same-site :max-age
  :secure :http-only. Booleans emit bare flags; nil/absent are omitted.
  Values are emitted as-is: this is a build/host seam, not a sanitizer —
  callers pass a value already negotiated through `negotiate` (which only
  returns a :supported locale)."
  [{:keys [name value cookie-attrs]}]
  (let [attrs (->> (seq (or cookie-attrs {}))
                   (keep attr-str)
                   (remove nil?))]
    (str name "=" value (when (seq attrs)
                          (str "; " (str/join "; " attrs))))))

(defn substitute
  "Template substitution for build-time-generated documents — the SSR
  seam. Pairs with shitsuke's i18n table (the app-kotoba.cloud
  sign_in_i18n pattern: source string → localized string resolved at
  request time).

    (substitute html {:locale \"ja\" :strings {\"Sign in\" \"サインイン\"}})

  Replaces {{LOCALE}} tokens with the locale tag, then each :strings
  source with its localization. Unknown strings pass through unchanged
  (a missing translation must be visible as source text, never blank) —
  fail-open for content, exactly because locale SELECTION above is
  fail-closed. Pure; the same substitution runs in SSR (bb/nbb) and in
  a browser host."
  [html {:keys [locale strings]}]
  (let [html (if (nil? html) "" (str html))
        html (if locale
               (str/replace html #"\{\{LOCALE\}\}" (str locale))
               html)]
    (if strings
      (reduce (fn [h [src localized]]
                (str/replace h (str src) (str localized)))
              html strings)
      html)))

(defn document-variants
  "The content-addressing side of locale support: ONE route → N locale
  documents, each its own CID.

    (document-variants {:base-name \"top\"
                        :locales [:en :ja]
                        :html-fn (fn [locale] ...)
                        :cid-fn (fn [entry-name html] ...)})

  → [{:locale :en :cid \"...\" :entry-name \"top\" :manifest {...}} ...]

  Each variant runs through the SAME self-contained rules as
  publish/manifest — a locale variant that references external assets is
  refused exactly like any other document (:self-contained false, with
  the refusing manifest on the variant). The CID is injected (:cid-fn);
  shinkansen does not hash — publish-document.cljk and state.cljc own
  that.

  THE RULE (owner decision): identity stays per-locale-CID; the
  name/route is locale-independent; negotiation happens at the edge/host
  with the cookie. There is NO path fork like /ja/ — a locale variant is
  never a new entry route, only a new content address the host serves
  after negotiation."
  [{:keys [base-name locales html-fn cid-fn]}]
  (when (and (string? base-name) (seq locales) (fn? html-fn) (fn? cid-fn))
    (mapv (fn [locale]
            (let [entry-name (str base-name)
                  html (html-fn locale)
                  cid (cid-fn entry-name html)
                  m (publish/manifest {:file (str entry-name "." (str locale) ".html")
                                       :html html
                                       :entry-name entry-name})]
              {:locale locale
               :cid cid
               :entry-name entry-name
               :self-contained (boolean (:ok m))
               :manifest (assoc m :cid cid)}))
          locales)))
