# Publishing to Maven Central

All 13 SDKs publish from one Gradle build under the coordinate:

```
io.github.rongo270:<sdk-name>:<version>
```

The groupId is `io.github.rongo270`; the Kotlin packages stay `dev.rgkit.*`.
Those are independent — a groupId only has to be a namespace you can prove you
own, it does not have to match your package names.

```kotlin
dependencies {
    implementation("io.github.rongo270:exit-reason:0.1.0")
}
```
```kotlin
import dev.rgkit.exitreason.ExitReason
```

---

## Prerequisite: Java on your PATH

`java` is not installed system-wide on this Mac — only Android Studio's bundled
JDK. Add this once to `~/.gradle/gradle.properties` so Gradle always finds it:

```properties
org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home
```

Or export it per-shell before running Gradle:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

---

## One-time setup

### 1. Claim the namespace

1. Sign in at <https://central.sonatype.com> **with GitHub** (use the `rongo270`
   account — that's what proves ownership).
2. Go to **Namespaces → Add Namespace**, enter `io.github.rongo270`.
3. It shows a verification code, e.g. `a1b2c3d4e5`.
4. Create a **public** GitHub repo named exactly that code. It can be empty.
5. Back in the portal, click **Verify Namespace**.
6. Once it flips to *Verified*, delete the temporary repo.

### 2. Generate a signing key

Central rejects unsigned artifacts.

```bash
brew install gnupg

# RSA, 4096 bits. Expiry 2y is fine — you can extend it later.
gpg --full-generate-key

# Grab the LONG key id (the part after rsa4096/)
gpg --list-secret-keys --keyid-format=long

# Publish the PUBLIC half so Central can verify your signatures
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
```

Then point Gradle at it in `~/.gradle/gradle.properties` — **not** the
`gradle.properties` in this repo, that one is committed to git:

```properties
signing.gnupg.keyName=<LONG_KEY_ID>
```

gpg-agent will prompt for your passphrase when you sign.

### 3. Generate a portal token

In the portal: **Account → Generate User Token**. Keep it — you'll need it for
API uploads. For the web upload flow below you can skip this.

---

## Publishing a release

```bash
# 1. Bump the version in ./gradle.properties
#    VERSION_NAME=0.1.0

# 2. Build the signed bundle for all 13 SDKs
./gradlew centralBundle

# 3. Upload build/central/rgkit-0.1.0-bundle.zip at
#    https://central.sonatype.com -> Publish Component
```

The portal validates the bundle, shows you the 13 components, and waits. Click
**Publish** to push to Maven Central. It appears on
`repo1.maven.org` within ~15 minutes and on search.maven.org within a few hours.

**Versions are permanent.** You can never overwrite `0.1.0` once published —
only release `0.1.1`. Use the portal's **Drop** button to discard a bundle
before publishing if something looks wrong.

### Useful checks before you upload

```bash
./gradlew publishToMavenLocal    # install to ~/.m2 and test against a real app
./gradlew publishAllToStaging    # build the artifact tree without zipping it
./gradlew test                   # run unit tests
```

---

## What's wired up

| Piece | Where |
|---|---|
| Module list | `settings.gradle.kts` — project name becomes the artifactId |
| Version + POM metadata | `gradle.properties` |
| Shared publish/sign config | `build.gradle.kts` (`subprojects` block) |
| Per-module plugins | `id("maven-publish")`, `id("signing")` in each module |
| Per-module POM description | `description = "..."` in each module |

Each publication carries the `.aar`, a `-sources.jar`, a `-javadoc.jar`, the
`.pom`, Gradle `.module` metadata, checksums, and `.asc` signatures.

The javadoc jar is currently a **stub** (it just wraps the README). Central only
requires the file to exist. If you want real rendered API docs later, add the
Dokka plugin and replace the `javadocJar` task in `build.gradle.kts`.

### License

The POM declares **Apache 2.0**, and `LICENSE` at the repo root matches. If you
want a different license, change both — Central checks that the POM declares
one, and consumers check the file.
