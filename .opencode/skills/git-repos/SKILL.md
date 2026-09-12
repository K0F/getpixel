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

This environment can publish a repo two ways. Prefer GitHub (it is also the
only one F-Droid and other remote CI can see); the Pi bare repo is the
fallback for when GitHub/`gh` is unreachable.

Hosting facts already verified in this environment:

- GitHub user: `K0F` → URL pattern `https://github.com/K0F/<name>`.
- `gh` can hang on `gh api`/`gh auth status` during network trouble; always run
  it under `timeout` (e.g. `timeout 30 gh ...`). An empty-but-exit-0 result
  also means "no answer" — check it really worked before moving on.
- Pi bare repos live in `/home/kof/repos/<name>.git`, reachable as
  `pi:/home/kof/repos/<name>.git` (ssh host alias `pi`).
- git identity is already configured (Kryštof Pešek / krystof.pesek@gmail.com).

## 0. Before committing anything

1. `make test` / the project's test suite first — push working code.
2. `git status` and `git diff` and inspect; stage only intended files.
3. Never commit secrets (API keys, keystores, `local.properties`, `*.jks`).
4. Write `README.md` + `LICENSE` + `.gitignore` before the first commit.
5. Use `-q` and a concise message that matches repo style; do NOT amend the
   failed commit, create a new one instead.

## 1. GitHub (primary)

Local branch should be `main`:

```sh
git init -b main          # or: git branch -M main
git add -A && git commit -q -m "message"
```

Create the public repo and push in one step:

```sh
gh auth setup-git                       # lets git use gh's token over https
timeout 90 gh repo create getpixel --public --source=. --push
```

**Gotcha:** if an `origin` remote already exists (e.g. the Pi), `gh repo
create --source=. --push` fails with `Unable to add remote "origin"` — the
GitHub repo is still created, but nothing is pushed and `origin` is untouched.
Fix by adding GitHub under a distinct name and pushing:

```sh
git remote add github https://github.com/K0F/<name>.git
timeout 60 git push github main --tags   # --tags pushes release tags
```

Verify:

```sh
timeout 30 gh repo view <name> --json url,visibility -q '.url + " " + .visibility'
# expect https://github.com/K0F/<name> PUBLIC
```

## 2. Raspberry Pi bare repo (fallback / backup)

```sh
timeout 30 ssh pi 'test -d /home/kof/repos/<name>.git && echo EXISTS || \
  (git init --bare -q /home/kof/repos/<name>.git && echo CREATED)'
git remote add origin pi:/home/kof/repos/getpixel.git
git push -u origin main --tags
```

The Pi also doubles as a backup for an existing GitHub repo:

```sh
git remote add pi pi:/home/kof/repos/<name>.git
git push pi main --tags
```

## 3. Synchronising multiple remotes

Keep them in sync after each commit, then confirm both landed:

```sh
git push github main v1.0.0 && git push origin main v1.0.0
git remote -v                              # show all remotes
```

Naming convention used so far: `origin` = Pi, `github` = GitHub. Symmetric
uploads (no `git pull` from the Pi mirror) keep both diverging in content.

## 4. Tagging releases

F-Droid `AutoUpdateMode: Version v%v` + `UpdateCheckMode: Tags` tracks
`v<version>` tags; tag immediately after the release commit and push tags to
every remote:

```sh
git tag v1.0.0
git push github v1.0.0 && git push origin v1.0.0
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