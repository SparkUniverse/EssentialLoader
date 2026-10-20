import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import essential.configureModLauncherContainerJar

plugins {
    id("base")
    id("essential.build-logic")
}

val stage0 by configurations.creating { isCanBeConsumed = false }

dependencies {
    stage0(project(":stage0:fml10"))
}

val jar by tasks.registering(ShadowJar::class) {
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    archiveBaseName.set("container-${project.name}")

    configureModLauncherContainerJar(stage0)

    from("resources")
}

artifacts {
    add("default", jar)
}
