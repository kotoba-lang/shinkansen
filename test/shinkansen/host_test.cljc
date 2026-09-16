(ns shinkansen.host-test
  "The reference host as a pure function: request data → response data,
  every branch named. The running server is scripts/host-node-check.cljk;
  this pins what the transport hands it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [shinkansen.host :as host]))

(def ^:private cid "bafkreia2cc444k5yrw57uhszfrvbri7wee3ljbpb5wfcorykk72kgxhjaq")
(def ^:private page "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>home</body></html>")

(defn- ctx [& [{:keys [authorize-fn dev? documents]}]]
  (let [state (atom {:db {:n 0} :chain []})]
    {:tree {:path "/" :document :home
            :children [{:path "items" :document :items :layout (fn [inner p] (str "<!-- layout -->" inner))
                        :children [{:path ":id" :document :item}]}]}
     :documents (or documents
                    {:home {:html page :mode :ssg :artifact cid}
                     :items {:mode :ssr :render-fn (fn [p d] (str "<html><body>items n=" (:n d) "</body></html>"))
                             :load-fn (fn [_] {:n (get-in @state [:db :n])})}
                     :item {:mode :ssr :render-fn (fn [p d] (str "<html><body>item " (:id p) " q=" (:q p) "</body></html>"))}})
     :cid-fn (fn [t] (str "cid-" (hash t)))
     :decl {:events {:count/inc {}}}
     :app {:state state :state-fn (fn [db _] (update db :n inc))}
     :authorize-fn authorize-fn
     :principal-fn (fn [h] (get h "x-principal"))
     :dev? dev?}))

(defn- get* [c url & [headers]] (host/handle {:method "GET" :url url :headers (or headers {})} c))
(defn- post* [c body & [headers]] (host/handle {:method "POST" :url "/invoke" :headers (or headers {}) :body body} c))

(deftest a-name-resolves-to-bytes-with-the-cid-as-etag
  (let [r (get* (ctx) "/")]
    (is (= 200 (:status r)))
    (is (= page (:body r)))
    (is (= (str "\"cid-" (hash page) "\"") (get-in r [:headers "etag"])))
    (is (= "no-cache" (get-in r [:headers "cache-control"])) "a name is mutable: revalidate")
    (is (str/starts-with? (get-in r [:headers "link"]) "<ipfs://cid-") "identity lives at ipfs://")
    (testing "and the same CID answers 304"
      (is (= 304 (:status (get* (ctx) "/" {"if-none-match" (get-in r [:headers "etag"])})))))))

(deftest an-unresolvable-name-is-a-404-that-names-the-reason
  (let [r (get* (ctx) "/nope")]
    (is (= 404 (:status r)))
    (is (str/includes? (:body r) ":no-match"))
    (is (str/includes? (:body r) "viewport") "an error page is still a page")))

(deftest a-leaf-the-documents-do-not-carry-is-a-500-not-a-404
  (let [r (get* (ctx {:documents {}}) "/")]
    (is (= 500 (:status r)))
    (is (str/includes? (:body r) ":document-not-registered"))))

(deftest ssr-is-a-query-over-params-and-load-and-layouts-compose
  (let [r (get* (ctx) "/items/7?q=x")]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "item 7 q=x") "path param and query string reach the render fn")
    (is (str/starts-with? (:body r) "<!-- layout -->") "the parent layout wraps the leaf"))
  (let [r (get* (ctx) "/items")]
    (is (str/includes? (:body r) "items n=0") "load-fn ran and its data reached the render fn")))

(deftest dev-bytes-carry-the-reload-listener-and-published-bytes-do-not
  (is (str/includes? (:body (get* (ctx {:dev? true}) "/")) "/__shinkansen/reload"))
  (is (not (str/includes? (:body (get* (ctx) "/")) "/__shinkansen/reload"))))

(deftest invoke-without-an-authorizer-is-403-no-authorizer
  (let [r (post* (ctx) {:artifact cid :input {:kind "action" :event ["count/inc"]}})]
    (is (= 403 (:status r)))
    (is (str/includes? (:body r) "\"reason\":\"no-authorizer\""))))

(deftest invoke-with-an-allowing-authorizer-appends-and-the-next-ssr-sees-it
  (let [c (ctx {:authorize-fn (fn [_] {:ok true :reason :granted})})
        r (post* c {:artifact cid :input {:kind "action" :event ["count/inc"]}}
                 {"authorization" "Bearer opaque" "x-principal" "did:key:zAlice"})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "\"height\":1"))
    (is (str/includes? (:body r) "\"principal\":\"did:key:zAlice\""))
    (is (not (str/includes? (:body r) "\"grant\":")) "the grant is never echoed")
    (is (str/includes? (:body (get* c "/items")) "items n=1") "the chain moved; the query sees the new head")
    (testing "the JSON wire's string event id met the keyword declaration"
      (is (str/includes? (:body r) "\"event\":[\"count/inc\"]")))))

(deftest the-grant-reaches-the-authorizer-from-the-bearer-header-and-the-url-does-not
  (let [seen (atom nil)
        c (ctx {:authorize-fn (fn [q] (reset! seen q) {:ok true})})]
    (post* c {:artifact cid :input {:kind "action" :event ["count/inc"]}} {"Authorization" "Bearer tok-123"})
    (is (= "tok-123" (:grant @seen)))
    (is (= #{:principal :artifact :grant :effect} (set (keys @seen))))))

(deftest refusals-map-to-named-statuses
  (let [allow (fn [_] {:ok true})
        deny (fn [_] {:ok false :reason :out-of-scope})]
    (is (= 400 (:status (post* (ctx {:authorize-fn allow}) {:artifact "Qm123" :input {:kind "action" :event ["count/inc"]}}))))
    (is (= 400 (:status (post* (ctx {:authorize-fn allow}) {:artifact cid :input {:kind "mutation"}}))))
    (is (= 403 (:status (post* (ctx {:authorize-fn deny}) {:artifact cid :input {:kind "action" :event ["count/inc"]}}))))
    (let [r (post* (ctx {:authorize-fn allow}) {:artifact cid :input {:kind "action" :event ["count/nope"]}})]
      (is (= 422 (:status r)))
      (is (str/includes? (:body r) "undeclared-event")))
    (is (= 405 (:status (host/handle {:method "GET" :url "/invoke" :headers {}} (ctx)))))
    (is (= 405 (:status (host/handle {:method "DELETE" :url "/" :headers {}} (ctx)))))))

(deftest a-query-answers-a-station-through-the-same-seam
  (let [c (ctx {:authorize-fn (fn [_] {:ok true})})
        r (post* c {:artifact cid :input {:kind "query" :params {}}} {"authorization" "Bearer t"})]
    (is (= 200 (:status r)))
    (is (str/includes? (:body r) "\"data-cid\":\"cid-"))))

(deftest json-out-speaks-the-interaction-vocabulary
  (is (= "{\"a\":[\"todo/add\",\"milk\",1,true,null],\"b\":\"x\\\"y\"}"
         (host/->json {:a [:todo/add "milk" 1 true nil] :b "x\"y"}))))

(deftest identity-headers-are-exposed-for-hosts-that-serve-bytes-elsewhere
  (is (= {"cache-control" "no-cache" "etag" "\"cid-1\"" "link" "<ipfs://cid-1>; rel=\"canonical\""}
         (host/document-headers {:cid "cid-1"})))
  (is (= {"cache-control" "public, max-age=60, stale-while-revalidate=60" "etag" "\"c\"" "link" "<ipfs://c>; rel=\"canonical\""}
         (host/document-headers {:cid "c" :mode :isr :revalidate-seconds 60})))
  (is (= {"cache-control" "no-cache"} (host/document-headers {})) "no CID, no identity claimed")
  (is (host/not-modified? "\"cid-1\"" "cid-1"))
  (is (host/not-modified? "\"x\", \"cid-1\"" "cid-1") "a list of tags")
  (is (not (host/not-modified? "\"cid-2\"" "cid-1")))
  (is (not (host/not-modified? nil "cid-1")))
  (is (not (host/not-modified? "\"cid-1\"" nil)) "no CID can never match"))

