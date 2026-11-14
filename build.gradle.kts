import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// CLion 2026.2 runs on JBR 25, so platform bytecode is Java 25 and the toolchain must match.
// 21 produced a `verifyPluginProjectConfiguration` warning: "Java sourceCompatibility is set to
// '21', but IntelliJ Platform '2026.2.4.1' requires Java '25'".
kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.jfreechart)

    intellijPlatform {
        // A local CLion installation is used when `localPlatformPath` points at one: the plugin is
        // then compiled against the exact build it has to run on. In CI there is no such
        // installation, so the CLion distribution is downloaded from the JetBrains repository.
        // A path that does not exist (another contributor's machine, a moved installation) falls
        // back to the download instead of failing with an unresolvable dependency.
        val localPlatform = providers.gradleProperty("localPlatformPath").getOrElse("").trim()
        val localPlatformDir = localPlatform.takeIf(String::isNotEmpty)?.let(::file)
        if (localPlatformDir != null && localPlatformDir.isDirectory) {
            local(localPlatformDir)
        } else {
            if (localPlatformDir != null) {
                logger.lifecycle(
                    "localPlatformPath=$localPlatform does not exist — downloading the platform instead.",
                )
            }
            create(
                providers.gradleProperty("platformType").get(),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',').filter(String::isNotBlank) })
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        // id, name and vendor stay in META-INF/plugin.xml; these three are patched in at build time.
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    // Signed builds are produced only when the signing secrets are present (see release.yml).
    // Without them the resulting jar is unsigned, which the Marketplace accepts as well.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            // Verify against the exact CLion build the plugin is compiled against (CL-262.11879.106
            // locally, the same 2026.2.4.1 in CI). `recommended()` would additionally pull CLion EAP
            // builds such as 2026.3.0.1, which a plugin targeting 262 is not expected to match, and
            // it makes the check download two extra IDE distributions. Marketplace runs its own
            // compatibility check against newer builds at upload time.
            current()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    test {
        useJUnit()
    }

    verifyPlugin {
        // The verifier keeps its downloads and caches in `~/.pluginVerifier` by default, which is
        // outside the project and not writable in every environment. Keep it inside the build tree
        // so CI and local runs use the same cached directory.
        jvmArgs(
            "-Dplugin.verifier.home.dir=" +
                layout.buildDirectory.dir("pluginVerifierHome").get().asFile.absolutePath,
        )

        // Only the compatibility problems that matter for a release; the full report is written
        // to build/reports/pluginVerifier either way.
        failureLevel.set(
            listOf(
                VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
                VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
                VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
                VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
                VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
                VerifyPluginTask.FailureLevel.PLUGIN_STRUCTURE_WARNINGS,
                VerifyPluginTask.FailureLevel.SCHEDULED_FOR_REMOVAL_API_USAGES,
            )
        )
    }
}
