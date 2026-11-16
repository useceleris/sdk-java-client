# Releasing with GitHub Actions

The [Release workflow](../.github/workflows/release.yml) uploads a signed
`com.useceleris:celeris-client` release to the Central Publisher Portal. It
does not publish the deployment in the Portal, create a Git tag, or create a
GitHub Release. Those actions happen only after a maintainer has inspected and
published the deployment.

## Required setup

The Central Portal namespace `com.useceleris` must be verified, and the
repository must be able to use these organization secrets:

| Secret | Purpose |
| --- | --- |
| `CENTRAL_TOKEN_USERNAME` | Username of a Central Portal user token. |
| `CENTRAL_TOKEN_PASSWORD` | Password of that Central Portal user token. |
| `GPG_PRIVATE_KEY` | ASCII-armored private key that signs the artifacts. |
| `GPG_PASSPHRASE` | Passphrase for that private key. |

The Portal token needs authority to publish the `com.useceleris` namespace.
The matching public primary GPG key must be available to Maven Central. Never
put any of these values in the repository or a workflow log.

## What the workflow checks

Run the workflow manually from the protected `release` branch. It first runs
the ordinary CI gates:

- `./mvnw -B -ntp verify` on Java 17, 21 and 25.
- Two clean Java 21 package builds whose jars must have identical SHA-256
  checksums.

Only after all of those jobs pass does the release job run
`./mvnw -B -ntp -Prelease clean deploy`. That command runs the full Java 21
verification again, signs the jar, sources jar, Javadoc jar and POM, and
uploads the deployment to Central Portal. The release profile has
`autoPublish=false`, so the Portal waits for a maintainer to publish it.

The workflow permits only one upload at a time and does not cancel an upload
that is already running.

## Prepare a release

The release commit must use a final, non-SNAPSHOT version. Set it with:

```sh
make version VERSION=1.0.1
```

This changes both `pom.xml` and `Constants.SDK_VERSION`. Commit and merge that
change into `release`, then select that branch when starting **Release** in
GitHub Actions.

The workflow rejects versions ending in `-SNAPSHOT` before it starts the CI
matrix or an upload. Maven Central supports snapshot uploads, but snapshots are
mutable, skip release validation and are not Maven Central releases.

Development snapshots may be published locally from `main` by a developer with
the required Central Portal token and GPG key. They are mutable development
artifacts and do not require Portal approval. `release.yml` never publishes
them: only a reviewed, final version on `release` may pass through the release
workflow and reach the manual Portal approval step.

## Publish and tag

After the workflow reports a validated deployment:

1. Inspect the deployment in Central Portal and click **Publish**.
2. Wait for its status to become **Published** and confirm that version is
   available from Maven Central.
3. Before changing the release branch to the next snapshot version, tag the
   exact release commit:

   ```sh
   git switch release
   git pull --ff-only
   git status --short
   git tag -s v1.0.1 -m "v1.0.1"
   git push origin v1.0.1
   ```

   Use `git tag -a` instead when signed Git tags are not configured. The tag
   is the audit record that connects the immutable published Maven artifact to
   its exact source commit.

4. Create the GitHub Release for `v1.0.1`.
5. Start the next development version:

   ```sh
   make version VERSION=1.0.2-SNAPSHOT
   git add pom.xml src/main/java/com/useceleris/client/Constants.java
   git commit -m "chore: start 1.0.2"
   git push origin release
   ```

## Failed or repeated runs

If a check fails before the Central upload, fix the release branch and rerun
the workflow using the same version. If the Central upload succeeds, do not
rerun it for that version: inspect, publish or drop that deployment in the
Portal first. Once a version has been published, it is immutable; never reuse
it to repair a publication or GitHub metadata mistake.
