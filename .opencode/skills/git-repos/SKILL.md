---
name: git-repos
description: >-
  Use when creating a git repository and publishing it ("create a repo",
  "make a repo", "git init", "push to github", "publish repo") or managing
  repos and remotes ("add a remote", "push to both", "sync pi and github",
  "tag a release", "create a backup repo"). Covers the two hosting paths
  available here: GitHub via gh CLI, and a LAN Raspberry Pi bare repo.
---

# Creating and managing git repositories

Every new repo is published to **both** hosting paths: the Raspberry Pi bare
repo as `origin` (the *home* — where remote access lives), **and** GitHub as a
second remote named `github` (the public copy that F-Droid / remote CI can
see). Never leave a repo on a single host.

Remote naming for new repos: `origin` = Pi, plus a second remote `github`
(`https://github.com/K0F/<name>.git`). Push to both on every sync, along with
tags.

The Pi remote location is **copied from `~/.ssh/config`**, never hardcoded
here. Look up the `pi` host block (`Host pi`, `user`, `hostname`, `port`) and
build the git URL against that entry's home directory. In this environment that
resolves to `pi:repos/<name>.git` (= `/home/kof/repos/<name>.git` for user
`kof`). If the ssh config changes, the repo locations below change with it.

Hosting facts already verified in this environment:

- GitHub user: `K0F` → URL pattern `https://github.com/K0F/<name>`.
- `gh` can hang on `gh api`/`gh auth status` during network trouble; always run
  it under `timeout` (e.g. `timeout 30 gh ...`). An empty-but-exit-0 result
  also means "no answer" — check it really worked before moving on.
- `~/.ssh/config` defines `Host pi` (see above); `pi:repos/…` paths resolve to
  the `kof` user's home on that host. `git ls-remote pi:repos/<name>.git`
  validates reachability.
- git identity is already configured (Kryštof Pešek / krystof.pesek@gmail.com).

## 0. Before committing anything

1. `make test` / the project's test suite first — push working code.
2. `git status` and `git diff` and inspect; stage only intended files.
3. Never commit secrets (API keys, keystores, `local.properties`, `*.jks`).
4. Write `README.md` + `LICENSE` + `.gitignore` before the first commit.
5. Use `-q` and a concise message that matches repo style; do NOT amend the
   failed commit, create a new one instead.

## 1. Full dual-hosting setup (always do all of this)

Local branch should be `main`:

```sh
git init -b main          # or: git branch -M main
git add -A && git commit -q -m "message"
```

**Step 1 — GitHub** (created first; `origin` is *not* left on GitHub):

```sh
gh auth setup-git                       # lets git use gh's token over https
timeout 90 gh repo create <name> --public --source=. --push
git remote rename origin github         # Pi is the home → GitHub moves to `github`
```

**Gotcha:** if an `origin` remote already exists, `gh repo create
--source=. --push` fails with `Unable to add remote "origin"` — the GitHub
repo is still created, but nothing is pushed. Fix by adding GitHub under the
`github` name and pushing (then continue with the Pi step):

```sh
git remote add github https://github.com/K0F/<name>.git
timeout 60 git push github main --tags
```

**Step 2 — Pi bare repo, always** (`origin`, the home):

```sh
timeout 30 ssh pi 'test -d ~/repos/<name>.git && echo EXISTS || \
  (git init --bare -q ~/repos/<name>.git && echo CREATED)'
git remote add origin pi:repos/<name>.git
git push -u origin main --tags
```

(`~/repos` on the remote = `$HOME` from the `pi` entry in `~/.ssh/config`;
`pi:repos/…` resolves against that same home.)

**Step 3 — verify both hosts** before moving on:

```sh
git remote -v                                    # expect origin = pi:..., github = github...
timeout 30 gh repo view <name> --json url,visibility -q '.url + " " + .visibility'
# expect https://github.com/K0F/<name> PUBLIC
git ls-remote pi:repos/<name>.git | head          # pi reachable
```

## 2. Raspberry Pi bare repo only (fallback / backup)

```sh
timeout 30 ssh pi 'test -d ~/repos/<name>.git && echo EXISTS || \
  (git init --bare -q ~/repos/<name>.git && echo CREATED)'
git remote add origin pi:repos/<name>.git
git push -u origin main --tags
```

Use this when `gh create`/`gh api` hang (§6). Existing repos can add the Pi as
a pure backup the same way.

## 3. Synchronising remotes

Push to every remote on each sync, then confirm both landed:

```sh
git push origin main --tags && git push github main --tags
git remote -v                                  # origin (= Pi) + github
```

Symmetric uploads only; no pull from mirrors keeps both hosts in content sync.

## 4. Tagging releases

F-Droid `AutoUpdateMode: Version v%v` + `UpdateCheckMode: Tags` tracks
`v<version>` tags; tag immediately after the release commit and push tags to
every remote:

```sh
git tag v1.0.0
git push origin v1.0.0 && git push github v1.0.0
```

Bump `versionCode` in `app/build.gradle` before each new tag.

## 5. Everyday management

- Inspect before acting: `git status --short`, `git log --oneline -10`,
  `git diff --stat`.
- Stage precisely: `git add path/to/file` (avoid `git add -A` blobs/secrets).
- Fetch state of GitHub: `git fetch github` (then `git log HEAD..github/main`).
- Housekeeping: `git remote prune github`, `git branch -vv`.
- Clone check after creating a repo (proves the remote is usable):
  `git clone <url> /tmp/<name>-check && make test`.

## 6. When GitHub is down

`gh repo create`/`gh api` return immediately with an empty message or hang
under `timeout`. Fall back to the Pi bare repo (§2), and remember remote
recipe URLs (`fdroid/*.yml` `Repo:` fields) point at GitHub — update those
fields back to `https://github.com/K0F/<name>` once GitHub is reachable again.