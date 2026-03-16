(ns mcp-tasks.arrays-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-tasks.arrays :as arr]))

;; =============================================================================
;; Long Array Tests
;; =============================================================================

(deftest along-reduce-test
  (testing "basic reduction"
    (is (= 15 (arr/along-reduce + 0 (long-array [1 2 3 4 5])))))
  (testing "with primitive function"
    (is (= 15 (arr/along-reduce
               (fn ^long [^long acc ^long x] (+ acc x))
               0
               (long-array [1 2 3 4 5])))))
  (testing "empty array"
    (is (= 0 (arr/along-reduce + 0 (long-array [])))))
  (testing "product"
    (is (= 120 (arr/along-reduce * 1 (long-array [1 2 3 4 5]))))))

(deftest along-reduce-idx-test
  (testing "weighted sum with index"
    (is (= 14 (arr/along-reduce-idx
               (fn ^long [^long acc ^long idx ^long x] (+ acc (* idx x)))
               0
               (long-array [1 2 3 4]))))))  ; 0*1 + 1*2 + 2*3 + 3*4 = 0+2+6+12 = 20... wait
  ;; Actually: 0*1=0, 1*2=2, 2*3=6, 3*4=12 -> 0+2+6+12=20
  ;; Let me recalculate: idx goes 0,1,2,3 and values are 1,2,3,4
  ;; So: 0*1 + 1*2 + 2*3 + 3*4 = 0 + 2 + 6 + 12 = 20

(deftest along-fold-test
  (testing "parallel fold equals sequential reduce"
    (let [arr (long-array (range 1000))]
      (is (= (arr/along-reduce + 0 arr)
             (arr/along-fold + 0 + arr)))))
  (testing "small array (no parallelism)"
    (is (= 15 (arr/along-fold + 0 + (long-array [1 2 3 4 5])))))
  (testing "large array with custom chunk size"
    (let [arr (long-array (range 10000))]
      (is (= (arr/along-reduce + 0 arr)
             (arr/along-fold + 0 + {:chunk-size 100} arr))))))

(deftest along-map-test
  (testing "double each element"
    (is (= [2 4 6 8 10]
           (vec (arr/along-map (fn ^long [^long x] (* x 2))
                               (long-array [1 2 3 4 5]))))))
  (testing "square each element"
    (is (= [1 4 9 16 25]
           (vec (arr/along-map (fn ^long [^long x] (* x x))
                               (long-array [1 2 3 4 5]))))))
  (testing "empty array"
    (is (= [] (vec (arr/along-map inc (long-array [])))))))

(deftest along-map-idx-test
  (testing "add index to each element"
    (is (= [0 2 4 6 8]
           (vec (arr/along-map-idx
                 (fn ^long [^long idx ^long x] (+ idx x))
                 (long-array [0 1 2 3 4])))))))

(deftest along-filter-test
  (testing "filter even numbers"
    (is (= [2 4 6 8 10]
           (vec (arr/along-filter even? (long-array (range 1 11)))))))
  (testing "filter with primitive pred"
    (is (= [6 7 8 9]
           (vec (arr/along-filter (fn [^long x] (> x 5))
                                  (long-array (range 10)))))))
  (testing "filter none"
    (is (= []
           (vec (arr/along-filter (fn [^long x] (> x 100))
                                  (long-array (range 10)))))))
  (testing "filter all"
    (is (= [0 1 2 3 4]
           (vec (arr/along-filter (fn [^long x] (< x 100))
                                  (long-array (range 5))))))))

(deftest along-some-test
  (testing "finds first match"
    (is (= 6 (arr/along-some (fn [^long x] (> x 5))
                             (long-array (range 10))))))
  (testing "returns nil when no match"
    (is (nil? (arr/along-some (fn [^long x] (> x 100))
                              (long-array (range 10)))))))

(deftest along-every?-test
  (testing "all positive"
    (is (true? (arr/along-every? pos? (long-array [1 2 3 4 5])))))
  (testing "not all positive"
    (is (false? (arr/along-every? pos? (long-array [1 2 -3 4 5])))))
  (testing "empty array"
    (is (true? (arr/along-every? pos? (long-array []))))))

(deftest along-count-test
  (testing "count evens"
    (is (= 5 (arr/along-count even? (long-array (range 10)))))))

(deftest along-sum-test
  (testing "basic sum"
    (is (= 55 (arr/along-sum (long-array (range 1 11)))))))

(deftest along-min-max-test
  (testing "min"
    (is (= 1 (arr/along-min (long-array [5 3 1 4 2])))))
  (testing "max"
    (is (= 5 (arr/along-max (long-array [5 3 1 4 2]))))))

;; =============================================================================
;; Double Array Tests
;; =============================================================================

(deftest adouble-reduce-test
  (testing "basic reduction"
    (is (= 15.0 (arr/adouble-reduce + 0.0 (double-array [1.0 2.0 3.0 4.0 5.0])))))
  (testing "with primitive function"
    (is (= 15.0 (arr/adouble-reduce
                 (fn ^double [^double acc ^double x] (+ acc x))
                 0.0
                 (double-array [1.0 2.0 3.0 4.0 5.0]))))))

(deftest adouble-map-test
  (testing "double each element"
    (is (= [2.0 4.0 6.0]
           (vec (arr/adouble-map (fn ^double [^double x] (* x 2.0))
                                 (double-array [1.0 2.0 3.0])))))))

(deftest adouble-filter-test
  (testing "filter positive"
    (is (= [1.0 2.0 3.0]
           (vec (arr/adouble-filter pos? (double-array [-1.0 1.0 -2.0 2.0 3.0])))))))

(deftest adouble-sum-test
  (testing "basic sum"
    (is (= 6.0 (arr/adouble-sum (double-array [1.0 2.0 3.0]))))))

(deftest adouble-fold-test
  (testing "parallel fold equals sequential reduce"
    (let [arr (double-array (range 1000))]
      (is (== (arr/adouble-reduce + 0.0 arr)
              (arr/adouble-fold + 0.0 + arr))))))

;; =============================================================================
;; Int Array Tests
;; =============================================================================

(deftest aint-reduce-test
  (testing "basic reduction"
    (is (= 15 (arr/aint-reduce + 0 (int-array [1 2 3 4 5]))))))

(deftest aint-map-test
  (testing "double each element"
    (is (= [2 4 6]
           (vec (arr/aint-map (fn [^long x] (* x 2))
                              (int-array [1 2 3])))))))

(deftest aint-filter-test
  (testing "filter even"
    (is (= [2 4]
           (vec (arr/aint-filter even? (int-array [1 2 3 4 5])))))))

(deftest aint-sum-test
  (testing "basic sum"
    (is (= 15 (arr/aint-sum (int-array [1 2 3 4 5]))))))

;; =============================================================================
;; Float Array Tests
;; =============================================================================

(deftest afloat-reduce-test
  (testing "basic reduction"
    (is (== 6.0 (arr/afloat-reduce + 0.0 (float-array [1.0 2.0 3.0]))))))

(deftest afloat-map-test
  (testing "double each element"
    (let [result (arr/afloat-map (fn ^double [^double x] (* x 2.0))
                                 (float-array [1.0 2.0 3.0]))]
      (is (= [2.0 4.0 6.0] (mapv double result))))))

(deftest afloat-sum-test
  (testing "basic sum"
    (is (== 6.0 (arr/afloat-sum (float-array [1.0 2.0 3.0]))))))

;; =============================================================================
;; Byte Array Tests
;; =============================================================================

(deftest abyte-reduce-test
  (testing "basic reduction"
    (is (= 15 (arr/abyte-reduce + 0 (byte-array [1 2 3 4 5]))))))

(deftest abyte-map-test
  (testing "double each element"
    (is (= [2 4 6]
           (vec (arr/abyte-map (fn [^long x] (* x 2))
                               (byte-array [1 2 3])))))))

;; =============================================================================
;; Short Array Tests
;; =============================================================================

(deftest ashort-reduce-test
  (testing "basic reduction"
    (is (= 15 (arr/ashort-reduce + 0 (short-array [1 2 3 4 5]))))))

(deftest ashort-map-test
  (testing "double each element"
    (is (= [2 4 6]
           (vec (arr/ashort-map (fn [^long x] (* x 2))
                                (short-array [1 2 3])))))))

;; =============================================================================
;; Char Array Tests
;; =============================================================================

(deftest achar-reduce-test
  (testing "build string from chars"
    (is (= "abc"
           (arr/achar-reduce (fn [acc c] (str acc c))
                             ""
                             (char-array [\a \b \c]))))))

(deftest achar-map-test
  (testing "uppercase"
    (is (= [\A \B \C]
           (vec (arr/achar-map #(Character/toUpperCase %)
                               (char-array [\a \b \c])))))))

(deftest achar-filter-test
  (testing "filter vowels"
    (is (= [\a \e \i]
           (vec (arr/achar-filter #(#{\a \e \i \o \u} %)
                                  (char-array [\a \b \c \d \e \f \g \h \i])))))))

;; =============================================================================
;; Boolean Array Tests
;; =============================================================================

(deftest abool-all?-test
  (testing "all true"
    (is (true? (arr/abool-all? (boolean-array [true true true])))))
  (testing "not all true"
    (is (false? (arr/abool-all? (boolean-array [true false true])))))
  (testing "empty"
    (is (true? (arr/abool-all? (boolean-array []))))))

(deftest abool-any?-test
  (testing "some true"
    (is (true? (arr/abool-any? (boolean-array [false false true])))))
  (testing "none true"
    (is (false? (arr/abool-any? (boolean-array [false false false])))))
  (testing "empty"
    (is (false? (arr/abool-any? (boolean-array []))))))

(deftest abool-count-true-test
  (testing "count trues"
    (is (= 3 (arr/abool-count-true (boolean-array [true false true false true]))))))

(deftest abool-map-test
  (testing "negate all"
    (is (= [false true false]
           (vec (arr/abool-map not (boolean-array [true false true])))))))

;; =============================================================================
;; Cross-type Conversion Tests
;; =============================================================================

(deftest along->double-test
  (testing "convert longs to doubles"
    (is (= [1.0 2.0 3.0]
           (vec (arr/along->double (long-array [1 2 3])))))))

(deftest adouble->long-test
  (testing "convert doubles to longs (truncates)"
    (is (= [1 2 3]
           (vec (arr/adouble->long (double-array [1.5 2.7 3.9])))))))

(deftest aint->long-test
  (testing "convert ints to longs"
    (is (= [1 2 3]
           (vec (arr/aint->long (int-array [1 2 3])))))))

(deftest along->int-test
  (testing "convert longs to ints"
    (is (= [1 2 3]
           (vec (arr/along->int (long-array [1 2 3])))))))

;; =============================================================================
;; Array Creation Tests
;; =============================================================================

(deftest along-range-test
  (testing "basic range"
    (is (= [0 1 2 3 4]
           (vec (arr/along-range 0 5)))))
  (testing "start from non-zero"
    (is (= [5 6 7 8 9]
           (vec (arr/along-range 5 10))))))

(deftest adouble-range-test
  (testing "basic range"
    (is (= [0.0 0.5 1.0 1.5]
           (vec (arr/adouble-range 0.0 2.0 0.5))))))

(deftest along-repeat-test
  (testing "repeat value"
    (is (= [42 42 42 42 42]
           (vec (arr/along-repeat 5 42))))))

(deftest adouble-repeat-test
  (testing "repeat value"
    (is (= [3.14 3.14 3.14]
           (vec (arr/adouble-repeat 3 3.14))))))

;; =============================================================================
;; Zipping Operations Tests
;; =============================================================================

(deftest along-zip-with-test
  (testing "add element-wise"
    (is (= [5 7 9]
           (vec (arr/along-zip-with + (long-array [1 2 3]) (long-array [4 5 6]))))))
  (testing "different lengths uses minimum"
    (is (= [5 7]
           (vec (arr/along-zip-with + (long-array [1 2 3]) (long-array [4 5])))))))

(deftest adouble-zip-with-test
  (testing "multiply element-wise"
    (is (= [4.0 10.0 18.0]
           (vec (arr/adouble-zip-with * (double-array [1.0 2.0 3.0])
                                      (double-array [4.0 5.0 6.0])))))))

(deftest along-dot-test
  (testing "dot product"
    (is (= 32 (arr/along-dot (long-array [1 2 3]) (long-array [4 5 6]))))))
    ;; 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32

(deftest adouble-dot-test
  (testing "dot product"
    (is (== 32.0 (arr/adouble-dot (double-array [1.0 2.0 3.0])
                                  (double-array [4.0 5.0 6.0]))))))
