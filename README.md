# groundwork-settings

Settings and secrets for a Clojure app. One EDN
file per environment holds the whole settings map; `#secret :ns/name`
references are read from the sops file the settings file names;
`#env "NAME"` fills what the platform hands in. `load` checks
the result against the app's malli shape and throws every problem at
once, each with its path. A secret stays wrapped: printed, logged, in an
exception, in malli's errors or as JSON it says only
`#secret :groundwork-email/postmark-token`; `reveal` gives its text where it is
used.

```clojure
io.github.p4ulcristian/groundwork-settings {:git/tag "v0.1.2" :git/sha "25dfbf7"}
```

Needs only malli and jsonista, and the `sops` program on the
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
{:secrets  [{:from         :sops
             :file         "secrets/production.sops.yaml"
             :age-key-file "/run/secrets/age-key"
             :timeout-ms   30000}]                   ; how long sops may take
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

## The sops files

`:secrets` lists the sops files the place opens, each
`{:from :sops :file … :age-key-file … :timeout-ms …}`, every key required, or
`[]` when the file has no `#secret`. `load` leaves it out of the result.
groundwork-settings runs `sops --decrypt --output-type json <file>` once per
file, with only `PATH` and `SOPS_AGE_KEY_FILE` set, and reads its output in
memory. `:groundwork-postgres/password` is the key `password` under
`groundwork-postgres`:

```yaml
# secrets/production.sops.yaml, before `sops --encrypt --age <public key>`
groundwork-postgres:
    password: "…"
groundwork-email:
    postmark-token: "…"
    webhook-token: "…"
```

A secret lives in exactly one of the files: one found in two is a problem
(no file overrides another), and so is one found in none. Several files are
for secrets with different readers, a team's shared sandbox keys and one
machine's own:

```clojure
{:secrets [{:from :sops :file "secrets/dev-vendors.sops.yaml" …}   ; the team reads it
           {:from :sops :file "secrets/box.sops.yaml" …}]}         ; only the box does
```

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
PATH (a throwaway key in a temporary folder), and a fake sops always.

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
