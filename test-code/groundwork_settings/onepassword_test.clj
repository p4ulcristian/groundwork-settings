(ns groundwork-settings.onepassword-test
  "The 1Password source against a fake op on the PATH: a script that
   answers op read as a service account would, and refuses a call that
   doesn't come as groundwork-settings makes it (the token in its environment, the cache
   off, no HOME, no other variable). Never a real 1Password."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-settings.api :as settings]
   [groundwork-settings.test-files :as files]))

(def ^:private token "ops_fake-service-account-token-5150")

(def ^:private fake-op
  (str "#!/bin/bash
# a fake op: answers `op read --no-newline op://shop-test/<item>/<field>`
[ \"$OP_SERVICE_ACCOUNT_TOKEN\" = '" token "' ] || { echo \"[ERROR] 2026/10/23 00:00:00 could not read secret '$3': not signed in\" >&2; exit 1; }
[ \"$OP_CACHE\" = false ] || { echo '[ERROR] the cache is on' >&2; exit 1; }
[ -z \"$HOME\" ] || { echo '[ERROR] HOME is set' >&2; exit 1; }
[ -z \"$SOMETHING_ELSE\" ] || { echo '[ERROR] the environment was passed on' >&2; exit 1; }
[ $# = 3 ] && [ \"$1\" = read ] && [ \"$2\" = --no-newline ] || { echo \"[ERROR] called as: $*\" >&2; exit 1; }
case \"$3\" in
  op://shop-test/postbox/postmark-token) printf '%s' 'pm-text-of-the-postmark-token' ;;
  op://shop-test/cellar/password)        printf '%s' 'db-text-of-the-password' ;;
  op://shop-test/slow/token)             sleep 5 ;;
  op://shop-test/leaky/token)            echo \"[ERROR] 2026/10/23 00:00:00 echoing $OP_SERVICE_ACCOUNT_TOKEN\" >&2; exit 1 ;;
  *) echo \"[ERROR] 2026/10/23 00:00:00 could not read secret '$3': isn't an item in the vault\" >&2; exit 1 ;;
esac
"))

(def ^:private shape
  [:map {:closed true}
   [:server [:map {:closed true} [:port :int]]]
   [:email [:map {:closed true} [:server-token settings/secret]]]
   [:database [:map {:closed true} [:password settings/secret]]]])

(defn- make-world
  "A folder with the fake op and an environment whose PATH finds it."
  []
  (let [folder (files/make-folder)
        bin    (files/write-program folder "op" fake-op)]
    {:folder folder
     :env    {"PATH"                     (str bin ":/usr/bin:/bin")
              "OP_SERVICE_ACCOUNT_TOKEN" token
              "PORT"                     "8080"
              "SOMETHING_ELSE"           "never passed to op"
              "HOME"                     "/home/someone"}}))

(defn- settings-text [{:keys [vault token-from extra]}]
  (str "{:secrets {:from :1password :vault \"" vault "\" :token-from " token-from " :timeout-ms 3000}
 :server   {:port #env [\"PORT\" :int]}
 :email  {:server-token #secret :groundwork-email/postmark-token}
 :database {:password #secret :groundwork-postgres/password}"
       extra "}"))

(defn- load-settings [{:keys [folder env]} text]
  (settings/load {:file  (files/write-file folder "production.edn" text)
              :shape shape
              :env   env}))

(defn- problems-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (assoc (ex-data e) :text (str (ex-message e) (pr-str (ex-data e)))))))

(deftest a-settings-file-with-secrets-and-env-resolves
  (let [world    (make-world)
        settings (load-settings world (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""}))]
    (is (= {:port 8080} (:server settings)))
    (is (not (contains? settings :secrets)) "the source is left out")
    (is (= "pm-text-of-the-postmark-token" (settings/reveal (get-in settings [:email :server-token]))))
    (is (= "db-text-of-the-password" (settings/reveal (get-in settings [:database :password]))))
    (is (= "{:server {:port 8080}, :email {:server-token #secret :groundwork-email/postmark-token}, :database {:password #secret :groundwork-postgres/password}}"
           (pr-str settings)))))

(deftest a-secret-1password-doesnt-have-is-a-problem-at-its-path
  (let [world (make-world)
        {:keys [problems text]}
        (problems-of #(load-settings world (str/replace (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})
                                                        ":groundwork-postgres/password" ":groundwork-postgres/no-such")))]
    (is (= [{:path [:database :password]
             :message "1Password could not read op://shop-test/cellar/no-such: could not read secret 'op://shop-test/cellar/no-such': isn't an item in the vault"}]
           problems))
    (is (not (str/includes? text "pm-text")))))

(deftest a-wrong-token-fails-every-read-and-is-never-said
  (let [world (assoc-in (make-world) [:env "OP_SERVICE_ACCOUNT_TOKEN"] "ops_another-token-0000")
        {:keys [problems text]}
        (problems-of #(load-settings world (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})))]
    (is (= #{[:email :server-token] [:database :password]} (set (map :path problems))))
    (is (every? #(str/includes? (:message %) "not signed in") problems))
    (is (not (str/includes? text "ops_another-token")))))

(deftest a-token-op-repeats-is-replaced-by-its-name
  (let [world (make-world)
        {:keys [problems text]}
        (problems-of #(load-settings world (str/replace (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})
                                                        ":groundwork-postgres/password" ":leaky/token")))]
    (is (= [{:path [:database :password]
             :message "1Password could not read op://shop-test/leaky/token: echoing #secret :groundwork-settings/op-service-account-token"}]
           problems))
    (is (not (str/includes? text token)))))

(deftest the-token-is-never-written-in-the-file
  (let [world (make-world)
        {:keys [problems text]}
        (problems-of #(load-settings world (settings-text {:vault "shop-test" :token-from (pr-str token)})))]
    (is (= [{:path [:secrets :token-from] :message "should be #env \"NAME\": the token never sits in the file"}]
           problems))
    (is (not (str/includes? text token)))))

(deftest a-missing-token-variable-is-a-problem-and-op-is-not-run
  (let [world (update (make-world) :env dissoc "OP_SERVICE_ACCOUNT_TOKEN")
        {:keys [problems]}
        (problems-of #(load-settings world (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})))]
    (is (= [{:path [:secrets :token-from] :message "the environment variable OP_SERVICE_ACCOUNT_TOKEN is not set"}]
           problems))))

(deftest op-that-takes-too-long-is-stopped
  (let [world (make-world)
        text  (-> (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})
                  (str/replace ":groundwork-postgres/password" ":slow/token")
                  (str/replace ":timeout-ms 3000" ":timeout-ms 300"))
        start (System/nanoTime)
        {:keys [problems]} (problems-of #(load-settings world text))]
    (is (= [{:path [:database :password] :message "op took longer than 300 ms to read op://shop-test/slow/token"}]
           problems))
    (is (< (/ (- (System/nanoTime) start) 1e6) 4000))))

(deftest no-op-on-the-path-is-a-problem
  (let [world (assoc-in (make-world) [:env "PATH"] "/nowhere")
        {:keys [problems]}
        (problems-of #(load-settings world (settings-text {:vault "shop-test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})))]
    (is (= [{:path [:secrets :from] :message "no op on the PATH"}] problems))))

(deftest a-bad-source-is-listed-with-the-rest
  (let [world (make-world)
        {:keys [problems]}
        (problems-of #(load-settings world (str/replace (settings-text {:vault "shop/test" :token-from "#env \"OP_SERVICE_ACCOUNT_TOKEN\""})
                                                        ":timeout-ms 3000" "")))]
    (is (= [{:path [:secrets :timeout-ms] :message "missing required key"}
            {:path [:secrets :vault] :message "should be a vault's name or id, without /"}]
           problems))))
