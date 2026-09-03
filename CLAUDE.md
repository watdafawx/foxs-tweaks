# CLAUDE.md

See **[AGENTS.md](AGENTS.md)** — it holds all the guidance for this repository and is kept as the
single source of truth. Everything there applies here.

The three things most likely to bite, in short:

1. **Build with `build.bat build`, not `gradlew`.** There is no system JDK on the dev machine; the
   script points `JAVA_HOME` at the portable one in `toolchain/`.
2. **Relics and Apotheosis are both optional.** Never put `@EventBusSubscriber` on a class that
   imports a Relics type — FML loads annotated classes regardless of whether their dependencies
   exist. Verify changes by running a dev client with those mods removed from `run/mods/`.
3. **A fresh clone will not compile until `libs/` is populated.** Those are third-party mod jars,
   deliberately not committed.
