(ns knossos.history-test
  (:require [knossos.history :refer :all]
            [clojure.test :refer :all]
            [knossos.op :as op]))

(deftest complete-test
  (testing "empty history"
    (is (= (complete [])
           [])))

  (testing "an invocation"
    (is (= (complete [(op/invoke :a :read nil)])
           [(op/invoke :a :read nil)])))

  (testing "a completed invocation"
    (is (= (complete [(op/invoke :a :read nil)
                      (op/ok     :a :read 2)])
           [(op/invoke :a :read 2)
            (op/ok     :a :read 2)])))

  (testing "a failed invocation"
    (is (= (complete [(op/invoke :a :read nil)
                      (op/fail   :a :read nil)])
           [(assoc (op/invoke :a :read nil) :fails? true)
            (op/fail   :a :read nil)])))

  (testing "an unbalanced set of invocations"
    (is (thrown? RuntimeException
                 (complete [(op/invoke :a :read nil)
                            (op/invoke :a :read nil)]))))

  (testing "an unbalanced completion"
    (is (thrown? AssertionError
                 (complete [(op/ok :a :read 2)])))))
