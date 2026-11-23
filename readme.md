# test-runner2

An opinionated fork of
[cognitect-labs/test-runner](https://github.com/cognitect-labs/test-runner).

## Rationale

Selecting a subset of tests to run using the original `test-runner` is cumbersome.

If `'^:focus` metadata is present on any tests, only those tests will be executed.

Tests having `^:skip` metadata will be skipped.

## Installation

```clojure
:aliases {:test {:extra-paths ["test"]
                 :extra-deps  {io.github.ivarref/test-runner
                               {:git/sha "3d8216f71ad69a5164d9f67ed91f608ef2a0e631"
                                :git/tag "0.1.95"}}
                 :main-opts   ["-m" "cognitect.test-runner"]
                 :exec-fn     cognitect.test-runner.api/test}}
```

## Example

Write tests like this:

```clojure
(ns my-ns-test
  (:require [clojure.test :refer :all]))

(deftest ^:focus test-1
  (println "Only this will run now"))

(deftest test-2
  (println "This won't run until the ^:focus metadata is removed from test-1"))

(deftest ^:skip test-3
  (println "This test will be skipped regardless of ^:focus metadata"))
```

Force running all tests, even when `^:focus` and `^:skip` is present:

```
clojure -M:test --all

clojure -X:test :all true
```

## Copyright and License

Copyright © Nu North America, Inc.

Licensed under the Eclipse Public License, Version 2.0