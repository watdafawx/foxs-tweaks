# CLAUDE.md

@AGENTS.md

[AGENTS.md](AGENTS.md) is the single source of truth for this repository and is imported above in
full. Add rules there, not here. The machine-wide rules in `~/.claude/CLAUDE.md` load separately.

The three things most likely to bite, in short:

1. **Build with `build.bat build`, not `gradlew`.** There is no system JDK on the dev machine (the
   `java` on `PATH` is a JRE 17); the script points `JAVA_HOME` at the portable JDK 21 in `toolchain/`.
2. **Relics and Apotheosis are both optional.** Never put `@EventBusSubscriber` on a class that
   imports a Relics type — FML loads annotated classes regardless of whether their dependencies
   exist. Verify changes by running a dev client with those mods removed from `run/mods/`.
3. **A dev client needs jars in `run/mods/` and must run on rig, never on FOX.** Compiling resolves
   everything from Maven (`libs/` is gone), but FML only discovers mods in `run*/mods/` and
   jar-in-jar, so drop the jars under test in first; then run it through
   `X:\Dev\tools\run-on-rig.ps1` — see [Agent harnesses](AGENTS.md#agent-harnesses).
