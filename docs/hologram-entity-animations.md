# Detached entity animation findings (Minecraft 1.21.1)

Source: Mojang-mapped 1.21.1 sources in NeoForm's mergeWithSources output; GeckoLib 4.9.3 NeoForge bytecode (javap). No entity tick or aiStep is safe as a generic renderer API: they update movement, run block interactions, dispatch sounds/particles and can consult missing owner/world state. Holograms only sample animation fields.

## Vanilla animation families

* Legacy models consume walkAnimation.position(partial), speed(partial), getAttackAnim(partial), and ageInTicks. update(newSpeed, smoothing) accumulates position and interpolates speedOld; calling it only on frames/ticks visited cannot reproduce a seek. Holograms assign both speed endpoints and position from their own clock. Swing also supplies swinging, swingTime and attackAnim/oAttackAnim for renderer predicates.
* Public AnimationState objects require start/stop and accumulated time, usually managed in tick/onSyncedDataUpdated. Bat fly/rest, camel idle/sit, warden roar/sniff and frog croak are sampled explicitly. Armadillo roll-up and sniffer sniffing are supported. Breeze's idle state runs by default. These are the mob's own model keyframes. Idle age-based breathing/tails remain automatic; no random AI behaviours are invented.
* Axolotl does NOT use AnimationState; its model chooses land/water/moving/playing-dead paths and saves previous rotations in the entity. Clear that model history for deterministic seeks. Allay and goat likewise are not public AnimationState mobs in this version. Creaking does not exist in Minecraft 1.21.1.
* Bee uses onGround plus movement/age for flying wings; parrot/chicken consume flap, oFlap, flapSpeed/oFlapSpeed normally updated by aiStep. Bat requires both its resting bit and its fly/rest AnimationStates. Phantom, ghast, blaze, vex and allay have age-driven model motion; a FLY selection provides airborne state but does not manufacture missing keyframes. Ender dragon is deliberately unsupported: multipart state and flight history require a separate adapter.
* Fish/dolphin models read isInWater as well as age/limbs. Squid renderer interpolates tentacleAngle/oldTentacleAngle. Guardian needs its private client tail/spike animation fields; these are widened alongside walkAnimation and water state, without touching the world or creating water blocks.
* deathTime controls the usual renderer topple, but models can suppress limb motion through isAlive/isDeadOrDying. DEATH also sets health to zero, restoring full health on the next non-death sample. Custom death controllers/data trackers are not generic. HURT remains renderer flash/rotation; no model-part delta is expected for every mob. SNEAK and REST are often static poses; equal samples do not imply a broken pose.

## Optional animation libraries

GeckoLib 4.9.3 GeoEntity.getTick returns entity.tickCount; GeoEntityRenderer reads walkAnimation speed/position and obtains a cache manager for the instance id. GeoModel handles tickCount + partialTick, but controllers retain transition history and may clamp backward time. Unique detached entity instances/ids now isolate those caches. Setting walk speed, onGround, swimming, swinging and health supplies common predicates; aggressive flags and arbitrary mod-specific synced data are deliberately not guessed. Synthetic age supports ordinary forward playback, but deterministic reverse/scrub and triggered controller animations are NOT verified. AzureLib-style controllers have the same class of per-instance/predicate requirements; no AzureLib runtime was tested.

Primary library documentation: [GeckoLib 4 animation controllers](https://github.com/bernie-g/geckolib/wiki/The-Animation-Controller-%28Geckolib4%29). Runtime compatibility is not inferred from that documentation.

## Editor and lifetime

Studio, Creator and web entity animation menus use one per-type offered list. Group choices are a union of their entity layers. Added effects: RUN, FLY, SWIM, REST, ROAR, SNIFF, CROAK, ROLL. Idle starts automatically for models with an idle AnimationState. Existing WALK/ATTACK/HURT/DEATH/SNEAK saves remain valid. A 256-entry LRU keyed by hologram owner + layer uid bounds the entity cache; previews have separate keys. Eviction/dimension clear releases detached instances.

## Verification

First run reproduced seven axolotl backward-seek failures with retained model history. Clearing that history made all seven pass on the next run; this provides a failure-before/fix-after oracle for the seek regression.

`build.bat runHologramEntityAnimCheck` runs on rig only, writes `run-hologram-entity-anim/foxstweaks-hologram-entity-anim-check.txt` and screenshots. It uses real dispatcher rendering, reads all ModelPart transforms, compares two ages, then seeks backward. Additional parrot/chicken flight and zombie walking samples straddle a tick boundary. Core walking, flight/swimming and named keyframes must visibly change model transforms; hurt/death renderer transforms and static poses are reported separately. The harness waits for each requested pose to actually render before sampling; fixed tick delays alone can sample stale poses during catch-up ticks. The report records each offered mob × animation; omitted types are not offered. Same-type instances must have distinct object identity and entity ids. GeckoLib/AzureLib runtime coverage remains explicitly unverified without a fixture mod.

## Rig model-part matrix

`no model change` means equal ModelPart transforms at 0.15s and 0.7s; static poses, renderer flash and renderer topple are outside this oracle. `—` means not offered. A change can include natural age-driven idle motion; this does not prove that each generic effect adds a unique animation.

| Mob | WALK | RUN | ATTACK | HURT | DEATH | SNEAK | FLY | SWIM | REST | ROAR | SNIFF | CROAK | ROLL |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| zombie | works | works | works | works | works | works | — | — | — | — | — | — | — |
| skeleton | works | works | works | works | works | works | — | — | — | — | — | — | — |
| wolf | works | works | no model change | no model change | no model change | no model change | — | — | no model change | — | — | — | — |
| horse | works | works | no model change | no model change | no model change | no model change | — | — | — | — | — | — | — |
| bee | works | works | no model change | no model change | no model change | no model change | works | — | — | — | — | — | — |
| parrot | works | works | no model change | no model change | no model change | no model change | works | — | no model change | — | — | — | — |
| bat | no model change | no model change | no model change | no model change | no model change | no model change | works | — | no model change | — | — | — | — |
| chicken | works | works | no model change | no model change | no model change | no model change | works | — | — | — | — | — | — |
| phantom | works | works | works | works | works | works | works | — | — | — | — | — | — |
| allay | works | works | works | works | works | works | works | — | — | — | — | — | — |
| warden | works | works | works | works | works | works | — | — | — | works | works | — | — |
| camel | works | works | works | works | works | works | — | — | works | — | — | — | — |
| frog | works | works | no model change | no model change | no model change | no model change | — | works | — | — | — | works | — |
| axolotl | works | works | works | works | works | works | — | works | — | — | — | — | — |
| cod | works | works | works | works | works | works | — | works | — | — | — | — | — |
| squid | works | works | works | works | works | works | — | works | — | — | — | — | — |
| guardian | works | works | works | works | works | works | — | works | — | — | — | — | — |
| armadillo | works | works | no model change | no model change | no model change | no model change | — | — | — | — | — | — | works |
| breeze | works | works | works | works | works | works | — | — | — | — | — | — | — |
| sniffer | works | works | no model change | no model change | no model change | no model change | — | — | — | — | works | — | — |
| iron_golem | works | works | no model change | no model change | no model change | no model change | — | — | — | — | — | — | — |
| villager | works | works | no model change | no model change | no model change | no model change | — | — | — | — | — | — | — |

Final entity suite: ALL PASS, including backward seeks, three tick-boundary cases, and isolated entity ids. Report archived at `build/rig-reports/entity-anim/foxstweaks-hologram-entity-anim-check.txt`; 22 screenshots beside it. No GeckoLib/AzureLib mob fixture installed.

## Final checks and handoff

* `build.bat build`: PASS (local, JDK 21).
* `npm ci && npm run build`: PASS; generated web bundle committed. npm reports two existing dependency vulnerabilities (one moderate, one high); dependency versions unchanged.
* Rig `runHologramEntityAnimCheck`, `runHologramCheck`, `runHologramCreatorCheck`, `runHologramWebCheck`: ALL PASS.
* Reports and screenshots: `build/rig-reports/entity-anim/`, `build/rig-reports/run-hologram/`, `build/rig-reports/run-hologram-creator/`, `build/rig-reports/run-hologram-web/`.
* AE2-specific regression cases skipped (fixture jars absent). GeckoLib/AzureLib runtime, custom data-tracker/controller animations and ender dragon unverified.
* Rig run stopped; two task-owned PowerShell shells stalled in profile initialization were killed. No dev server launched.

Suggested CHANGELOG paragraph:

Entity hologram layers now animate using isolated per-layer entities and the hologram clock, including seekable walking/running, flying wings, swimming, vanilla idle keyframes and selected mob actions (warden roar/sniff, sniffer sniff, frog croak, armadillo curl, resting). Studio, Creator and web menus expose mob-specific choices. Fixed shared animation state, axolotl seek history and wing interpolation at tick boundaries. No entity AI or world ticks run.

Suggested AGENTS.md paragraph:

Entity animation fields are sampled by `HologramEntityLife` without `tick()`/`aiStep()`. `HologramEntities` caches at most 256 entities by hologram id + layer uid; preserve separate instances/ids for library controller caches. Walk position/speed endpoints, AnimationState accumulated time and flap endpoints must derive from the same hologram clock; axolotl model rotation history must reset before sampling. Per-type editor/web choices come from `HologramEntityLife.offered`. Dev check: `runHologramEntityAnimCheck` (`run-hologram-entity-anim/`) compares real model poses, backward seeks, tick-boundary continuity and instance isolation. See `docs/hologram-entity-animations.md` for the vanilla/library investigation and mob matrix.

Changed file spans (inclusive; generated bundle is minified):

| File | Lines |
|---|---|
| `.gitignore` | 34–35 |
| `build.gradle` | 166–171 |
| `docs/hologram-web-protocol.md` | 67–67 |
| `src/main/java/dev/watdafawx/foxstweaks/FoxsTweaks.java` | 140–142 |
| `src/main/java/dev/watdafawx/foxstweaks/client/HologramCreatorScreen.java` | 516–1115 |
| `src/main/java/dev/watdafawx/foxstweaks/client/HologramEntities.java` | 30–242 |
| `src/main/java/dev/watdafawx/foxstweaks/client/HologramEntityLife.java` | 1–131 |
| `src/main/java/dev/watdafawx/foxstweaks/client/HologramRenderer.java` | 565–565 |
| `src/main/java/dev/watdafawx/foxstweaks/client/HologramScreen.java` | 1057–1068 |
| `src/main/java/dev/watdafawx/foxstweaks/client/dev/HologramEntityAnimCheck.java` | 1–233 |
| `src/main/java/dev/watdafawx/foxstweaks/client/web/HologramWebRegistries.java` | 98–100 |
| `src/main/java/dev/watdafawx/foxstweaks/hologram/HologramEffect.java` | 125–367 |
| `src/main/resources/META-INF/accesstransformer.cfg` | 16–25 |
| `src/main/resources/assets/foxstweaks/web/assets/app.js` | 1–1 |
| `web/src/inspector.tsx` | 383–385 |
| `web/src/protocol.ts` | 154–154 |
