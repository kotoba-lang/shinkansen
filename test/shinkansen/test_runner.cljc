(ns shinkansen.test-runner
  "Minimal nbb-compatible test runner. Requires the test namespaces and runs
  cljs.test over them; exits non-zero on any failure so cron/CI read truth."
  (:require [clojure.test :refer [run-tests]]))

(defn -main [& _args]
  (let [summary (run-tests 'shinkansen.state-test 'shinkansen.mcp-test 'shinkansen.publish-test)]
    (println "Ran" (:test summary) "tests containing" (:assert summary) "assertions.")
    (println (:fail summary) "failures," (:error summary) "errors.")
    (if (and (zero? (:fail summary)) (zero? (:error summary)))
      nil
      (throw (ex-info "tests failed" summary)))))
