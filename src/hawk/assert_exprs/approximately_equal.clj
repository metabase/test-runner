(ns hawk.assert-exprs.approximately-equal
  "See documentation in `docs/approximately-equal.md`."
  (:require
   [clojure.algo.generic.math-functions :as algo.generic.math]
   [clojure.pprint :as pprint]
   [methodical.core :as methodical]
   [schema.core :as s]))

(set! *warn-on-reflection* true)

#_{:clj-kondo/ignore [:dynamic-var-not-earmuffed]}
(methodical/defmulti ^:dynamic =?-diff
  "Multimethod to use to diff two things with `=?`. Despite not having earmuffs, this is dynamic so it can be rebound at
  runtime."
  {:arglists '([expected actual])}
  (fn [expected actual]
    [(type expected) (type actual)]))

(defn- add-primary-methods
  "Add primary methods in map `m` of dispatch value -> method fn to [[*impl*]]. Return a new multifn with those methods
  added."
  [m]
  (reduce
   (fn [multifn [dispatch-value f]]
     (methodical/add-primary-method multifn dispatch-value f))
   =?-diff
   m))

(def ^:dynamic *debug*
  "Whether to enable Methodical method tracing for debug purposes."
  false)

(defn =?-diff*
  "Are `expected` and `actual` 'approximately' equal to one another?"
  ([expected actual]
   (=?-diff* =?-diff expected actual))

  ([diff-fn expected actual]
   (let [diff-fn (if (map? diff-fn)
                   (add-primary-methods diff-fn)
                   diff-fn)]
     (binding [=?-diff diff-fn]
       (if *debug*
         (methodical/trace diff-fn expected actual)
         (diff-fn expected actual))))))

;;;; Default method impls

(methodical/defmethod =?-diff :default
  [expected actual]
  (when-not (= expected actual)
    (list 'not= expected actual)))

(methodical/defmethod =?-diff [Class Object]
  [expected-class actual]
  (when-not (instance? expected-class actual)
    (list 'not (list 'instance? expected-class actual))))

(methodical/defmethod =?-diff [java.util.regex.Pattern String]
  [expected-regex s]
  (when-not (re-matches expected-regex s)
    (list 'not (list 're-matches expected-regex s))))

;;; two regexes should be treated as equal if they're the same pattern.
(methodical/defmethod =?-diff [java.util.regex.Pattern java.util.regex.Pattern]
  [expected actual]
  (when-not (= (str expected) (str actual))
    (list 'not= (list 'str expected) (list 'str actual))))

(methodical/defmethod =?-diff [clojure.lang.AFunction Object]
  [pred actual]
  (when-not (pred actual)
    (list 'not (list pred actual))))

(methodical/defmethod =?-diff [clojure.lang.Sequential clojure.lang.Sequential]
  [expected actual]
  (let [same-size? (= (count expected)
                      (count actual))]
    ;; diff items at each index, e.g. (=?-diff (first expected) (first actual)) then (=?-diff (second expected) (second
    ;; actual)) and so forth. Keep diffing until BOTH sequences are empty.
    (loop [diffs    []
           expected expected
           actual   actual]
      (if (and (empty? expected)
               (empty? actual))
        ;; If there are no more items then return the vector the diffs, if there were any
        ;; non-nil diffs, OR if the sequences were of different sizes. The diff between [1 2 nil] and [1 2]
        ;; in [[clojure.data/diff]] is [nil nil nil]; that's what we'll return in this situation too.
        (when (or (some some? diffs)
                  (not same-size?))
          diffs)
        ;; when there is at least element left in either `expected` or `actual`, diff the first item in each. If one of
        ;; these is empty, it will diff against `nil`, but that's ok, because we will still fail because `same-size?`
        ;; above will be false
        (let [this-diff (=?-diff (first expected) (first actual))]
          (recur (conj diffs this-diff) (rest expected) (rest actual)))))))

(methodical/defmethod =?-diff [clojure.lang.IPersistentMap clojure.lang.IPersistentMap]
  [expected-map actual-map]
  (not-empty (into {} (for [[k expected] expected-map
                            :let         [actual (get actual-map k (symbol "nil #_\"key is not present.\""))
                                          diff   (=?-diff expected actual)]
                            :when        diff]
                        [k diff]))))

(deftype Exactly [expected])

(defn read-exactly
  "Data reader for `#hawk/exactly`."
  [expected-form]
  (->Exactly (eval expected-form)))

(defmethod print-method Exactly
  [this writer]
  ((get-method print-dup Exactly) this writer))

(defmethod print-dup Exactly
  [^Exactly this ^java.io.Writer writer]
  (.write writer (format "#hawk/exactly %s" (pr-str (.expected this)))))

(defmethod pprint/simple-dispatch Exactly
  [^Exactly this]
  (pprint/pprint-logical-block
   :prefix "#hawk/exactly " :suffix nil
   (pprint/write-out (.expected this))))

(methodical/defmethod =?-diff [Exactly :default]
  [^Exactly this actual]
  (let [expected (.expected this)]
    (when-not (= expected actual)
      (list 'not (list '= (symbol "#hawk/exactly") expected actual)))))

(deftype Schema [schema])

(defn read-schema
  "Data reader for `#hawk/schema`."
  [schema-form]
  (->Schema (eval schema-form)))

(defmethod print-method Schema
  [this writer]
  ((get-method print-dup Schema) this writer))

(defmethod print-dup Schema
  [^Schema this ^java.io.Writer writer]
  (.write writer (format "#hawk/schema %s" (pr-str (.schema this)))))

(defmethod pprint/simple-dispatch Schema
  [^Schema this]
  (pprint/pprint-logical-block
   :prefix "#hawk/schema " :suffix nil
   (pprint/write-out (.schema this))))

(methodical/defmethod =?-diff [Schema :default]
  [^Schema this actual]
  (s/check (.schema this) actual))

(deftype Approx [expected epsilon])

(defn read-approx
  "Data reader for `#hawk/approx`."
  [form]
  (let [form (eval form)
        _ (assert (sequential? form) "Expected #hawk/approx [expected epsilon]")
        [expected epsilon] form]
    (assert (number? expected))
    (assert (number? epsilon))
    (->Approx expected epsilon)))

(defmethod print-method Approx
  [this writer]
  ((get-method print-dup Approx) this writer))

(defmethod print-dup Approx
  [^Approx this ^java.io.Writer writer]
  (.write writer (format "#hawk/approx %s" (pr-str [(.expected this) (.epsilon this)]))))

(defmethod pprint/simple-dispatch Approx
  [^Approx this]
  (pprint/pprint-logical-block
   :prefix "#hawk/approx " :suffix nil
   (pprint/write-out [(.expected this) (.epsilon this)])))

(methodical/defmethod =?-diff [Approx Number]
  [^Approx this actual]
  (let [expected (.expected this)
        epsilon  (.epsilon this)]
    (when-not (algo.generic.math/approx= expected actual epsilon)
      (list 'not (list 'approx= expected actual (symbol "#_epsilon") epsilon)))))
