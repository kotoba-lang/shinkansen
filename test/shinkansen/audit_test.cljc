(ns shinkansen.audit-test
  "Both directions for every axis: a document that passes it and a document
  that fails it WITH the reason literal pinned — a negative test that only
  asserts the score counts runs that failed for another reason."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shinkansen.audit :as audit]))

(def head
  (str "<!doctype html><html lang=\"ja\"><head>"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
       "<script src=\"/js/session.js\" defer></script>"
       "<style>.kc-sb{position:fixed;inset-block:0;inset-inline-start:0;inline-size:15rem}"
       "@media(max-width:64rem){.kc-sb{position:static}}"
       "@media(max-width:30rem){.kc-sb{padding:0}}</style></head>"))

(def good-doc
  (str head
       "<body><a href=\"#main\">本文へ</a>"
       "<nav><a href=\"/docs/\">ドキュメント</a><a href=\"/account\" data-current=\"true\">アカウント</a><a href=\"/billing/\">料金</a></nav>"
       "<main id=\"main\"><h1>アカウント</h1>"
       "<p role=\"status\" data-state=\"loading\">サインイン状態を確認しています…</p>"
       "<a class=\"dads-button\" href=\"https://auth.example/\">サインイン</a>"
       "<div id=\"body\" hidden><span data-state=\"loading\">読み込んでいます…</span>"
       "<button disabled>作成</button><button id=\"x\">更新</button></div>"
       "</main></body></html>"))

(def account-like-doc
  ;; every measured failure of kotoba.cloud/account on 2026-09-15, in one document
  (str "<!doctype html><html lang=\"ja\"><head>"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
       "<script src=\"/js/session.js\" defer></script>"
       "<style>.kc-sb{position:fixed;inset-block:0;inline-size:15rem}"
       "body{padding-inline-start:15rem}.kc-sb-main{margin-inline-start:15rem}"
       "@media(max-width:64rem){.kc-sb{position:static}}"
       "@media(max-width:30rem){.kc-sb{padding:0}}</style></head><body>"
       "<nav>"
       (apply str (for [i (range 6)] (str "<a href=\"/d" i "/\">項目" i "</a>")))
       "<a href=\"/account\" data-current=\"true\">接続トークン (PAT)</a>"
       "<a href=\"/ja/#research-models\">モデル</a>"
       "<a href=\"/account\" data-current=\"true\">利用とインサイト</a>"
       "<a href=\"/account\" data-current=\"true\">アカウント</a>"
       "</nav>"
       "<main id=\"main\" class=\"kc-sb-main\"><h1>アカウント管理</h1>"
       "<section><h2>本人確認（カード認証 / Stripe Identity）</h2>"
       "<span class=\"ck-console__label\">USERNAME</span><span data-state=\"loading\">読み込んでいます…</span>"
       "<span class=\"ck-console__label\">STABLE PRINCIPAL</span><span data-state=\"loading\">読み込んでいます…</span>"
       "<span data-state=\"loading\">読み込んでいます…</span><span data-state=\"loading\">読み込んでいます…</span>"
       "<p>" (apply str (repeat 130 "説")) "</p><p>" (apply str (repeat 130 "明")) "</p>"
       "<button id=\"account-ekyc-start\" disabled>本人確認を開始</button>"
       "<button id=\"account-refresh\">状態を更新</button></section>"
       "<section><h2>組織（Org DID）</h2><button id=\"account-refresh\">状態を更新</button>"
       "<button id=\"account-org-create\" disabled>組織を作成</button></section>"
       "<section><h2>チーム (Members)</h2><button id=\"account-refresh\">状態を更新</button>"
       "<button id=\"account-org-create\" disabled>組織を作成</button></section>"
       "<section><h2>セキュリティ管理 (Guardrails / Firewall / Compliance)</h2>"
       "<button id=\"account-refresh\">状態を更新</button></section>"
       "</main></body></html>"))

(defn- axis [report id] (first (filter #(= id (:id %)) (:axes report))))

(deftest walker-sees-structure
  (let [els (audit/elements good-doc)
        by-id (fn [id] (first (filter #(= id (get-in % [:attrs "id"])) els)))]
    (is (= "main" (:tag (by-id "main"))))
    (is (:hidden? (by-id "body")) "hidden= marks the element")
    (is (:hidden? (by-id "x")) "…and its descendants")
    (is (= "アカウント" (:text (first (filter #(= "h1" (:tag %)) els)))))
    (is (every? :before-main? (filter :in-nav? els)))
    (is (every? :in-main? (filter #(= "p" (:tag %)) els)))))

(deftest closed-details-hides-body-but-not-summary
  (let [els (audit/elements "<main id=\"main\"><details><summary><span id=\"s\">詳細</span></summary><span id=\"b\" data-state=\"loading\">x</span></details></main>")
        by-id (fn [id] (first (filter #(= id (get-in % [:attrs "id"])) els)))]
    (is (not (:hidden? (by-id "s"))))
    (is (:hidden? (by-id "b")))
    (is (= 1.0 (:score (axis (audit/score-document {:file "d" :html (str "<html><head></head><body><main id=\"main\"><details><summary>詳細</summary><span data-state=\"loading\">a</span><span data-state=\"loading\">b</span><span data-state=\"loading\">c</span></details></main></body></html>")} {:assets #{}}) :idle-pending)))
        "pending cells under a closed details are not idle-visible")))

(deftest good-document-is-clean
  (let [r (audit/score-document {:file "good.html" :html good-doc} {:assets #{"/js/session.js"}})]
    (is (empty? (:findings r)) (pr-str (:findings r)))
    (is (empty? (:unmeasured r)))
    (is (= 100.0 (:overall r)))))

(deftest account-like-document-names-every-failure
  (let [r (audit/score-document {:file "account.html" :html account-like-doc} {:assets #{}})
        f (fn [id] (:finding (axis r id)))]
    (testing "assets" (is (str/includes? (f :assets-resolve) "referenced but absent from the published assets: /js/session.js")))
    (testing "ids" (is (str/includes? (f :unique-ids) "account-refresh×4"))
                   (is (str/includes? (f :unique-ids) "account-org-create×2")))
    (testing "nav" (is (str/includes? (f :nav-one-home) "/account×3"))
                   (is (str/includes? (f :nav-one-home) "3 items marked current")))
    (testing "nav before content" (is (str/includes? (f :nav-before-content) "10 nav links precede <main>")))
    (testing "skip" (is (str/includes? (f :skip-link) "no a[href=#main] skip link")))
    (testing "pending" (is (str/includes? (f :idle-pending) "4 pending-state cells visible")))
    (testing "disabled" (is (str/includes? (f :idle-disabled) "3 disabled control(s)")))
    (testing "repeated" (is (str/includes? (f :repeated-actions) "\"状態を更新\"×4")))
    (testing "labels" (is (str/includes? (f :plain-labels) "本人確認（カード認証 / Stripe Identity）"))
                      (is (str/includes? (f :plain-labels) "STABLE PRINCIPAL")))
    (testing "notes" (is (str/includes? (f :note-density) "2 paragraphs over 120 characters")))
    (testing "locale" (is (str/includes? (f :locale-path-links) "/ja/#research-models")))
    (testing "fixed" (is (str/includes? (f :fixed-anchor) "position:fixed without left/right/inset-inline on: .kc-sb")))
    (is (< (:overall r) 40.0) (str "overall " (:overall r)))))

(deftest unmeasured-assets-are-not-a-pass
  (let [r (audit/score-document {:file "a" :html good-doc} {})]
    (is (= [{:axis :assets-resolve :why "no asset set supplied — cannot tell whether the referenced scripts exist; a document whose script 404s never hydrates"}]
           (:unmeasured r)))
    (is (nil? (:score (axis r :assets-resolve))))
    (is (= 100.0 (:overall r)) "unmeasured is excluded from the mean, not counted as 0 or 1")))

(deftest no-referenced-assets-needs-no-asset-set
  (let [r (audit/score-document {:file "a" :html "<html><head></head><body><main id=\"main\"></main></body></html>"} {})]
    (is (empty? (:unmeasured r)))))

(deftest viewport-axis-follows-the-viewport-contract
  (let [r (audit/score-document {:file "v" :html "<html><head><style>body{width:1200px}</style></head><body><main id=\"main\"></main></body></html>"} {:assets #{}})]
    (is (str/includes? (:finding (axis r :viewport)) "viewport-missing"))
    (is (str/includes? (:finding (axis r :viewport)) "fixed-width-body"))))

(deftest audit-of-nothing-is-not-clean
  (let [r (audit/audit [] {:assets #{}})]
    (is (:empty? r))
    (is (= 0.0 (:overall r)))))

(deftest audit-aggregates-findings-heaviest-first
  (let [r (audit/audit [{:file "good" :html good-doc} {:file "bad" :html account-like-doc}] {:assets #{"/js/session.js"}})]
    (is (= 2 (count (:documents r))))
    (is (= ["bad"] (:files (first (:findings r)))))
    (is (apply >= (map :headroom (:findings r))) "sorted by headroom")
    (is (empty? (:unmeasured r)))))

(def external-css-doc
  ;; the shell alone: the media bands and the position rules live in /css/site.css
  (str "<!doctype html><html lang=\"ja\"><head>"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
       "<link rel=\"stylesheet\" href=\"/css/site.css\">"
       "</head><body><main id=\"main\"><h1>x</h1></main></body></html>"))

(def site-css
  ".kc-sb{position:fixed;inset-block:0;inline-size:15rem}@media(max-width:30rem){.kc-sb{padding:0}}")

(deftest external-stylesheet-not-supplied-is-unmeasured
  (let [r (audit/score-document {:file "e" :html external-css-doc} {:assets #{"/css/site.css"}})
        why (set (map :axis (:unmeasured r)))]
    (is (= #{:viewport :fixed-anchor} why))
    (is (str/includes? (:why (first (:unmeasured r))) "external stylesheet(s) not supplied: /css/site.css"))
    (is (nil? (:score (axis r :fixed-anchor))))))

(deftest external-stylesheet-supplied-is-measured
  (let [r (audit/score-document {:file "e" :html external-css-doc}
                                {:assets #{"/css/site.css"} :stylesheets {"/css/site.css" site-css}})]
    (is (empty? (:unmeasured r)))
    (is (= 1.0 (:score (axis r :viewport))) "the phone band came from the external sheet")
    (is (str/includes? (:finding (axis r :fixed-anchor)) "position:fixed without left/right/inset-inline on: .kc-sb")
        "…and so did the unanchored fixed rule")))

(deftest inline-css-part-is-measured-too
  (let [r (audit/score-document {:file "e" :html external-css-doc :css site-css}
                                {:assets #{"/css/site.css"}})]
    ;; :css covers the CSS axes' INPUT, but the link is still unresolved by
    ;; :stylesheets — the audit says so rather than guessing they are the same
    (is (= #{:viewport :fixed-anchor} (set (map :axis (:unmeasured r)))))))

(deftest language-switch-links-are-not-locale-forks
  (let [doc "<html><head></head><body><nav><a href=\"/ja/\" hreflang=\"ja\" lang=\"ja\">日本語</a><a href=\"/en/\" hreflang=\"en\">English</a></nav><main id=\"main\"><a href=\"/ja/legal/\">運営</a></main></body></html>"
        r (audit/score-document {:file "l" :html doc} {:assets #{}})]
    (is (= "locale-forked links: /ja/legal/ — one locale-free URL per document; the edge negotiates (shinkansen.locale). A forked link is a redirect hop at best and a dead link at worst"
           (:finding (axis r :locale-path-links)))
        "the hreflang switches are exempt; the plain /ja/legal/ link is not")))
