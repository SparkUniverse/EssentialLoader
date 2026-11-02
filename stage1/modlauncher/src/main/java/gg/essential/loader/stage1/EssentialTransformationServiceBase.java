package gg.essential.loader.stage1;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.TypesafeMap;
import gg.essential.loader.stage1.util.DelegatingTransformationServiceBase;
import gg.essential.loader.stage1.util.FallbackTransformationServiceBase;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class EssentialTransformationServiceBase extends DelegatingTransformationServiceBase {
    protected static final String KEY_LOADED = "gg.essential.loader.stage1.loaded";

    public EssentialTransformationServiceBase(
        final ITransformationService stage0,
        final Function<String, FallbackTransformationServiceBase> newFallbackService,
        final String variant,
        final String mcVersion
    ) throws Exception {
        super(stage1 -> newFallbackService.apply(findUniqueId(stage0)));

        // Check if another transformation service has already loaded stage2 (we do not want to load it twice)
        final TypesafeMap blackboard = Launcher.INSTANCE.blackboard();
        final TypesafeMap.Key<ITransformationService> LOADED =
            TypesafeMap.Key.getOrCreate(blackboard, KEY_LOADED, ITransformationService.class);
        if (blackboard.get(LOADED).isPresent()) {
            return;
        }
        // We are doing it
        blackboard.computeIfAbsent(LOADED, __ -> this);

        final Path gameDir = Launcher.INSTANCE.environment()
            .getProperty(IEnvironment.Keys.GAMEDIR.get())
            .orElse(Paths.get("."));

        final EssentialLoader loader = EssentialLoader.getInstance(variant, "forge_" + mcVersion);
        loader.load(gameDir);

        Object stage2 = loader.getStage2();
        if (stage2 != null) {
            this.delegate = (ITransformationService) stage2.getClass()
                .getDeclaredMethod("getTransformationService")
                .invoke(stage2);
        }
    }

    private static String findUniqueId(ITransformationService stage0) {
        String[] partsArray = stage0.getClass().getName().split("\\.");
        List<String> standardParts = Arrays.asList("gg", "essential", "loader", "stage0", "EssentialTransformationService");

        Deque<String> actualStack = new ArrayDeque<>(Arrays.asList(partsArray));
        Deque<String> standardStack = new ArrayDeque<>(standardParts);

        // Remove common suffix
        while (!actualStack.isEmpty() && !standardStack.isEmpty()) {
            if (actualStack.getLast().equals(standardStack.getLast())) {
                actualStack.removeLast();
                standardStack.removeLast();
            } else {
                break;
            }
        }

        // Build unique id consisting of remaining package parts
        String uniqueId = actualStack.stream()
            .map(it -> it.toLowerCase(Locale.ROOT))
            .collect(Collectors.joining("-"));

        if (uniqueId.isEmpty()) {
            uniqueId = "stage0"; // fallback for the non-relocated stage0 (if forge ever implements JiJ)
        }

        return "essential-loader-" + uniqueId;
    }
}
