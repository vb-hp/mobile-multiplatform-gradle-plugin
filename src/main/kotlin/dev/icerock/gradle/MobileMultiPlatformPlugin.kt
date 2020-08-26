/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.gradle

import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Sync
import org.gradle.internal.io.LineBufferingOutputStream
import org.gradle.internal.io.TextStream
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File


class MobileMultiPlatformPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val androidLibraryExtension = target.extensions.findByType(LibraryExtension::class.java)
        val kmpExtension = target.extensions.findByType(KotlinMultiplatformExtension::class.java)
        val cocoaPodsExtension = target.extensions.create("cocoaPods", CocoapodsConfig::class.java)

        if (androidLibraryExtension != null) setupAndroidLibrary(androidLibraryExtension)
        if (kmpExtension != null) setupMobileTargets(kmpExtension)

        if (kmpExtension != null) {
            cocoaPodsExtension.dependencies.forEach { pod ->
                configureCocoaPod(
                    kotlinMultiplatformExtension = kmpExtension,
                    project = target,
                    podsProject = cocoaPodsExtension.podsProject,
                    pod = pod,
                    configuration = cocoaPodsExtension.buildConfiguration
                )
            }

            kmpExtension.targets.configureEach {
                if(this !is KotlinNativeTarget) return@configureEach

                this.binaries.configureEach {
                    if(this is Framework) {
                        val framework = this as Framework
                        val linkTask = framework.linkTask
                        val syncTaskName = linkTask.name.replaceFirst("link", "sync")

                        val syncFramework = target.tasks.create(syncTaskName, Sync::class.java) {
                            group = "cocoapods"

                            from(framework.outputDirectory)
                            into(target.file("build/cocoapods/framework"))
                        }
                        syncFramework.dependsOn(linkTask)
                    }
                }
            }
        }
    }

    private fun setupAndroidLibrary(libraryExtension: LibraryExtension) {
        libraryExtension.sourceSets {
            mapOf(
                "main" to "src/androidMain",
                "release" to "src/androidMainRelease",
                "debug" to "src/androidMainDebug",
                "test" to "src/androidUnitTest",
                "testRelease" to "src/androidUnitTestRelease",
                "testDebug" to "src/androidUnitTestDebug"
            ).forEach { (name, root) ->
                getByName(name).run {
                    setRoot(root)
                }
            }
        }
    }

    private fun setupMobileTargets(kmpExtension: KotlinMultiplatformExtension) {
        kmpExtension.apply {
            android {
                publishLibraryVariants("release", "debug")
            }
            ios()
        }
    }

    private fun configureCocoaPod(
        kotlinMultiplatformExtension: KotlinMultiplatformExtension,
        project: Project,
        pod: CocoaPodInfo,
        podsProject: File,
        configuration: String
    ) {
        kotlinMultiplatformExtension.targets
            .matching { it is KotlinNativeTarget }
            .all {
                val target = this as KotlinNativeTarget
                val (buildTask, frameworksDir) = configurePodCompilation(
                    kotlinNativeTarget = target,
                    pod = pod,
                    podsProject = podsProject,
                    project = project,
                    configuration = configuration
                )

                val frameworks = target.binaries
                    .matching { it is Framework }

                frameworks.all {
                    val framework = this as Framework
                    framework.linkerOpts("-F${frameworksDir.absolutePath}")
                }

                if (pod.onlyLink) {
                    project.logger.log(LogLevel.WARN, "CocoaPod ${pod.module} integrated only in link $target stage")
                    frameworks.all { linkTask.dependsOn(buildTask) }
                    return@all
                }

                val defFile = File(project.buildDir, "cocoapods/def/${pod.module}.def")
                defFile.parentFile.mkdirs()
                defFile.writeText(
                    """
language = Objective-C
package = cocoapods.${pod.module}
modules = ${pod.module}
linkerOpts = -framework ${pod.module} 
                    """.trimIndent()
                )

                configureCInterop(
                    kotlinNativeTarget = target,
                    defFile = defFile,
                    frameworksDir = frameworksDir,
                    pod = pod,
                    project = project,
                    buildPodTask = buildTask
                )
            }
    }

    private fun configurePodCompilation(
        kotlinNativeTarget: KotlinNativeTarget,
        pod: CocoaPodInfo,
        podsProject: File,
        project: Project,
        configuration: String
    ): Pair<Task, File> {
        val arch = when (kotlinNativeTarget.konanTarget) {
            KonanTarget.IOS_ARM64 -> "iphoneos" to "arm64"
            KonanTarget.IOS_X64 -> "iphonesimulator" to "x86_64"
            else -> throw IllegalArgumentException("${kotlinNativeTarget.konanTarget} is unsupported")
        }
        val cocoapodsOutputDir = File(project.buildDir, "cocoapods")
        val capitalizedPodName = pod.capitalizedModule
        val capitalizedSdk = arch.first.capitalize()
        val capitalizedArch = arch.second.capitalize()

        val buildTask = project.tasks.create("cocoapodBuild$capitalizedPodName$capitalizedSdk$capitalizedArch") {
            group = "cocoapods"

            doLast {
                buildPod(
                    podsProject = podsProject,
                    project = project,
                    scheme = pod.scheme,
                    arch = arch,
                    outputDir = cocoapodsOutputDir,
                    configuration = configuration
                )
            }
        }
        val frameworksDir = File(cocoapodsOutputDir, "UninstalledProducts/${arch.first}")
        return buildTask to frameworksDir
    }

    private fun configureCInterop(
        kotlinNativeTarget: KotlinNativeTarget,
        defFile: File,
        frameworksDir: File,
        pod: CocoaPodInfo,
        project: Project,
        buildPodTask: Task
    ) {
        val compilation = kotlinNativeTarget.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME)
        val capitalizedPodName = pod.capitalizedModule

        val cinteropSettings = compilation.cinterops.create("cocoapod$capitalizedPodName") {
            defFile(defFile)

            compilerOpts("-F${frameworksDir.absolutePath}")
        }
        val cinteropTask = project.tasks.getByName(cinteropSettings.interopProcessingTaskName)

        cinteropTask.dependsOn(buildPodTask)
    }

    private fun buildPod(
        podsProject: File,
        project: Project,
        scheme: String,
        outputDir: File,
        arch: Pair<String, String>,
        configuration: String
    ) {
        val podsProjectPath = podsProject.absolutePath

        val podBuildDir = outputDir.absolutePath
        val derivedData = File(outputDir, "DerivedData").absolutePath
        val cmdLine = arrayOf(
            "xcodebuild",
            "-project", podsProjectPath,
            "-scheme", scheme,
            "-sdk", arch.first,
            "-arch", arch.second,
            "-configuration", configuration,
            "-derivedDataPath", derivedData,
            "SYMROOT=$podBuildDir",
            "DEPLOYMENT_LOCATION=YES",
            "SKIP_INSTALL=YES",
            "build"
        )
        cmdLine.joinToString(separator = " ").also {
            project.logger.log(LogLevel.LIFECYCLE, "cocoapod build cmd: $it")
        }

        val errOut = LineBufferingOutputStream(
            object : TextStream {
                override fun endOfStream(failure: Throwable?) {
                    if (failure != null) {
                        project.logger.log(LogLevel.ERROR, failure.message, failure)
                    }
                }

                override fun text(text: String) {
                    project.logger.log(LogLevel.ERROR, text)
                }
            }
        )
        val stdOut = LineBufferingOutputStream(
            object : TextStream {
                override fun endOfStream(failure: Throwable?) {
                    if (failure != null) {
                        project.logger.log(LogLevel.ERROR, failure.message, failure)
                    }
                }

                override fun text(text: String) {
                    project.logger.log(LogLevel.INFO, text)
                }
            }
        )
        val result = project.exec {
            workingDir = podsProject
            commandLine = cmdLine.toList()
            standardOutput = stdOut
            errorOutput = errOut
        }
        project.logger.log(LogLevel.LIFECYCLE, "xcodebuild result is ${result.exitValue}")
        result.assertNormalExitValue()
    }
}
