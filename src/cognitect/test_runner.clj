(ns cognitect.test-runner
  (:require [clojure.string :as str]
            [clojure.tools.namespace.find :as find]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.tools.cli :as cli])
  (:refer-clojure :exclude [test])
  (:import (java.lang.management ManagementFactory)))

(defn- ns-filter
  [{:keys [namespace namespace-regex]}]
  (let [[include-ns include-regexes]
        (if (or (seq namespace) (seq namespace-regex))
          [namespace namespace-regex]
          [nil [#".*\-test$"]])]
    (fn [ns]
      (or
        (get include-ns ns)
        (some #(re-matches % (name ns)) include-regexes)))))


(defn is-focus-mode? [options]
  (let [dirs (or (:dir options)
                 #{"test"})
        nses (->> dirs
                  (map io/file)
                  (mapcat find/find-namespaces-in-dir))
        nses (filter (ns-filter options) nses)
        focus-count (atom 0)
        exclude (:exclude options)]
    (dorun (map require nses))
    (doseq [ns nses]
      (doseq [[_name var] (ns-publics ns)]
        (when (:test (meta var))
          (when
            (:focus (meta var))
            (swap! focus-count inc)))))
    (let [focus-mode? (pos-int? @focus-count)
          skip-count (atom 0)]
      (doseq [ns nses]
        (doseq [[_name var] (ns-publics ns)]
          (when (:test (meta var))
            (if focus-mode?
              (if (:focus (meta var))
                nil
                (swap! skip-count inc))
              (when exclude
                (when ((apply some-fn exclude) (meta var))
                  (swap! skip-count inc)))))))
      [(if focus-mode?
         true
         false)
       @skip-count])))


(defn- var-filter
  [{:keys [var include exclude]}]
  (let [test-specific (if var
                        (set (map #(or (resolve %)
                                       (throw (ex-info (str "Could not resolve var: " %)
                                                       {:symbol %})))
                                  var))
                        (constantly true))
        test-inclusion (if include
                         #((apply some-fn include) (meta %))
                         (constantly true))
        test-exclusion (if exclude
                         #((complement (apply some-fn exclude)) (meta %))
                         (constantly true))]
    #(and (test-specific %)
          (test-inclusion %)
          (test-exclusion %))))

(defn- filter-vars!
  [nses filter-fn]
  (doseq [ns nses]
    (doseq [[_name var] (ns-publics ns)]
      (when (:test (meta var))
        (when (not (filter-fn var))
          (alter-meta! var #(-> %
                                (assoc ::test (:test %))
                                (dissoc :test))))))))

(defn- restore-vars!
  [nses]
  (doseq [ns nses]
    (doseq [[_name var] (ns-publics ns)]
      (when (::test (meta var))
        (alter-meta! var #(-> %
                              (assoc :test (::test %))
                              (dissoc ::test)))))))

(defn- contains-tests?
  "Check if a namespace contains some tests to be executed."
  [ns]
  (some (comp :test meta)
        (-> ns ns-publics vals)))


(defonce start-time (atom nil))


(defn esc [c s]
  (assert (string? s))
  (str "\u001B[" c
       s
       "\u001B[0m"))

(defn green [& s]
  (esc "32m" (str/join " " s)))

(defn red [& s]
  (esc "31m" (str/join " " s)))


(defn- plural [s n]
  (if (or (= n 0)
          (>= n 2))
    (str s "s")
    s))


(defmethod test/report :summary [m]
  (let [spent-ms (- (System/currentTimeMillis)
                    @start-time)
        spent-secs (format "%.3f secs." (double (/ spent-ms 1000)))
        assertions-count (+ (:pass m) (:fail m) (:error m))
        fail-str (str (:fail m) " " (plural "failure" (:fail m)) ",")
        error-str (str (:error m) " " (plural "error" (:error m)) ".")
        overview-str (str fail-str " " error-str)]
    (test/with-test-out
      (println "\nRan" (:test m) (plural "test" (:test m)) "containing"
               assertions-count (plural "assertion" assertions-count) "in"
               spent-secs)
      (if (pos-int? (+ (:fail m)
                       (:error m)))
        (println (red overview-str))
        (println (green overview-str))))))


(defn test
  [options]
  (if (nil? @start-time)
    (reset! start-time (.getStartTime (ManagementFactory/getRuntimeMXBean)))
    (reset! start-time (System/currentTimeMillis)))
  (let [dirs (or (:dir options)
                 #{"test"})
        focus-mode? (:focus-mode? options)
        nses (->> dirs
                  (map io/file)
                  (mapcat find/find-namespaces-in-dir))
        nses (filter (ns-filter options) nses)
        skip-count (:skip-count options)]
    (if (= skip-count 0)
      (if focus-mode?
        (println (format "\nRunning focused tests in %s" dirs))
        (println (format "\nRunning all tests in %s" dirs)))
      (if focus-mode?
        (println (format "\nRunning focused tests in %s, skipping %d" dirs skip-count))
        (println (format "\nRunning tests in %s, skipping %d" dirs skip-count))))
    (dorun (map require nses))
    (try
      (filter-vars! nses (var-filter options))
      (apply test/run-tests (filter contains-tests? nses))
      (finally
        (restore-vars! nses)))))

(defn- parse-kw
  [^String s]
  (if (.startsWith s ":") (read-string s) (keyword s)))


(defn- accumulate [m k v]
  (update-in m [k] (fnil conj #{}) v))

(def cli-options
  [["-d" "--dir DIRNAME" "Name of the directory containing tests. Defaults to \"test\"."
    :parse-fn str
    :assoc-fn accumulate]
   ["-n" "--namespace SYMBOL" "Symbol indicating a specific namespace to test."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-r" "--namespace-regex REGEX" "Regex for namespaces to test."
    :parse-fn re-pattern
    :assoc-fn accumulate]
   ["-v" "--var SYMBOL" "Symbol indicating the fully qualified name of a specific test."
    :parse-fn symbol
    :assoc-fn accumulate]
   ["-i" "--include KEYWORD" "Run only tests that have this metadata keyword."
    :parse-fn parse-kw
    :assoc-fn accumulate]
   ["-e" "--exclude KEYWORD" "Exclude tests with this metadata keyword."
    :parse-fn parse-kw
    :assoc-fn accumulate]
   ["-a" "--all" "Run all tests regardsless of test metadata."
    :assoc-fn assoc]
   ["-H" "--test-help" "Display this help message"]])

(defn- help
  [args]
  (println "\nUSAGE:\n")
  (println "clj -m" (namespace `help) "<options>\n")
  (println (:summary args))
  (println "\nAll options may be repeated multiple times for a logical OR effect.")
  (println "If neither -n nor -r is supplied, use -r #\".*-test$\" (ns'es ending in '-test')"))

(defn -main
  "Entry point for the test runner"
  [& args]
  (let [args (cli/parse-opts args cli-options)]
    (if (:errors args)
      (do (doseq [e (:errors args)]
            (println e))
          (help args)
          (System/exit 1))
      (if (-> args :options :test-help)
        (help args)
        (try
          (let [options' (:options args)
                all (:all options')
                excludes (if (seq (:exclude options'))
                           (:exclude options')
                           (if all
                             nil
                             #{:exclude :skip :ignore}))
                [focus-mode? skip-count] (if all
                                           [false 0]
                                           (is-focus-mode? (assoc options'
                                                             :exclude excludes)))
                includes (if (seq (:include options'))
                           (:include options')
                           (if all
                             nil
                             (if focus-mode?
                               #{:focus}
                               nil)))
                options'' (assoc options'
                            :exclude excludes
                            :skip-count skip-count
                            :focus-mode? focus-mode?
                            :include includes)
                {:keys [fail error]} (test options'')]
            (System/exit (if (zero? (+ fail error)) 0 1)))
          (finally
            ;; Only called if `test` raises an exception
            (shutdown-agents)))))))
