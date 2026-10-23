(ns safe.secret
  "A secret: text kept inside a value of safe's own type, which says only
   its name wherever it is printed, turned into text or written as JSON:
   #secret :postbox/postmark-token. reveal-text gives the text."
  (:require
   [malli.core :as malli])
  (:import
   (com.fasterxml.jackson.core JsonGenerator)
   (com.fasterxml.jackson.databind JsonSerializable)
   (java.io Writer)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(definterface Revealable
  (^String revealText []))

;; The text is a mutable field only so that it is private: a deftype's
;; other fields are public, and a field-reading serializer (Jackson's
;; defaults) would write them. Nothing sets it after it is made.
;; Equality and hash are Object's, the instance's identity: they never
;; look at the text, so they take the same time whatever it is.
(deftype Secret [secret-name ^:volatile-mutable ^String text]
  Revealable
  (revealText [_] text)

  Object
  (toString [_] (str "#secret " secret-name))

  JsonSerializable
  (serialize [this generator _]
    (.writeString ^JsonGenerator generator (.toString this)))
  (serializeWithType [this generator _ _]
    (.writeString ^JsonGenerator generator (.toString this))))

(defmethod print-method Secret [secret ^Writer writer]
  (.write writer (.toString ^Object secret)))

(defmethod print-dup Secret [secret ^Writer writer]
  (.write writer (.toString ^Object secret)))

(defn secret?
  [value]
  (instance? Secret value))

(defn make-secret
  "A secret named secret-name (a namespaced keyword) holding text."
  [secret-name text]
  (when-not (qualified-keyword? secret-name)
    (throw (ex-info "A secret's name is a namespaced keyword, like :postbox/postmark-token."
                    {:type :safe/bad-secret-name :given-class (some-> secret-name class .getName)})))
  (when-not (string? text)
    (throw (ex-info (str "A secret's text is a string; " secret-name " was given another value.")
                    {:type :safe/bad-secret-text :secret-name secret-name :given-class (some-> text class .getName)})))
  (Secret. secret-name text))

(defn reveal-text
  "The secret's text. Throws for anything else, naming only its class."
  ^String [secret]
  (if (secret? secret)
    (.revealText ^Revealable secret)
    (throw (ex-info (str "reveal takes a secret; it was given "
                         (if (nil? secret) "nil" (.getName (class secret))) ".")
                    {:type :safe/not-a-secret :given-class (some-> secret class .getName)}))))

(defn- hash-text ^bytes [^String text]
  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes text StandardCharsets/UTF_8)))

(defn- text-of [value]
  (cond (secret? value) (reveal-text value)
        (string? value) value))

(defn equal-texts?
  "Whether two values, each a secret or a string, hold the same text,
   compared in constant time: both are hashed first, so neither the
   length nor where they first differ changes the time. nil, or anything
   else, is equal to nothing."
  [a b]
  (let [text-a (text-of a)
        text-b (text-of b)]
    (boolean (and text-a text-b
                  (MessageDigest/isEqual (hash-text text-a) (hash-text text-b))))))

(def secret-shape
  "The malli shape of a secret, for a library's settings shape:
   [:map [:server-token safe/secret]]. A string, a written-out token, is
   refused: a secret comes from the settings' secrets source."
  (malli/-simple-schema
   {:type            :safe/secret
    :pred            secret?
    :type-properties {:error/message "should be a #secret, read from the settings' secrets source"}}))
