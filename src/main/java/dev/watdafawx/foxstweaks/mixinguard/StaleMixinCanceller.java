package dev.watdafawx.foxstweaks.mixinguard;

import com.bawnorton.mixinsquared.api.MixinCanceller;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns off another mod's mixin when it calls a class that the mod it patches no longer has.
 *
 * <p>Addons often mix into a parent mod's internals (ExtendedAE Plus into JEI, AE2 addons into AE2).
 * When the parent updates and deletes or moves one of those classes, Mixin's pre-processor throws
 * {@code ClassNotFoundException} the moment the target class loads. That kills the target class
 * itself, so the parent mod breaks too (JEI's bookmark overlay, EMI's "Error Rendering"). Losing the
 * one addon feature is strictly better, so such a mixin is cancelled up front with a log line.
 *
 * <p>Deliberately narrow, so a lookup quirk can never cancel a healthy mixin:
 * <ul>
 *   <li>at least one target class must be found, which proves the lookup reaches that mod's jar;</li>
 *   <li>only method and field owners count - Mixin resolves every one of those while preparing,
 *       so a missing one is a guaranteed crash, not a maybe;</li>
 *   <li>the missing owner must sit in the same package root as a target (e.g. both
 *       {@code mezz.jei.*}), i.e. the patched mod changed its own internals.</li>
 * </ul>
 *
 * <p>Loaded by MixinSquared through {@code META-INF/services} during mixin config preparation, long
 * before Minecraft classes exist. It must touch nothing but Mixin and ASM.
 */
public final class StaleMixinCanceller implements MixinCanceller {
    private static final ILogger LOG = MixinService.getService().getLogger("foxstweaks");

    private final Map<String, Boolean> exists = new ConcurrentHashMap<>();

    public StaleMixinCanceller() {
        LOG.info("Stale mixin guard active");
    }

    @Override
    public boolean shouldCancel(List<String> targetClassNames, String mixinClassName) {
        List<String> targets = targetClassNames.stream()
                .map(StaleMixinCanceller::internal)
                .filter(this::exists)
                .toList();
        // No target present: Mixin skips the mixin on its own, nothing to protect.
        if (targets.isEmpty()) return false;

        String mixin = internal(mixinClassName);
        String missing;
        try {
            missing = firstMissingOwner(mixin, targets);
        } catch (IOException | RuntimeException e) {
            return false; // Can't read it: leave the decision to Mixin.
        }
        if (missing == null) return false;

        LOG.warn("Disabled mixin {} (targets {}): it calls {}, which no longer exists. "
                + "The mod it patches has changed; update the mod that owns this mixin.",
                mixinClassName, targetClassNames, missing.replace('/', '.'));
        return true;
    }

    private String firstMissingOwner(String mixin, List<String> targets) throws IOException {
        byte[] bytes;
        try (InputStream in = MixinService.getService().getResourceAsStream(mixin + ".class")) {
            if (in == null) return null;
            bytes = in.readAllBytes();
        }

        String[] found = {null};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        check(owner);
                    }

                    @Override
                    public void visitFieldInsn(int op, String owner, String n, String d) {
                        check(owner);
                    }

                    private void check(String owner) {
                        if (found[0] != null || owner.equals(mixin) || owner.startsWith("[")) return;
                        if (!sharesRoot(owner, targets)) return;
                        if (!exists(owner)) found[0] = owner;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private boolean exists(String internalName) {
        return exists.computeIfAbsent(internalName, n -> {
            try (InputStream in = MixinService.getService().getResourceAsStream(n + ".class")) {
                return in != null;
            } catch (IOException e) {
                return true; // Unknown counts as present: never cancel on a failed read.
            }
        });
    }

    /** True when {@code owner} and some target share their first two package segments. */
    private static boolean sharesRoot(String owner, List<String> targets) {
        String root = root(owner);
        return root != null && targets.stream().anyMatch(t -> root.equals(root(t)));
    }

    private static String root(String internalName) {
        int first = internalName.indexOf('/');
        if (first < 0) return null;
        int second = internalName.indexOf('/', first + 1);
        return second < 0 ? null : internalName.substring(0, second);
    }

    private static String internal(String name) {
        return name.replace('.', '/');
    }
}
