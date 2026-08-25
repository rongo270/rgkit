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
    implementation("io.github.rongo270:exit-reason:0.2.0")
}
```
```kotlin
import dev.rgkit.exitreason.ExitReason
```

---

## Local machine setup — already done

These are configured on Rongo's MacBook. Listed here for a rebuild or a second
machine.

| What | Where |
|---|---|
| `JAVA_HOME` → Android Studio's JDK | `~/.zshrc` (there is no system `java`) |
| GPG signing key `7724DCFE7A1E9305` | `~/.gnupg`, RSA 4096, expires 2028-08-11 |
| `signing.gnupg.keyName` | `~/.gradle/gradle.properties` |
| `pinentry-mac` for GUI passphrase prompts | `~/.gnupg/gpg-agent.conf` |

`gradlew` needs `JAVA_HOME` to boot the JVM — `org.gradle.java.home` alone is
not enough, since that only tells the *daemon* which JDK to use.

Without `pinentry-mac`, gpg falls back to a curses prompt that cannot draw in a
non-interactive shell, and signing fails.

Signing is also serialized through a Gradle shared service (`gpgSerializer` in
`build.gradle.kts`). With `org.gradle.parallel=true` the Sign tasks otherwise
hand gpg-agent several signing requests at once and it fails one of them with
`Process 'command 'gpg'' finished with non-zero exit value 2` — the failure
that blocked the 0.1.x releases. Don't remove that `usesService(...)` line;
the alternative is remembering `--no-parallel` on every release.

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

### 2. Publish the public key

The key exists locally, but Central can only verify signatures once the
**public** half is on a keyserver:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys 7724DCFE7A1E9305
```

This is permanent and public — keyservers do not support deletion. It uploads
only the public key and the `Rongo <rongoapp2026@gmail.com>` user id. The
private key never leaves `~/.gnupg`.

To recreate the key from scratch on a new machine:

```bash
brew install gnupg pinentry-mac
gpg --quick-generate-key "Rongo <rongoapp2026@gmail.com>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long   # id is after rsa4096/
```

Put the id in `~/.gradle/gradle.properties` as `signing.gnupg.keyName` —
**never** in this repo's `gradle.properties`, which is committed to git.

**Back up the private key.** If you lose it you cannot publish updates signed
by the same identity:

```bash
gpg --export-secret-keys --armor 7724DCFE7A1E9305 > ~/rgkit-signing-key.asc
```

Store that file somewhere safe and offline — anyone holding it plus the
passphrase can sign releases as you.

---

## Publishing a release

```bash
# 1. Bump the version in ./gradle.properties
#    VERSION_NAME=0.2.0

# 2. Build the signed bundle for all 13 SDKs
./gradlew centralBundle

# 3. Upload build/central/rgkit-0.2.0-bundle.zip at
#    https://central.sonatype.com -> Publish Component
```

The portal validates the bundle, shows you the 13 components, and waits. Click
**Publish** to push to Maven Central. It appears on
`repo1.maven.org` within ~15 minutes and on search.maven.org within a few hours.

**Versions are permanent.** You can never overwrite `0.2.0` once published —
only release `0.2.1`. Use the portal's **Drop** button to discard a bundle
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
