(ns abracad.avro-test
  (:require [clojure.test :refer :all]
            [abracad.avro :as avro])
  (:import [java.io ByteArrayOutputStream]
           [java.net InetAddress]))

(defrecord Example [foo-foo bar])

(defrecord SubExample [^long baz])

(extend-type InetAddress
  avro/AvroSerializable
  (schema-name [_] "ip.address")
  (field-get [this field]
    (case field
      :address (.getAddress this)))
  (field-list [this] #{:address}))

(defn ->InetAddress
  [address] (InetAddress/getByAddress address))

(deftest test-example
  (let [schema (avro/parse-schema
                {:type :record,
                 :namespace 'abracad.core-test
                 :name 'Example
                 :fields [{:name "foo-foo" :type :string}
                          {:name "bar"
                           :type [:null
                                  {:type :record
                                   :name 'SubExample
                                   :fields [{:name "baz",
                                             :type :long}]}]}]})
        record (->Example "bar" (->SubExample 0))
        bytes (avro/binary-encoded schema record)]
    (is (= {:foo-foo "bar" :bar {:baz 0}}
           (avro/decode schema bytes)))
    (binding [avro/*avro-readers*
            {'abracad.core-test/Example #'->Example
             'abracad.core-test/SubExample #'->SubExample}]
      (is (= record (avro/decode schema bytes))))))

(deftest test-customized
  (let [schema (avro/parse-schema
                {:type :record
                 :name 'ip.address
                 :fields [{:name :address
                           :type [{:type :fixed, :name "IPv4", :size 4}
                                  {:type :fixed, :name "IPv6", :size 16}]}]})
        records [(InetAddress/getByName "8.8.8.8")
                 (InetAddress/getByName "8::8")]
        bytes (apply avro/binary-encoded schema records)]
    (binding [avro/*avro-readers* {'ip/address #'->InetAddress}]
      (is (= records (doall (avro/decode-seq schema bytes)))))))

(deftest test-union
  (let [vertical {:type :enum, :name "vertical", :symbols [:up :down]}
        horizontal (avro/parse-schema
                    {:type :enum, :name "horizontal", :symbols [:left :right]})
        schema (avro/parse-schema
                vertical horizontal
                [:null :long :string "vertical" "horizontal"])
        records ["down" :up :down :left 0 :right "left"]
        bytes (apply avro/binary-encoded schema records)]
    (is (= records (avro/decode-seq schema bytes)))))

(deftest test-bytes
  (let [schema (avro/parse-schema
                [{:type :fixed, :name "foo", :size 1}, :bytes])
        records [(byte-array (map byte [1]))
                 (byte-array (map byte [1 2]))]
        bytes (apply avro/binary-encoded schema records)
        thawed (avro/decode-seq schema bytes)]
    (is (= 6 (alength ^bytes bytes)))
    (is (every? (partial instance? (Class/forName "[B")) thawed))
    (is (= (map seq records) (map seq thawed)))))

(deftest test-arrays
  (let [schema (avro/parse-schema {:type :array, :items :long})
        records [[] [0 1] (range 1024)]
        bytes (apply avro/binary-encoded schema records)
        thawed (avro/decode-seq schema bytes)]
    (is (= records thawed))))

(deftest test-maps
  (let [schema (avro/parse-schema {:type :map, :values :long})
        records [{}
                 {"foo" 0, "bar" 1}
                 (->> (range 1024)
                      (map #(-> [(str %) %]))
                      (into {}))]
        bytes (apply avro/binary-encoded schema records)
        thawed (avro/decode-seq schema bytes)]
    (is (= records thawed))))

(deftest test-extra
  (let [schema (avro/parse-schema
                {:name "Example", :type "record",
                 :fields [{:name "foo", :type "long"}]})]
    (is (thrown? clojure.lang.ExceptionInfo
          (->> {:foo 0, :bar 1}
               (avro/binary-encoded schema))))
    (is (= {:foo 0}
           (->> ^{:type 'Example} {:foo 0, :bar 1}
                (avro/binary-encoded schema)
                (avro/decode schema))))
    (is (= {:foo 0}
           (->> ^:avro/unchecked {:foo 0, :bar 1}
                (avro/binary-encoded schema)
                (avro/decode schema))))))

(deftest test-positional
  (let [schema (avro/parse-schema
                {:name "Example", :type "record",
                 :abracad.reader "vector",
                 :fields [{:name "left", :type "long"}
                          {:name "right", :type "string"}]}
                ["Example" "string"])
        records [[0 "foo"] [1 "bar"] [2 "baz"] "quux"]
        bytes (apply avro/binary-encoded schema records)
        thawed (avro/decode-seq schema bytes)]
    (is (= records thawed))
    (is (= 'Example (-> thawed first type)))))

(deftest test-mangle-union
  (let [schema (avro/parse-schema
                {:name "mangle-me", :type "record",
                 :abracad.reader "vector"
                 :fields [{:name "field0", :type "long"}]}
                ["mangle-me" "long"])
        records [0 [1] [2] 3 4 [5]]
        bytes (apply avro/binary-encoded schema records)
        thawed (avro/decode-seq schema bytes)]
    (is (= records thawed))))
