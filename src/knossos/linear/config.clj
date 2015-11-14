(ns knossos.linear.config
  "Datatypes for search configurations"
  (:require [clojure.string :as str]
            [clojure.core.reducers :as r]
            [potemkin :refer [definterface+ deftype+ defrecord+]]
            [knossos [core :as core]
                     [util :refer :all]
                     [op :as op :refer [Op Invoke OK Info Fail]]])
    (:import knossos.core.Model
             java.util.Arrays
             java.util.Set
             java.util.HashSet))

;; An immutable map of process ids to whether they are calling or returning an
;; op, augmented with a mutable union-find memoized equality test.

(definterface+ Processes
  (calls      "A reducible of called but unlinearized operations."      [ps])

  (call       "Adds an operation being called with the calling state."  [ps op])
  (linearize  "Changes the given operation from calling to returning."  [ps op])
  (return     "Removes an operation from the returned set."             [ps op])

  (idle?      "Is this process doing nothing?"                          [ps p])
  (calling?   "Is this process currently calling an operation?"         [ps p])
  (returning? "Is this process returning an operation?"                 [ps p]))

; Maybe later
; (def ^:const idle      0)
; (def ^:const calling   1)
; (def ^:const returning 2)

;; A silly implementation based on two Clojure maps.
(defrecord MapProcesses [calls rets]
  Processes
  (calls [ps]
    (vals calls))

  (call [ps op]
    (let [p (:process op)]
      (assert (idle? ps p))
      (MapProcesses. (assoc calls p op) rets)))

  (linearize [ps op]
    (let [p (:process op)]
      (assert (calling? ps p))
      (MapProcesses. (dissoc calls p) (assoc rets p op))))

  (return [ps op]
    (let [p (:process op)]
      (assert (returning? ps p))
      (MapProcesses. calls (dissoc rets p))))

  (idle?      [ps p] (not (or (contains? calls p)
                              (contains? rets  p))))
  (calling?   [ps p] (contains? calls p))
  (returning? [ps p] (contains? rets p)))

(defn map-processes
  "A Processes tracker based on Clojure maps."
  []
  (MapProcesses. {} {}))

(deftype MemoizedMapProcesses [callsMap
                               retsMap
                               ^:volatile-mutable ^int hasheq
                               ^:volatile-mutable ^int hashcode]
  Processes
  (calls [ps]
    (vals callsMap))

  (call [ps op]
    (let [p (:process op)]
      (assert (idle? ps p))
      (MemoizedMapProcesses. (assoc callsMap p op) retsMap -1 -1)))

  (linearize [ps op]
    (let [p (:process op)]
      (assert (calling? ps p))
      (MemoizedMapProcesses. (dissoc callsMap p) (assoc retsMap p op) -1 -1)))

  (return [ps op]
    (let [p (:process op)]
      (assert (returning? ps p))
      (MemoizedMapProcesses. callsMap (dissoc retsMap p) -1 -1)))

  (idle?      [ps p] (not (or (contains? callsMap p)
                              (contains? retsMap  p))))
  (calling?   [ps p] (contains? callsMap p))
  (returning? [ps p] (contains? retsMap  p))

  ; I'm assuming calls and rets will never be identical since we move ops
  ; atomically from one to the other, so we shouuuldn't see collisions here?
  ; Maye xor isn't good enough though. Might back off to murmur3.
  clojure.lang.IHashEq
  (hasheq [ps]
    (when (= -1 hasheq)
      (set! hasheq (int (bit-xor (hash callsMap) (hash retsMap)))))
    hasheq)

  Object
  (hashCode [ps]
    (when (= -1 hashcode)
      (set! hashcode (int (bit-xor (.hashCode callsMap) (.hashCode retsMap)))))
    hashcode)

  (equals [this other]
    (or (identical? this other)
        (and (instance? MemoizedMapProcesses other)
             (= (.hashCode this) (.hashCode other))
             (.equals callsMap (.callsMap ^MemoizedMapProcesses other))
             (.equals retsMap  (.retsMap  ^MemoizedMapProcesses other))))))

(defn memoized-map-processes
  "A Processes tracker based on Clojure maps, with memoized hashcode for faster
  hashing and equality."
  []
  (MemoizedMapProcesses. {} {} -1 -1))

; A thousand processes with 32-bit indexes pointing to the operations they
; represent can fit in an array of 4 KB (plus 12 bytes overhead), and be
; compared for equality with no memory indirection. Almost all of those
; processes are likely idle, however--wasted storage.

; A Clojure map with ~8 entries will use an ArrayMap--that's 16*64 bits + 12
; bytes overhead for the array, 8 bytes for the ArrayMap overhead, and each
; Long referred to is 12 bytes overhead + 8 bytes value, for 320 bytes total.
; That's a combined 852 bytes.

; The array representation is ~5x larger, but doesn't require ~10 pointer
; derefs, which might make it easier on caches and pipelines.

; Could we compress the array representation? We could, for instance, take an
; integer array and stripe alternating pairs of process ID and op ID across it,
; sorted by process ID, then use binary search or linear scan to extract the op
; for a given key. That'd pack 8 entries into 12 + ((4 + 4) * 8) = 76 bytes,
; and no pointer indirection for comparison.

; Which of these structures is more expensive to manipulate? With arraymaps
; we're essentially rewriting the whole array structure *anyway*, and with more
; than 8 elements we promote to clojure.lang.APersistentMap, but we're still
; talking about rewriting 256-byte INode arrays at one or two levels of the
; HAMT, plus some minor pointer chasing. Oh AND we have to hash!

; Looking up pending operations *is* more expensive in this approach. We have
; to map the operation index to an actual operation, which is either an array
; lookup (cheap, v. cache-friendly), or a vector lookup (~10x slower but not
; bad). OTOH, iteration over pending ops is reasonably cheap; just read every
; other element from the array and produce a reducible of O(n) or O(log32 n)
; fetches from the history. The history never changes so it should be more
; cache-friendly than the Processes trackers, which mutate *constantly*.

; We'll encode pending ops as positive integers, and returning ops as their
; negative complements, so 0 -> -1, 1 -> -2, 2 -> -3, ...

(defn array-processes-search
  "Given a process ID and an int array like [process-id op-id process-id op-id
  ....], finds the array index of this process ID. (inc idx) will give the
  index of the corresponding process ID. If the process is *not* present,
  returns (dec (- insertion-point)), where insertion point is where the process
  index *would* be, after insertion into the array."
  ([^ints a ^long process]
   (loop [low       0
          ^int high (dec (/ (alength a) 2))]
     (if (> low high)
       (dec (* -2 low))
       (let [mid     (quot (+ low high) 2)
             mid-val (aget a (* 2 mid))]
         (cond (< mid-val process)  (recur (inc mid)  high)
               (< process mid-val)  (recur low        (dec mid))
               true                 (* 2 mid)))))))

(defn array-processes-assoc
  "Given an array like [process-id op-id process-id op-id ...], an index where
  the process/op pair belong, a process id, and an op id, upserts the process
  id and op id into a copy of the array, maintaining sorted order."
  [^ints a ^long i ^long process ^long op]
  (if (neg? i)
    ; It's not in the array currently; insert it
    (let [i  (- (inc i))
          a' (int-array (+ 2 (alength a)))]
      ; Copy prefix, insert, copy postfix
      (System/arraycopy a 0 a' 0 i)
      (aset-int a' i       process)
      (aset-int a' (inc i) op)
      (System/arraycopy a i a' (+ i 2) (- (alength a) i))
      a')

    ; It's already in the array; just copy and overwrite those indices.
    (let [a' (int-array (alength a))]
      (System/arraycopy a 0 a' 0 (alength a))
      (aset-int a' i       process)
      (aset-int a' (inc i) op)
      a')))

(defn array-processes-dissoc
  "Like array-processes-assoc, but deletes the elements at i and i+1, returning
  a copy of the array without them."
  [^ints a ^long i]
  (let [a' (int-array (- (alength a) 2))]
    (System/arraycopy a 0       a' 0 i)
    (System/arraycopy a (+ i 2) a' i (- (alength a') i))
    a'))

(deftype ArrayProcesses [history ^ints a]
  Processes
  (calls [ps]
    (->> (range 1 (alength a) 2)
         (rkeep (fn [i]
                  (let [op-index (aget a i)]
                    (when-not (neg? op-index)
                      (nth history op-index)))))))

  (call [ps op]
    (let [p  (:process op)
          op (:index op)
          i  (array-processes-search a p)]
      ; The process should not be present yet.
      (assert (neg? i))
      (assert (integer? op))
      (ArrayProcesses. history (array-processes-assoc a i p op))))

  (linearize [ps op]
    (let [p  (:process op)
          op (:index op)
          i  (array-processes-search a p)]
      ; The process should be present and being called.
      (assert (not (neg? i)))
      (assert (not (neg? (aget a (inc i)))))
      (ArrayProcesses. history (array-processes-assoc a i p (dec (- op))))))

  (return [ps op]
    (let [p  (:process op)
          op (:index op)
          i  (array-processes-search a p)]
      ; The process should be present and returning.
      (assert (not (neg? i)))
      (assert (neg? (aget a (inc i))))
      (ArrayProcesses. history (array-processes-dissoc a i))))

  (idle? [ps p]
    (neg? (array-processes-search a p)))

  (calling? [ps p]
    (let [i (array-processes-search a p)]
      (and (not (neg? i))
           (not (neg? (aget a (inc i)))))))

  (returning? [ps p]
    (let [i (array-processes-search a p)]
      (and (not (neg? i))
           (neg? (aget a (inc i))))))

  Object
  (hashCode [ps]
    (Arrays/hashCode a))

  (equals [ps other]
    (and (instance? ArrayProcesses other)
         (Arrays/equals a ^ints (.a ^ArrayProcesses other)))))

(defn array-processes
  "A process tracker backed by a sorted array, closing over the given history.
  History operations must have :index elements identifying their position in
  the history, and integer :process fields."
  [history]
  (assert (every? integer? (map :process (remove op/info? history))))
  (assert (every? integer? (map :index history)))
  (ArrayProcesses. history (int-array 0)))

; One particular path through the history, comprised of a model and a tracker
; for process states.

(defrecord Config [model processes])

(defn config
  "An initial configuration around a given model and history."
  [model history]
  (Config. model (array-processes history)))

;; Non-threadsafe mutable configuration sets

(definterface+ ConfigSet
  (add! "Add a configuration to a config-set. Returns self.
        You do not need to preserve the return value."
        [config-set config]))

(deftype SetConfigSet [^:unsynchronized-mutable ^Set s]
  ConfigSet
  (add! [this config]
    (.add s config)
    this)

  clojure.lang.Counted
  (count [this] (.size s))

  clojure.lang.Seqable
  (seq [this] (seq s))

  Object
  (toString [this]
    (str "#{" (->> this
                   seq
                   (str/join #", "))
         "}")))

(defmethod print-method SetConfigSet [x ^java.io.Writer w]
  (.write w (str x)))

(defn set-config-set
  "An empty set-backed config set, or one backed by a collection."
  ([] (SetConfigSet. (HashSet.)))
  ([coll] (reduce add! (set-config-set) coll)))
