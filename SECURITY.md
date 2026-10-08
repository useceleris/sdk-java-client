# Security policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Report it privately through GitHub's [Security Advisories](https://github.com/useceleris/sdk-java-client/security/advisories/new) on this repository, so a fix can be prepared before anything is disclosed.

Include the affected version, the Java version, and the smallest example that reproduces the problem; a suggested fix is welcome but not required. You should get an acknowledgement within a few days, then what we found, what we intend to do and when. We credit reporters when the fix is published unless they would rather we did not.

Fixes land on the latest release; there is no long-term support branch.

## Scope

In scope: anything in this library that lets a connection read, write or impersonate beyond what its credentials grant, any leak of credentials, and any network input that can crash or corrupt a consuming application.

Out of scope: the Celeris service itself, reported through the same channel on its own repository, and findings that require the signing secret. That secret is the trust boundary, and its compromise is total by design.

## What this library does

- **It never signs.** It has no signing facility or secret-taking constructor, uses no signing primitive, and does not depend on `celeris-server`. Tests check the sources and the built jar on every build.
- **Network input is untrusted.** The decoder is bounded in fragment count and depth and exercised with mutated input; identifiers are validated.
- **Credentials stay out of errors and logs.** The library logs and prints nothing. No server text, input value or credential reaches an SDK error, and handshake errors, which quote the URL carrying the credentials, are never passed on. `Credentials` print as redacted.
- **TLS is verified** against the JVM's default trust store through the client's own `HttpClient` and SSL context, so an application replacing the JVM's default SSL context cannot loosen this connection. Redirects are never followed.

## JVM settings no library can override

These system properties apply to every `java.net.http` client in the JVM, this one included. Never set them where credentials are in use:

- `jdk.internal.httpclient.disableHostnameVerification` turns off hostname verification.
- `jdk.httpclient.HttpClient.log` and `jdk.internal.httpclient.debug` log request URIs, which carry the credentials during the handshake.
- `javax.net.debug` dumps TLS traffic.
