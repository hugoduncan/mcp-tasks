(ns mcp-tasks.arrays
  "Primitive array operations with support for primitive functions.

   Provides fold, reduce, map, filter, and other operations over primitive
   arrays without boxing overhead when used with primitive function interfaces.

   Example usage:
     ;; Reduce with primitive function (no boxing)
     (along-reduce (fn ^long [^long acc ^long x] (+ acc x)) 0 arr)

     ;; Fold with parallel reduction
     (along-fold + 0 + arr)

     ;; Map to new array
     (along-map (fn ^long [^long x] (* x 2)) arr)

   Supported primitive types: long, double, int, float, byte, short, char, boolean")

;; =============================================================================
;; Long Arrays
;; =============================================================================

(defn along-reduce
  "Reduce over a long array using a primitive function.
   f should be (fn ^long [^long acc ^long x] ...) for best performance."
  ^long [f ^long init ^longs arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (long (f acc (aget arr i))))
        acc))))

(defn along-reduce-idx
  "Reduce over a long array with index.
   f should be (fn ^long [^long acc ^long idx ^long x] ...)."
  ^long [f ^long init ^longs arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (long (f acc i (aget arr i))))
        acc))))

(defn along-fold
  "Parallel fold over a long array.
   combinef combines results from parallel chunks.
   reducef reduces within a chunk.
   opts is an optional map with :chunk-size (default 512)."
  (^long [combinef ^long init reducef ^longs arr]
   (along-fold combinef init reducef nil arr))
  ([combinef init reducef opts ^longs arr]
   (let [n (long (or (:chunk-size opts) 512))
         init (long init)
         len (alength arr)]
     (if (<= len n)
       ;; Small array - just reduce
       (along-reduce reducef init arr)
       ;; Large array - parallel fold
       (let [chunks (quot len n)
             remainder (rem len n)
             futures (vec
                      (for [chunk (range chunks)]
                        (let [start (* chunk n)
                              end (+ start n)
                              chunk-arr (java.util.Arrays/copyOfRange arr (int start) (int end))]
                          (future (along-reduce reducef init chunk-arr)))))]
         ;; Combine chunk results
         (let [chunk-result (reduce (fn [^long acc fut]
                                      (long (combinef acc (long @fut))))
                                    init
                                    futures)]
           ;; Handle remainder
           (if (pos? remainder)
             (let [start (* chunks n)
                   rem-arr (java.util.Arrays/copyOfRange arr (int start) (int len))]
               (long (combinef chunk-result (along-reduce reducef init rem-arr))))
             chunk-result)))))))

(defn along-map
  "Map a function over a long array, returning a new long array.
   f should be (fn ^long [^long x] ...) for best performance."
  ^longs [f ^longs arr]
  (let [len (alength arr)
        result (long-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (long (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn along-map-idx
  "Map a function with index over a long array.
   f should be (fn ^long [^long idx ^long x] ...)."
  ^longs [f ^longs arr]
  (let [len (alength arr)
        result (long-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (long (f i (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn along-filter
  "Filter a long array, returning a new long array with matching elements.
   pred should be (fn ^boolean [^long x] ...) or any predicate."
  ^longs [pred ^longs arr]
  (let [len (alength arr)
        ;; First pass: count matches
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (long-array match-count)]
    ;; Second pass: fill result
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

(defn along-some
  "Returns the first element in arr for which pred returns true, or nil."
  [pred ^longs arr]
  (let [len (alength arr)]
    (loop [i 0]
      (when (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            v
            (recur (unchecked-inc i))))))))

(defn along-every?
  "Returns true if pred returns true for every element in arr."
  [pred ^longs arr]
  (let [len (alength arr)]
    (loop [i 0]
      (if (< i len)
        (if (pred (aget arr i))
          (recur (unchecked-inc i))
          false)
        true))))

(defn along-count
  "Count elements matching predicate."
  ^long [pred ^longs arr]
  (let [len (alength arr)]
    (loop [i 0
           cnt 0]
      (if (< i len)
        (recur (unchecked-inc i)
               (if (pred (aget arr i))
                 (unchecked-inc cnt)
                 cnt))
        cnt))))

(defn along-sum
  "Sum all elements in a long array."
  ^long [^longs arr]
  (along-reduce (fn ^long [^long a ^long b] (unchecked-add a b)) 0 arr))

(defn along-min
  "Find minimum element in a long array. Returns Long/MAX_VALUE for empty arrays."
  ^long [^longs arr]
  (along-reduce (fn ^long [^long a ^long b] (if (< a b) a b)) Long/MAX_VALUE arr))

(defn along-max
  "Find maximum element in a long array. Returns Long/MIN_VALUE for empty arrays."
  ^long [^longs arr]
  (along-reduce (fn ^long [^long a ^long b] (if (> a b) a b)) Long/MIN_VALUE arr))

;; =============================================================================
;; Double Arrays
;; =============================================================================

(defn adouble-reduce
  "Reduce over a double array using a primitive function.
   f should be (fn ^double [^double acc ^double x] ...) for best performance."
  ^double [f ^double init ^doubles arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (double (f acc (aget arr i))))
        acc))))

(defn adouble-reduce-idx
  "Reduce over a double array with index.
   f should be (fn ^double [^double acc ^long idx ^double x] ...)."
  ^double [f ^double init ^doubles arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (double (f acc i (aget arr i))))
        acc))))

(defn adouble-fold
  "Parallel fold over a double array.
   opts is an optional map with :chunk-size (default 512)."
  (^double [combinef ^double init reducef ^doubles arr]
   (adouble-fold combinef init reducef nil arr))
  ([combinef init reducef opts ^doubles arr]
   (let [n (long (or (:chunk-size opts) 512))
         init (double init)
         len (alength arr)]
     (if (<= len n)
       (adouble-reduce reducef init arr)
       (let [chunks (quot len n)
             remainder (rem len n)
             futures (vec
                      (for [chunk (range chunks)]
                        (let [start (* chunk n)
                              end (+ start n)
                              chunk-arr (java.util.Arrays/copyOfRange arr (int start) (int end))]
                          (future (adouble-reduce reducef init chunk-arr)))))]
         (let [chunk-result (reduce (fn [^double acc fut]
                                      (double (combinef acc (double @fut))))
                                    init
                                    futures)]
           (if (pos? remainder)
             (let [start (* chunks n)
                   rem-arr (java.util.Arrays/copyOfRange arr (int start) (int len))]
               (double (combinef chunk-result (adouble-reduce reducef init rem-arr))))
             chunk-result)))))))

(defn adouble-map
  "Map a function over a double array, returning a new double array.
   f should be (fn ^double [^double x] ...) for best performance."
  ^doubles [f ^doubles arr]
  (let [len (alength arr)
        result (double-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (double (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn adouble-map-idx
  "Map a function with index over a double array.
   f should be (fn ^double [^long idx ^double x] ...)."
  ^doubles [f ^doubles arr]
  (let [len (alength arr)
        result (double-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (double (f i (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn adouble-filter
  "Filter a double array, returning a new double array with matching elements."
  ^doubles [pred ^doubles arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (double-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

(defn adouble-some
  "Returns the first element in arr for which pred returns true, or nil."
  [pred ^doubles arr]
  (let [len (alength arr)]
    (loop [i 0]
      (when (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            v
            (recur (unchecked-inc i))))))))

(defn adouble-every?
  "Returns true if pred returns true for every element in arr."
  [pred ^doubles arr]
  (let [len (alength arr)]
    (loop [i 0]
      (if (< i len)
        (if (pred (aget arr i))
          (recur (unchecked-inc i))
          false)
        true))))

(defn adouble-count
  "Count elements matching predicate."
  ^long [pred ^doubles arr]
  (let [len (alength arr)]
    (loop [i 0
           cnt 0]
      (if (< i len)
        (recur (unchecked-inc i)
               (if (pred (aget arr i))
                 (unchecked-inc cnt)
                 cnt))
        cnt))))

(defn adouble-sum
  "Sum all elements in a double array."
  ^double [^doubles arr]
  (adouble-reduce (fn ^double [^double a ^double b] (+ a b)) 0.0 arr))

(defn adouble-min
  "Find minimum element in a double array."
  ^double [^doubles arr]
  (adouble-reduce (fn ^double [^double a ^double b] (if (< a b) a b)) Double/MAX_VALUE arr))

(defn adouble-max
  "Find maximum element in a double array."
  ^double [^doubles arr]
  (adouble-reduce (fn ^double [^double a ^double b] (if (> a b) a b)) Double/MIN_VALUE arr))

;; =============================================================================
;; Int Arrays
;; =============================================================================

(defn aint-reduce
  "Reduce over an int array using a primitive function.
   f should be (fn ^long [^long acc ^long x] ...) for best performance."
  ^long [f ^long init ^ints arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (long (f acc (aget arr i))))
        acc))))

(defn aint-map
  "Map a function over an int array, returning a new int array.
   f should be (fn ^long [^long x] ...) - result is cast to int."
  ^ints [f ^ints arr]
  (let [len (alength arr)
        result (int-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (unchecked-int (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn aint-filter
  "Filter an int array, returning a new int array with matching elements."
  ^ints [pred ^ints arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (int-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

(defn aint-sum
  "Sum all elements in an int array (returns long to avoid overflow)."
  ^long [^ints arr]
  (aint-reduce (fn ^long [^long a ^long b] (unchecked-add a b)) 0 arr))

;; =============================================================================
;; Float Arrays
;; =============================================================================

(defn afloat-reduce
  "Reduce over a float array using a primitive function.
   f should be (fn ^double [^double acc ^double x] ...) for best performance."
  ^double [f ^double init ^floats arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (double (f acc (aget arr i))))
        acc))))

(defn afloat-map
  "Map a function over a float array, returning a new float array.
   f should be (fn ^double [^double x] ...) - result is cast to float."
  ^floats [f ^floats arr]
  (let [len (alength arr)
        result (float-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (unchecked-float (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn afloat-filter
  "Filter a float array, returning a new float array with matching elements."
  ^floats [pred ^floats arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (float-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

(defn afloat-sum
  "Sum all elements in a float array (returns double for precision)."
  ^double [^floats arr]
  (afloat-reduce (fn ^double [^double a ^double b] (+ a b)) 0.0 arr))

;; =============================================================================
;; Byte Arrays
;; =============================================================================

(defn abyte-reduce
  "Reduce over a byte array using a primitive function.
   f should be (fn ^long [^long acc ^long x] ...) for best performance."
  ^long [f ^long init ^bytes arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (long (f acc (aget arr i))))
        acc))))

(defn abyte-map
  "Map a function over a byte array, returning a new byte array.
   f should be (fn ^long [^long x] ...) - result is cast to byte."
  ^bytes [f ^bytes arr]
  (let [len (alength arr)
        result (byte-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (unchecked-byte (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn abyte-filter
  "Filter a byte array, returning a new byte array with matching elements."
  ^bytes [pred ^bytes arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (byte-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

;; =============================================================================
;; Short Arrays
;; =============================================================================

(defn ashort-reduce
  "Reduce over a short array using a primitive function."
  ^long [f ^long init ^shorts arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (long (f acc (aget arr i))))
        acc))))

(defn ashort-map
  "Map a function over a short array, returning a new short array."
  ^shorts [f ^shorts arr]
  (let [len (alength arr)
        result (short-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (unchecked-short (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn ashort-filter
  "Filter a short array, returning a new short array with matching elements."
  ^shorts [pred ^shorts arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (short-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

;; =============================================================================
;; Char Arrays
;; =============================================================================

(defn achar-reduce
  "Reduce over a char array. Chars are passed as-is to f."
  [f init ^chars arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (f acc (aget arr i)))
        acc))))

(defn achar-map
  "Map a function over a char array, returning a new char array.
   f should return a char."
  ^chars [f ^chars arr]
  (let [len (alength arr)
        result (char-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (char (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn achar-filter
  "Filter a char array, returning a new char array with matching elements."
  ^chars [pred ^chars arr]
  (let [len (alength arr)
        match-count (loop [i 0
                           cnt 0]
                      (if (< i len)
                        (recur (unchecked-inc i)
                               (if (pred (aget arr i))
                                 (unchecked-inc cnt)
                                 cnt))
                        cnt))
        result (char-array match-count)]
    (loop [i 0
           j 0]
      (if (< i len)
        (let [v (aget arr i)]
          (if (pred v)
            (do
              (aset result j v)
              (recur (unchecked-inc i) (unchecked-inc j)))
            (recur (unchecked-inc i) j)))
        result))))

;; =============================================================================
;; Boolean Arrays
;; =============================================================================

(defn abool-reduce
  "Reduce over a boolean array."
  [f init ^booleans arr]
  (let [len (alength arr)]
    (loop [i 0
           acc init]
      (if (< i len)
        (recur (unchecked-inc i) (f acc (aget arr i)))
        acc))))

(defn abool-map
  "Map a function over a boolean array, returning a new boolean array."
  ^booleans [f ^booleans arr]
  (let [len (alength arr)
        result (boolean-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (boolean (f (aget arr i))))
          (recur (unchecked-inc i)))
        result))))

(defn abool-all?
  "Returns true if all elements in the boolean array are true."
  [^booleans arr]
  (let [len (alength arr)]
    (loop [i 0]
      (if (< i len)
        (if (aget arr i)
          (recur (unchecked-inc i))
          false)
        true))))

(defn abool-any?
  "Returns true if any element in the boolean array is true."
  [^booleans arr]
  (let [len (alength arr)]
    (loop [i 0]
      (if (< i len)
        (if (aget arr i)
          true
          (recur (unchecked-inc i)))
        false))))

(defn abool-count-true
  "Count the number of true values in a boolean array."
  ^long [^booleans arr]
  (let [len (alength arr)]
    (loop [i 0
           cnt 0]
      (if (< i len)
        (recur (unchecked-inc i)
               (if (aget arr i)
                 (unchecked-inc cnt)
                 cnt))
        cnt))))

;; =============================================================================
;; Cross-type Operations
;; =============================================================================

(defn along->double
  "Convert a long array to a double array."
  ^doubles [^longs arr]
  (let [len (alength arr)
        result (double-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (double (aget arr i)))
          (recur (unchecked-inc i)))
        result))))

(defn adouble->long
  "Convert a double array to a long array (truncates)."
  ^longs [^doubles arr]
  (let [len (alength arr)
        result (long-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (long (aget arr i)))
          (recur (unchecked-inc i)))
        result))))

(defn aint->long
  "Convert an int array to a long array."
  ^longs [^ints arr]
  (let [len (alength arr)
        result (long-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (long (aget arr i)))
          (recur (unchecked-inc i)))
        result))))

(defn along->int
  "Convert a long array to an int array (may overflow)."
  ^ints [^longs arr]
  (let [len (alength arr)
        result (int-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (unchecked-int (aget arr i)))
          (recur (unchecked-inc i)))
        result))))

;; =============================================================================
;; Array Creation Helpers
;; =============================================================================

(defn along-range
  "Create a long array containing values from start (inclusive) to end (exclusive)."
  ^longs [^long start ^long end]
  (let [len (- end start)
        result (long-array len)]
    (loop [i 0
           v start]
      (if (< i len)
        (do
          (aset result i v)
          (recur (unchecked-inc i) (unchecked-inc v)))
        result))))

(defn adouble-range
  "Create a double array containing values from start to end with given step."
  ^doubles [^double start ^double end ^double step]
  (let [len (long (Math/ceil (/ (- end start) step)))
        result (double-array len)]
    (loop [i 0
           v start]
      (if (< i len)
        (do
          (aset result i v)
          (recur (unchecked-inc i) (+ v step)))
        result))))

(defn along-repeat
  "Create a long array of n elements, all set to value."
  ^longs [^long n ^long value]
  (let [result (long-array n)]
    (java.util.Arrays/fill result value)
    result))

(defn adouble-repeat
  "Create a double array of n elements, all set to value."
  ^doubles [^long n ^double value]
  (let [result (double-array n)]
    (java.util.Arrays/fill result value)
    result))

;; =============================================================================
;; Zipping Operations
;; =============================================================================

(defn along-zip-with
  "Combine two long arrays element-wise using function f.
   f should be (fn ^long [^long a ^long b] ...).
   Result length is the minimum of the two input lengths."
  ^longs [f ^longs arr1 ^longs arr2]
  (let [len (min (alength arr1) (alength arr2))
        result (long-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (long (f (aget arr1 i) (aget arr2 i))))
          (recur (unchecked-inc i)))
        result))))

(defn adouble-zip-with
  "Combine two double arrays element-wise using function f.
   f should be (fn ^double [^double a ^double b] ...).
   Result length is the minimum of the two input lengths."
  ^doubles [f ^doubles arr1 ^doubles arr2]
  (let [len (min (alength arr1) (alength arr2))
        result (double-array len)]
    (loop [i 0]
      (if (< i len)
        (do
          (aset result i (double (f (aget arr1 i) (aget arr2 i))))
          (recur (unchecked-inc i)))
        result))))

(defn along-dot
  "Compute dot product of two long arrays."
  ^long [^longs arr1 ^longs arr2]
  (let [len (min (alength arr1) (alength arr2))]
    (loop [i 0
           sum 0]
      (if (< i len)
        (recur (unchecked-inc i)
               (unchecked-add sum (unchecked-multiply (aget arr1 i) (aget arr2 i))))
        sum))))

(defn adouble-dot
  "Compute dot product of two double arrays."
  ^double [^doubles arr1 ^doubles arr2]
  (let [len (min (alength arr1) (alength arr2))]
    (loop [i 0
           sum 0.0]
      (if (< i len)
        (recur (unchecked-inc i)
               (+ sum (* (aget arr1 i) (aget arr2 i))))
        sum))))
