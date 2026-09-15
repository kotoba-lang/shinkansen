(ns shinkansen.locale-test
  (:require [clojure.test :refer [deftest is]]
            [shinkansen.locale :as locale]
            [shinkansen.publish :as publish]))

(deftest cookie-wins-over-accept-language
  (is (= {:locale :ja :source :cookie}
         (locale/negotiate {:supported [:en :ja]
                            :default :en
                            :cookie-value "ja"
                            :accept-language "en;q=0.9,ja;q=0.3"}))))

(deftest accept-language-q-values-ordered
  ;; en;q=0.3, ja;q=0.9 → ja wins; reversed weights → en wins; no overlap
  ;; with :supported → :default.
  (is (= {:locale :ja :source :accept-language}
         (locale/negotiate {:supported [:en :ja] :default :en
                            :accept-language "en;q=0.3,ja;q=0.9"})))
  (is (= {:locale :en :source :accept-language}
         (locale/negotiate {:supported [:en :ja] :default :ja
                            :accept-language "en;q=0.9,ja;q=0.3"})))
  (is (= {:locale :en :source :default}
         (locale/negotiate {:supported [:en :ja] :default :en
                            :accept-language "fr;q=1.0,zh;q=0.5"}))))

(deftest unsupported-cookie-falls-to-default
  ;; Fail-closed: a stale/forged cookie outside :supported is never
  ;; trusted; it falls through (here to :default), never to a locale the
  ;; host does not serve.
  (is (= {:locale :en :source :default}
         (locale/negotiate {:supported [:en :ja] :default :en
                            :cookie-value "fr"
                            :accept-language "fr;q=1.0"})))
  (is (= {:locale :ja :source :accept-language}
         (locale/negotiate {:supported [:en :ja] :default :en
                            :cookie-value "fr"
                            :accept-language "ja;q=0.9"}))))

(deftest cookie-attrs-string-shape
  (is (= "shinkansen_locale=ja; Path=/; SameSite=Lax; Secure; Max-Age=31536000"
         (locale/set-cookie-header
          {:name "shinkansen_locale" :value "ja"
           :cookie-attrs (:cookie-attrs locale/defaults)})))
  ;; boolean flags: true emits bare flag, false omits entirely
  (is (= "shinkansen_locale=en; Path=/; Secure"
         (locale/set-cookie-header
          {:name "shinkansen_locale" :value "en"
           :cookie-attrs {:path "/" :same-site nil :secure true :max-age false}}))))

(deftest substitute-replaces-locale-and-strings
  (let [html "<html lang=\"{{LOCALE}}\"><button>Sign in</button> {{LOCALE}}"
        out (locale/substitute html {:locale "ja" :strings {"Sign in" "サインイン"}})]
    (is (re-find #"lang=\"ja\"" out))
    (is (re-find #"サインイン" out))
    (is (not (re-find #"\{\{LOCALE\}\}" out))))
  ;; missing translation passes through as source text (fail-open content)
  (is (re-find #"Sign in"
               (locale/substitute "<b>Sign in</b>"
                                  {:locale "ja" :strings {"Other" "他"}}))))

(deftest variants-produce-distinct-cids-for-distinct-locales
  (let [variants (locale/document-variants
                  {:base-name "top" :locales [:en :ja]
                   :html-fn #(str "<html><body>hello " (name %) "</body></html>")
                   :cid-fn (fn [name html] (str "cid:" (hash [name html])))})]
    (is (= [:en :ja] (mapv :locale variants)))
    (is (= ["top" "top"] (mapv :entry-name variants)))
    (is (not= (:cid (first variants)) (:cid (second variants))))
    (is (every? :cid variants))))

(deftest same-cid-for-same-locale-content
  (let [f (fn [locale] (locale/document-variants
                        {:base-name "top" :locales [locale]
                         :html-fn (constantly "<html><body>same</body></html>")
                         :cid-fn (fn [_ html] (str "cid:" (hash html)))}))]
    (is (= (:cid (first (f :en))) (:cid (first (f :en)))))))

(deftest variants-pass-publish-manifest-self-contained-check
  (let [good (locale/document-variants
              {:base-name "top" :locales [:en :ja]
               :html-fn #(str "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>@media(max-width:30rem){.x{}}</style></head><body>page " (name %) "</body></html>")
               :cid-fn (fn [_ html] (str "cid:" (hash html)))})
        bad  (locale/document-variants
              {:base-name "bad" :locales [:en]
               :html-fn (constantly "<html><script src=\"https://cdn.example.com/x.js\"></script></html>")
               :cid-fn (fn [_ html] (str "cid:" (hash html)))})]
    ;; every good variant passes the publish/manifest self-contained rule
    ;; — the SAME contract as any shinkansen document.
    (is (every? :self-contained good))
    (doseq [v good]
      (is (true? (:ok (:manifest v)))))
    ;; and the non-self-contained one is refused, by name
    (is (false? (:self-contained (first bad))))
    (is (= ["https://cdn.example.com/x.js"] (:external (:manifest (first bad)))))))
