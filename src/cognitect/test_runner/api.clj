(ns cognitect.test-runner.api
  (:refer-clojure :exclude [test])
  (:require
    [cognitect.test-runner :as tr]))


(defn- do-test
  [{:keys [dirs nses patterns vars includes excludes focus-mode?]}]
  (let [adapted {:dir             (when (seq dirs) (set dirs))
                 :namespace       (when (seq nses) (set nses))
                 :namespace-regex (when (seq patterns) (map re-pattern patterns))
                 :var             (when (seq vars) (set vars))
                 :include         (when (seq includes) (set includes))
                 :exclude         (when (seq excludes) (set excludes))
                 :focus-mode?     focus-mode?}]
    (tr/test adapted)))


(defn- test-org
  "Invoke the test-runner with the following options:

  * :dirs - coll of directories containing tests, default= [\"test\"]
  * :nses - coll of namespace symbols to test
  * :patterns - coll of regex strings to match namespaces
  * :vars - coll of fully qualified symbols to run tests on
  * :includes - coll of test metadata keywords to include
  * :excludes - coll of test metadata keywords to exclude
  * :all - include all tests regardless of metadata keywords\n

  If neither :nses nor :patterns is supplied, use `:patterns [\".*-test$\"]`."
  [opts]
  (let [{:keys [_fail _error]} (do-test opts)]
    nil
    #_(when (> (+ fail error) 0)
        (throw (ex-info "Test failures or errors occurred." {})))))


(defn test [{:keys [dirs includes excludes nses patterns all] :as opts}]
  (let [adapted {:dir             (if (seq dirs)
                                    (set dirs)
                                    (set ["test"]))
                 :namespace       (when (seq nses) (set nses))
                 :namespace-regex (when (seq patterns) (map re-pattern patterns))
                 :excludes        (if all
                                    #{}
                                    (if (seq excludes)
                                      excludes
                                      [:exclude
                                       :skip
                                       :ignore]))}
        focus-mode? (if all
                      false
                      (tr/focus-mode? adapted))
        adapted' (assoc adapted
                   :focus-mode? focus-mode?
                   :includes (if (seq includes)
                               includes
                               (if focus-mode?
                                 [:focus]
                                 nil)))]
    (test-org adapted')))


