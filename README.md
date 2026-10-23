# groundwork-settings

Settings and secrets for a Clojure app. One EDN
file per environment holds the whole settings map; `#secret :ns/name`
references are read from the one source the file names, sops or
1Password; `#env "NAME"` fills what the platform hands in. `load` checks
the result against the app's malli shape and throws every problem at
once, each with its path. A secret stays wrapped: printed, logged, in an
exception, in malli's errors or as JSON it says only
`#secret :groundwork-email/postmark-token`; `reveal` gives its text where it is
used.

```clojure
io.github.p4ulcristian/groundwork-settings {:git/tag "v0.1.1" :git/sha "72d0dbb"}
```

Needs only malli and jsonista, and the `sops` or `op` program on the
PATH. One public namespace, `groundwork-settings.api`.

## The api

| name | does |
|---|---|
| `load` | the settings from `{:file :shape :env}`, every key required: read, `#env` and `#secret` filled, checked; or one exception listing every problem |
| `secret` | the malli shape of a secret, for a library's settings shape |
| `reveal` | a secret's text, where it is used |
| `equal?` | whether two secrets or strings hold the same text, in constant time |
| `make-secret` | a secret from a name and a text, for tests |

```clojure
;; settings/production.edn, in the app's repository
{:secrets  {:from         :sops
            :file         "secrets/production.sops.yaml"
            :age-key-file "/run/secrets/age-key"
            :timeout-ms   30000}                     ; how long sops may take
 :server   {:ip "0.0.0.0" :port #env ["PORT" :int]}
 :database {:host "db.internal" :user "shop" :password #secret :groundwork-postgres/password}
 :email  {:provider {:name :postmark :server-token #secret :groundwork-email/postmark-token}}}
```

```clojure
(require '[groundwork-settings.api :as settings])

(def settings (settings/load {:file  "settings/production.edn"
                          :shape settings-shape      ; the app's malli shape, without :secrets
                          :env   (System/getenv)}))

settings
;=> {:server   {:ip "0.0.0.0" :port 8080}
;    :database {:host "db.internal" :user "shop" :password #secret :groundwork-postgres/password}
;    :email  {:provider {:name :postmark :server-token #secret :groundwork-email/postmark-token}}}

;; a library's settings shape
[:map {:closed true} [:server-token settings/secret] …]

;; where the text is used, and only there
(.header builder "X-Postmark-Server-Token" (settings/reveal server-token))

;; a token a request carries, against the settings'
(settings/equal? (:token settings) (read-bearer-token request))
```

A file with problems throws once:

```
settings/production.edn has 3 problems:
  [:database :password] :groundwork-postgres/password is not in secrets/production.sops.yaml: no key password under cellar
  [:server :host] disallowed key
  [:server :port] the environment variable PORT is not set
```

with `(ex-data e)` = `{:type :groundwork-settings/bad-settings :file … :problems [{:path … :message …} …]}`.
groundwork-settings logs nothing: it runs before the app's log exists.

## The two sources

`:secrets` names one, every key required, or is nil when the file has no
`#secret`. `load` leaves it out of the result.

**sops**: `{:from :sops :file … :age-key-file … :timeout-ms …}`. groundwork-settings runs
`sops --decrypt --output-type json <file>` with only `PATH` and
`SOPS_AGE_KEY_FILE` set and reads its output in memory. `:groundwork-postgres/password`
is the key `password` under `groundwork-postgres`:

```yaml
# secrets/production.sops.yaml, before `sops --encrypt --age <public key>`
cellar:
    password: "…"
postbox:
    postmark-token: "…"
    webhook-token: "…"
```

**1Password**: `{:from :1password :vault "shop-production" :token-from #env "OP_SERVICE_ACCOUNT_TOKEN" :timeout-ms …}`.
groundwork-settings runs `op read --no-newline op://shop-production/cellar/password` once
per secret, with only `PATH`, `OP_SERVICE_ACCOUNT_TOKEN` and `OP_CACHE=false`
set: the item `groundwork-postgres`, its field `password`. The token comes only from
`#env`, goes to op in its environment, and is never said.

Nothing decrypted is written to disk, and nothing is cached. A program
that takes longer than `:timeout-ms` is stopped.

## `#env`

`#env "NAME"` is text; `#env ["NAME" :int]`, `#env ["NAME" :boolean]`
(`true`/`false`) and `#env ["NAME" :string]` turn it into its type. A
variable that isn't set, or isn't its type, is a problem; its text is
never said.

## Tests

```sh
clojure -X:test
```

The sops tests use the real `sops` and `age-keygen` when they are on the
PATH (a throwaway key in a temporary folder), and a fake sops always; the
1Password tests use a fake `op`.

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
