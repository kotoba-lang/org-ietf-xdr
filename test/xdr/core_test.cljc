(ns xdr.core-test
  "Expectations are byte strings produced independently — Python `struct`
  big-endian packing plus the RFC's padding rule, written out by hand — not
  by the encoder under test. The stronger oracle arrives one layer up: the
  bytes macOS's own NFS client sends, pinned in `nfs`."
  (:require [clojure.test :refer [deftest is testing]]
            [xdr.core :as xdr]))

(defn- unhex [s]
  (xdr/->bytes
   (mapv (fn [pair]
           (let [t (apply str pair)]
             #?(:clj (Integer/parseInt t 16) :cljs (js/parseInt t 16))))
         (partition 2 s))))

(defn- round [schema value]
  (xdr/decode-value schema (xdr/encode schema value)))

;; ── primitives ────────────────────────────────────────────────────────────

(deftest primitives-match-the-wire
  (doseq [[schema value hex]
          [[:int -1 "ffffffff"]
           [:int 2147483647 "7fffffff"]
           [:uint 4294967295 "ffffffff"]
           [:bool true "00000001"]
           [:bool false "00000000"]
           [:uhyper 8589934599 "0000000200000007"]
           [:hyper -2 "fffffffffffffffe"]]]
    (testing (str schema " " value)
      (is (= hex (xdr/hexify (xdr/encode schema value))))
      (is (= value (xdr/decode-value schema (unhex hex)))))))

;; ── the padding rule, which is where decoders go wrong ────────────────────

(deftest opaque-and-strings-are-padded-to-four
  (doseq [[schema value hex]
          [[[:opaque* 64] "hello" "0000000568656c6c6f000000"]
           [[:opaque*] "" "00000000"]
           [[:string 255] "kotoba" "000000066b6f746f62610000"]
           [[:string 255] "あ" "00000003e3818200"]]]
    (testing (pr-str value)
      (is (= hex (xdr/hexify (xdr/encode schema value)))))))

(deftest fixed-opaque-is-padded-but-its-length-is-not-on-the-wire
  (is (= "0001020304050607" (xdr/hexify (xdr/encode [:opaque 8] (range 8)))))
  (is (= "0001020304000000" (xdr/hexify (xdr/encode [:opaque 5] (range 5))))))

(deftest padding-is-skipped-on-the-way-back
  (testing "the failure this exists to catch: a decoder that reads the
            length and the data but not the pad reads every later field
            from the wrong offset"
    (let [schema [:struct [:name [:string 255]] [:n [:uint]]]
          bytes (xdr/encode schema {:name "あ" :n 42})]
      (is (= {:name "あ" :n 42} (xdr/decode-value schema bytes)))
      (is (= 12 (:end (xdr/decode schema bytes)))
          "4 length + 3 data + 1 pad + 4 uint"))))

;; ── composites ────────────────────────────────────────────────────────────

(deftest arrays
  (is (= "00000003000000010000000200000003"
         (xdr/hexify (xdr/encode [:array* [:uint]] [1 2 3]))))
  (is (= [1 2 3] (round [:array* [:uint]] [1 2 3])))
  (is (= [7 8] (round [:array [:uint] 2] [7 8])))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (xdr/encode [:array [:uint] 2] [7 8 9]))))

(deftest optional-data-is-a-boolean-then-the-value
  (is (= "00000000" (xdr/hexify (xdr/encode [:optional [:uint]] nil))))
  (is (= "0000000100000009" (xdr/hexify (xdr/encode [:optional [:uint]] 9))))
  (is (nil? (round [:optional [:uint]] nil)))
  (is (= 9 (round [:optional [:uint]] 9))))

(deftest unions-select-an-arm-and-void-is-an-arm
  (let [schema [:union [:enum] {0 [:struct [:size [:uhyper]]]
                                :default :void}]]
    (is (= {:disc 0 :value {:size 5}}
           (round schema {:disc 0 :value {:size 5}})))
    (is (= {:disc 13 :value nil}
           (round schema {:disc 13 :value nil})))
    (is (= "0000000d" (xdr/hexify (xdr/encode schema {:disc 13 :value nil})))
        "a failure arm is the discriminant and nothing else")))

(deftest structs-are-concatenation
  (let [schema [:struct
                [:status [:enum]]
                [:handle [:opaque* 64]]
                [:name [:string 255]]]
        v {:status 0 :handle (xdr/->bytes [1 2 3]) :name "x"}
        out (round schema v)]
    (is (= 0 (:status out)))
    (is (= "x" (:name out)))
    (is (= [1 2 3] (mapv #(bit-and (int %) 0xff) (seq (:handle out)))))))

;; ── refusals ──────────────────────────────────────────────────────────────

(deftest declared-maximums-are-enforced
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (xdr/encode [:string 3] "toolong")))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (xdr/decode-value [:string 3] (xdr/encode [:string 255] "toolong")))))

(deftest a-truncated-message-is-refused-not-guessed
  (testing "a short read must not decode to a plausible value — that is how
            a stream framing bug becomes a filesystem corruption bug"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (xdr/decode-value [:uhyper] (unhex "00000001"))))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (xdr/decode-value [:opaque* 64] (unhex "0000000a68656c"))))))

(deftest large-offsets-survive-the-32-bit-boundary
  (testing "file offsets above 4 GiB — where a 32-bit shift silently wraps"
    (doseq [n [4294967296 4294967297 8589934592 1099511627776]]
      (is (= n (round [:uhyper] n))))))
