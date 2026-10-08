# Releasing

This page tells you how to publish `com.useceleris:celeris-client` to Maven Central. The `release` profile in `pom.xml` signs the artifacts with GPG and uploads them to the Central Portal. The upload waits in the portal until you publish it there.

Publish the client before the server (`sdk-java-server`). A server release needs a client version that is already on Maven Central.

## Make targets

| Target | What it does |
| --- | --- |
| `make check` | Runs the unit and integration suites. With JDK 21 or newer, it also runs Error Prone, NullAway and the format check. |
| `make format` | Applies google-java-format. |
| `make live` | Runs the live suites against a real Celeris stack. The values come from `.env`. |
| `make version VERSION=x.y.z` | Sets the project version in `pom.xml` and in `Constants.SDK_VERSION`, which generated message ids name. |
| `make install` | Installs the artifact in `~/.m2`, for a local server build. |
| `make release-check` | Builds and signs the release artifacts. It does not upload. |
| `make publish` | Signs the artifacts and uploads them to the Central Portal. |

`make release-check` and `make publish` stop when the version ends in `-SNAPSHOT`. Maven Central does not accept snapshot versions.

Set `JAVA_HOME` to select the JDK. Use JDK 21 or newer for a release, so that the analysis runs. The artifacts target Java 17 in all cases.

## One-time setup

Do these steps one time on each machine that publishes.

### 1. Verify the namespace

1. Sign in to [central.sonatype.com](https://central.sonatype.com).
2. Add the namespace `com.useceleris`.
3. Add the DNS TXT record that the portal shows to the `useceleris.com` domain.
4. Wait until the portal shows the namespace as verified.

You do this one time for the account, not for each machine.

### 2. Create a GPG signing key

Maven Central refuses files that do not have a valid signature.

1. Install GnuPG:

   ```bash
   brew install gnupg
   ```

2. Create a key. Use your name and an email address that you control:

   ```bash
   gpg --full-generate-key
   ```

3. Find the key ID:

   ```bash
   gpg --list-secret-keys --keyid-format short
   ```

4. Send the public key to a keyserver, so that Maven Central can verify the signatures. Replace `KEYID` with your key ID:

   ```bash
   gpg --keyserver keys.openpgp.org --send-keys KEYID
   ```

   On keys.openpgp.org, also confirm the email address from the message that you receive.

5. Keep a backup of the secret key in a safe place. If you lose it, you cannot sign with the same key again.

### 3. Add the Central Portal token

1. In the Central Portal, open **Account**, then **Generate User Token**.
2. Put the token in `~/.m2/settings.xml`. Do not put it in the repository. The `id` must be `central`, because the `release` profile uses that ID:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

## Release steps

Do these steps from the repository root, on a clean `main` branch.

1. Make sure that `main` has all the changes for the release:

   ```bash
   git status
   ```

2. Set the release version. Use semantic versioning:

   ```bash
   make version VERSION=0.2.0
   ```

3. Run the checks:

   ```bash
   make check
   ```

4. Optional: run the live suites against a Celeris stack:

   ```bash
   make live
   ```

5. Build and sign the artifacts without an upload. This finds GPG problems before the upload:

   ```bash
   make release-check
   ```

6. Upload the artifacts:

   ```bash
   make publish
   ```

   GPG asks for the key passphrase. Maven waits until the Central Portal validates the upload.

7. Open [central.sonatype.com](https://central.sonatype.com), then **Publish**, then **Deployments**. Examine the deployment, then click **Publish**.

   After you publish a version, you cannot change it or delete it. If the release has a problem, publish a new version.

8. Commit the version and tag the commit:

   ```bash
   git commit -am "chore: release 0.2.0"
   ```

   ```bash
   git tag -a v0.2.0 -m "v0.2.0"
   ```

9. Set the next development version and commit it:

   ```bash
   make version VERSION=0.3.0-SNAPSHOT
   ```

   ```bash
   git commit -am "chore: start 0.3.0"
   ```

10. Push the commits and the tag:

    ```bash
    git push origin main
    ```

    ```bash
    git push origin v0.2.0
    ```

The new version usually shows on Maven Central within 30 minutes. Search for it at [central.sonatype.com/artifact/com.useceleris/celeris-client](https://central.sonatype.com/artifact/com.useceleris/celeris-client).

## Then release the server

In `sdk-java-server`, after the client version shows on Maven Central:

```bash
make client-version VERSION=0.2.0
```

```bash
make version VERSION=0.2.0
```

```bash
make check
```

```bash
make publish
```

Then publish the deployment in the Central Portal, and commit, tag and push as for the client.

## Problems

| Problem | Cause and fix |
| --- | --- |
| `401 Unauthorized` on upload | The token in `~/.m2/settings.xml` is wrong or expired, or the server `id` is not `central`. Generate a new token. |
| The portal refuses the namespace | The namespace `com.useceleris` is not verified yet. Complete step 1 of the one-time setup. |
| `gpg: signing failed: Inappropriate ioctl for device` | GPG cannot show the passphrase prompt. Run `export GPG_TTY=$(tty)`, then try again. |
| `gpg: signing failed: No secret key` | No signing key on this machine. Complete step 2 of the one-time setup, or import your secret key. |
| The portal says that the signature is not valid | The public key is not on a keyserver. Send it again (step 2.4) and confirm the email address. |
| `make publish` stops with "is a snapshot" | The version ends in `-SNAPSHOT`. Run `make version VERSION=x.y.z`. |
| The server build cannot find the client version | The client version is not on Maven Central yet. Wait, or run `make install` in the client repository for a local build. |
