# sap-cpi-snapshot

Java 21 CLI that backs up SAP Cloud Integration (CPI) design-time content into a Git repository.

It reads every integration package through the CPI OData API, downloads each iFlow, value mapping, message mapping and script collection, and **unzips** them into a folder tree, so Git shows readable diffs of `.iflw`, Groovy and mapping files. Unchanged content gives byte-identical output: no change on the tenant means no commit.

```
<out>/
├── snapshot-manifest.json
├── CertAuthPoC/
│   ├── package.json
│   ├── iflows/<ArtifactId>/...
│   └── script-collections/<ArtifactId>/...
└── EvolveCutoverXIReprocess/
    └── ...
```

## Prerequisites

1. BTP cockpit → subaccount → **Instances and Subscriptions** → create an instance of
   **Process Integration Runtime**, plan **api**, role **WorkspacePackagesRead**,
   grant type **Client Credentials**.
2. Create a **service key** for it and save it as `servicekey.json` (git-ignored).

## Build

Open the folder in the dev container (VS Code *Reopen in Container* or Codespaces). The jar is built on create. To build manually:

```bash
mvn -B package
java -jar target/sap-cpi-snapshot.jar --help
```

## Usage

```bash
# What's on the tenant
java -jar target/sap-cpi-snapshot.jar list -k servicekey.json

# Snapshot into a backup repo and commit
git clone https://github.com/viksingh/cpi-trial.git ../cpi-trial
java -jar target/sap-cpi-snapshot.jar snapshot -k servicekey.json -o ../cpi-trial/content --commit

# ...and push
java -jar target/sap-cpi-snapshot.jar snapshot -k servicekey.json -o ../cpi-trial/content --push
```

| Option | Meaning |
|---|---|
| `-k, --service-key FILE` | BTP service key JSON (nested `oauth` block or flat) |
| `--url`, `--token-url`, `--client-id`, `--client-secret` | Override values; also read from `CPI_URL`, `CPI_TOKEN_URL`, `CPI_CLIENT_ID`, `CPI_CLIENT_SECRET` |
| `-o, --out DIR` | Target folder inside a git working tree |
| `--include REGEX` / `--exclude REGEX` | Filter packages by ID |
| `--[no-]prune` | Remove folders of packages deleted from the tenant (default on). Only folders containing `package.json` are touched |
| `--allow-empty` | Accept a tenant with no packages (see *Empty tenant guard*) |
| `--commit`, `-m TEXT`, `--push` | Commit (and push) when something changed |
| `-v` | Print every artifact |

Exit codes: `0` success, `1` some artifacts failed (the rest is still written), `2` configuration or connection error, `3` tenant returned no packages but the snapshot has some (nothing changed).

## Behaviour

- **Safe updates:** each package is built in a staging folder and swapped in. If listing a package fails, its previous snapshot is kept.
- **Retries:** HTTP 429/5xx are retried with back-off. A 401 refreshes the OAuth token once.
- **Empty tenant guard:** if the tenant returns no packages while the snapshot folder already holds some, the run stops with exit code 3 and nothing is changed or committed. This usually means the trial was reset or deleted, or the service key points to a different tenant. Pass `--allow-empty` to accept an empty tenant.
- **ZIP safety:** entries that would escape the target folder are rejected.

## Not included

The API exports design-time content only. These must be recreated manually on a new tenant:

- Security material (user credentials, OAuth credentials, keystore entries)
- Configuration values changed only at deployment time
- BTP destinations and Cloud Connector settings

## Scheduled backup

`docs/backup-workflow.yml` is a ready-made workflow to copy into the backup repository's `.github/workflows/`. It runs daily, builds this tool and pushes a snapshot.
