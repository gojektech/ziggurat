(ns ziggurat.middleware.default
  (:require [clojure.edn :as edn]
            [protobuf.core :as protobuf]
            [protobuf.impl.flatland.mapdef :as protodef]
            [ziggurat.config :refer [ziggurat-config]]
            [ziggurat.message-payload :refer [mk-message-payload]]
            [ziggurat.metrics :as metrics]
            [ziggurat.util.error :refer [report-error]])
  (:import [com.ziggurat.proto MessagePayloadProto$MessagePayload]))

(defn protobuf-struct->persistent-map
  "This functions converts a protobuf struct in to clojure persistent map recursively"
  [struct]
  (let [fields                          (:fields struct)
        protobuf-value-flattener        (fn flatten-value [value]
                                          (first (map (fn [[type val]]
                                                        (cond
                                                          (= :null-value type)
                                                          nil
                                                          (= :struct-value type)
                                                          (protobuf-struct->persistent-map val)
                                                          (= :list-value type)
                                                          (reduce #(conj %1 (flatten-value %2))
                                                                  []
                                                                  (:values val))
                                                          :else
                                                          val))
                                                      value)))
        protobuf-struct-entry-flattener (fn [res entry]
                                          (let [key   (keyword (:key entry))
                                                value (:value entry)]
                                            (if key
                                              (assoc res key (protobuf-value-flattener value))
                                              res)))]
    (reduce protobuf-struct-entry-flattener
            {}
            fields)))

(defn serialize-message
  [proto-class topic-entity message]
  (let [message (mk-message-payload (:message message) topic-entity (:retry-count message))]
    (protobuf/->bytes (protobuf/create proto-class message))))

(def serialize-to-message-payload (partial serialize-message MessagePayloadProto$MessagePayload))

(defn deserialize-message
  "This function takes in the message(proto Byte Array) and the proto-class and deserializes the proto ByteArray into a
  Clojure PersistentHashMap.
  Temporary logic for migration of services to Ziggurat V3.0
    If the message is of type map, the function just returns the map as it is. In older versions of Ziggurat (< 3.0) we stored
    the messages in deserialized formats in RabbitMQ and those messages can be processed by this function. So we have this logic here."
  ([message proto-class topic-entity-name]
   (deserialize-message message proto-class topic-entity-name false))
  ([message proto-class topic-entity-name flatten-protobuf-struct?]
   (if-not (map? message)                                   ;; TODO: we should have proper dispatch logic per message type (not like this)
     (try
       (let [proto-klass  (protodef/mapdef proto-class)
             loaded-proto (protodef/parse proto-klass message)
             proto-keys   (-> proto-klass
                              protodef/mapdef->schema
                              :fields
                              keys)
             result       (select-keys loaded-proto proto-keys)
             msg          (if (:message result) ;; TODO: please read the TODO above!
                            (edn/read-string (.toStringUtf8 (:message result)))
                            result)
             te           (keyword (get result :topic-entity topic-entity-name))
             rc           (get result :retry-count 0)
             result       {:message msg :topic-entity te :retry-count rc}]
         (if flatten-protobuf-struct?
           (let [struct-entries (-> proto-klass
                                    protodef/mapdef->schema
                                    :fields)
                 struct-keys    (map (fn [[k _]]
                                       k) (filter (fn [[_ v]]
                                                    (and (= :struct (:type v))
                                                         (= "google.protobuf.Struct" (:name v))))
                                                  struct-entries))]
             (reduce (fn [res val]
                       (update res val protobuf-struct->persistent-map))
                     result
                     struct-keys))
           result))
       (catch Throwable e
         (let [service-name      (:app-name (ziggurat-config))
               additional-tags   {:topic_name topic-entity-name}
               default-namespace "message-parsing"
               metric-namespaces [service-name topic-entity-name default-namespace]
               multi-namespaces  [metric-namespaces [default-namespace]]]
           (report-error e (str "Couldn't parse the message with proto - " proto-class))
           (metrics/multi-ns-increment-count multi-namespaces "failed" additional-tags)
           nil)))
     message)))

(defn protobuf->hash
  "This is a middleware function that takes in a message (Proto ByteArray or PersistentHashMap) and calls the handler-fn with the deserialized PersistentHashMap"
  ([handler-fn proto-class topic-entity-name]
   (protobuf->hash handler-fn proto-class topic-entity-name false))
  ([handler-fn proto-class topic-entity-name flatten-protobuf-struct?]
   (fn [message]
     (handler-fn (deserialize-message message proto-class topic-entity-name flatten-protobuf-struct?)))))
