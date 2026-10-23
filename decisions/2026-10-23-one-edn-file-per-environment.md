# One EDN settings file per environment

**Decision:** an app's settings are one EDN file per environment in its own repository, `settings/production.edn`, `settings/development.edn`: the whole settings map, every key written out, as the app's shape requires. Two tags stand where a value isn't in the file: `#secret :postbox/postmark-token`, read from the secrets' source the file names, and `#env "PORT"`, read from the environment. Paths in the file (the sops file, the age key) are as written, relative to the folder the app runs in.

**Why:** the file is the whole story of an environment: readable at once, diffable against another (`diff settings/stage.edn settings/production.edn` shows every difference that isn't a secret), reviewed in a pull request like code. It holds no secret, so it can be public. Manor's rule that every value that sets behaviour is a setting (mailroom's [Every value that sets behaviour is a setting](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)) makes the settings long; a file per environment is where a long map stays readable.

**Instead of:**
- an environment variable per key (Iron Rainbow's way): the values live in the hosting platform's screens, nothing shows what production runs, two environments can't be compared, and every number arrives as text to be parsed by hand
- one file with profiles (`#profile {:dev 1 :prod 2}`): every value carries all environments, and no file reads as one environment
- a base file merged with one per environment: a value's origin is two files away, and a key missing from the override silently takes the base's
