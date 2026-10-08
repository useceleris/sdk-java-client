# Conventions

Simplicity and maintainability are paramount. The surface contract lives in the specifications repository, and the JavaScript client is the reference implementation this package mirrors.

## Names

Use full domain words: `releaseInterest`, `queueInterestSync`, `segmentId`, `credentialProvider`. No abbreviations, and no single-letter names outside tight loops. A comment exists only to state a constraint the code cannot show.

## Simplicity over abstraction

Solve the problem in front of you with the simplest readable structure. No registries, factories, event frameworks, dependency-injection containers or wrapper layers. A helper type earns its place only by removing real, present duplication (`ListenerSet` qualifies; a "Manager" does not). Prefer a method on an existing type over a new type.

Exactly four seams exist, all passed through `CelerisClient`'s package-private constructor: `Dialer`, so tests substitute an in-memory peer for the JDK's WebSocket; `Timers`, so tests own the clock; the worker `Executor`; and the jitter `DoubleSupplier`.

## Maintainability

- One exported package, `com.useceleris.client`; package-private classes are its internals. Small files with one responsibility, named for it.
- Every fixed value lives in `Constants` as a package-private `static final` in SCREAMING_SNAKE_CASE. Validation rules stay beside the code that uses them.
- Delete code in the same change that obsoletes it.
- Every public identifier traces to a requirement or recorded decision (SEG-01, DEV-01, REV-01, ...). The surface is pinned in one list in `PackageTest`.
- Errors carry stable codes and messages naming what failed and which rule or limit it broke. Never interpolate received values, input values, credentials or server text, and never set a cause: handshake errors quote the credential URL.
- Threads:
  - One `ReentrantLock` per channel guards the channel, its segments, its connection and its command queue.
  - The lock is never held while user code runs, while a user-visible future completes, while a `WebSocket` method is called, or while anything blocks.
  - The JDK's listener callbacks never run user code; they hand complete messages to the channel.
  - Every continuation on a JDK future runs on the client's workers.
  - Futures the SDK returns complete on the workers, each as its own task, independently of event delivery and of one another.
- Tests are deterministic (the fakes in `TestRuntime`, fixed jitter), grouped by behaviour in topic files, and catch package-owned defects only. Golden vectors are hand-authored, never produced by the code under test. Real-transport suites carry the `transport` tag.
- Before completion, review the full diff for anything deletable without weakening behaviour or tests.

## Layout

Leave one blank line after every block (`if`, `for`, `switch`, `try`, a lambda spanning lines) before the next statement, except before `else`, `catch` or `finally`, or at the end of an enclosing block. Separate the members of a type with a blank line, except consecutive fields.

Every method, constructor and named type whose body spans lines ends with a marker that names it: `} // end method name`, `} // end constructor Name`, `} // end class Name`, and likewise `interface`, `enum`, `record` and `annotation`. Lambdas and anonymous classes carry none. A marker must fit in 100 columns, or google-java-format moves it to the next line; shorten the name instead.

`LayoutTest` checks these rules on every source, test and example file and names each violation by file and line. google-java-format owns everything else; `./mvnw spotless:apply` applies it.

## Checks

- `./mvnw verify` runs everything, and CI repeats it on Java 17, 21 and 25: compilation with every lint as an error; the linters Error Prone and NullAway and the formatter google-java-format on Java 21 and newer; the layout rules (`LayoutTest`); the unit and transport suites; the package checks (pinned surface, no signing facility reachable, nothing printed, every Java block in the README compiled by `ReadmeTest`); and checks of the built jar.
- `./mvnw verify -Plive` runs the acceptance suites against a real Celeris stack, reading `CELERIS_WS_URL`, `CELERIS_CLIENT_ID` and `CELERIS_SIGNING_SECRET` from a gitignored `.env` or the environment.
- `./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage` runs mutation testing.
