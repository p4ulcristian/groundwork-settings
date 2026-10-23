# load lists every problem at once, and logs nothing

**Decision:** `(safe/load {:file … :shape … :env …})`, every key required, the options checked (`:safe/bad-options`), reads the file, fills `#env`, reads the secrets, leaves `:secrets` out, checks the rest against `:shape` with malli, and returns the settings, or throws once:

```clojure
{:type     :safe/bad-settings
 :file     "settings/production.edn"
 :problems [{:path [:database :password] :message ":cellar/password is not in secrets/production.sops.yaml: no key password under cellar"}
            {:path [:server :host]       :message "disallowed key"}
            {:path [:server :port]       :message "the environment variable PORT is not set"}]}
```

with every problem, sorted by path, and the message listing them one per line. A file that can't be read or isn't one map is one problem at `[]`; a missing `:secrets` key, a bad source, a source that fails, a missing secret, a bad or missing `#env`, a missing, unknown or wrong key of the shape are all listed together. A value that couldn't be read is not also said to be the wrong type; an unknown key is said even then. Messages are malli's own for the shape's problems. No problem holds a secret's text or an environment variable's.

safe logs nothing and takes no `:log`.

**Why:** a deploy that fails one problem at a time takes as many deploys as there are problems. Paths say where in the file to look. safe runs first, before the app's logbook is made from the settings safe returns, so there is no log to write to; what it would say is the exception's data, which the app's start prints or the process's output shows.

**Instead of:**
- the first problem only: one deploy per mistake
- humanized malli errors as a nested map (the libraries' `:errors`): the reading problems (`#env`, `#secret`) have no place in malli's shape, and a nested map hides the count
- a `:log` option: there is none yet at that point, and a log made only for safe would write in another format than the app's
