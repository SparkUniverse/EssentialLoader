package essential

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ArchiveOperations
import org.gradle.kotlin.dsl.support.serviceOf

fun ShadowJar.configureModLauncherContainerJar(configuration: Configuration) {
    dependsOn(configuration)
    val archiveOps = project.serviceOf<ArchiveOperations>()
    from({ archiveOps.zipTree(configuration.singleFile) })
    preserveJars()

    // We cannot relocate the whole package cause the stage1.jar must remain in the same place
    // and excludes are broken: https://github.com/johnrengelman/shadow/issues/305
    // But we must relocate because modlauncher does not allow the same package to be used in different jars and yet
    // still does not have a JiJ mechanism either (we want to reserve the original package for that JiJ version).
    relocate("gg.essential.loader.stage0.EssentialLoader", "gg.essential.container.loader.stage0.EssentialLoader")
    relocate("gg.essential.loader.stage0.EssentialTransformationService", "gg.essential.container.loader.stage0.EssentialTransformationService")
    relocate("gg.essential.loader.stage0.EssentialTransformationServiceBase", "gg.essential.container.loader.stage0.EssentialTransformationServiceBase")
    relocate("gg.essential.loader.stage0.util", "gg.essential.container.loader.stage0.util")
    // FML10
    relocate("gg.essential.loader.stage0.EssentialModLocator", "gg.essential.container.loader.stage0.EssentialModLocator")

    // required for service files to be relocated
    mergeServiceFiles()

    manifest {
        attributes(
            mapOf(
                "FMLModType" to "LIBRARY"
            )
        )
    }
}
