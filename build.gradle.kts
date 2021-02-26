/*
 * Maze Game - play randomly generated mazes in your terminal!
 * Copyright (C) 2021  SirNapkin1334 / Napkin Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

import java.io.*
import java.util.Locale.ENGLISH
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.30"
}

group = "tech.napkin"
version = "0.0"

repositories {
    mavenCentral()
}

/**
 * Extract kotlin version from kotlin plugin declaration.
 *
 * We can't use a variable there (even a `const`!), because Fuck You™️.
 */
val kotlinVersion: String by extra {
    buildscript.configurations["classpath"].resolvedConfiguration.firstLevelModuleDependencies
        .find { it.moduleName == "org.jetbrains.kotlin.jvm.gradle.plugin" }?.moduleVersion ?:
    throw RuntimeException("Cannot determine kotlin version")
}

dependencies {
    testImplementation(kotlin("test-junit"))
    arrayOf(
        "net.java.dev.jna:jna-platform:5.7.0",
        "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"
    ).forEach { implementation(it) }
    implementation(kotlin("stdlib-jdk8"))
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
        kotlinOptions.includeRuntime = true
    }

    jar {
        manifest.attributes(
            "Implementation-Title"   to rootProject.name,
            "Implementation-Version" to rootProject.version,
            "Implementation-Vendor"  to "Napkin Technologies",
            "Build-Jdk"              to System.getProperty("java.version"),
            "Created-By"             to System.getProperty("java.version"),
            "Main-Class"             to "tech.napkin.games.maze.MazeGameKt",
            "Built-By" to System.getProperty("user.name").run {if(this=="sir"&&System.getProperty("os.name","").toLowerCase(ENGLISH).startsWith("linux")&&try{BufferedReader(InputStreamReader(Runtime.getRuntime().exec("cat /etc/hostname").inputStream)).lines().toArray()[0]=="napkin"}catch(e:IOException){false}){"SirNapkin1334"}else{this}}
        )
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveClassifier.set(null as String?)

        from(configurations.compileClasspath.get().files.mapNotNull {
            for (name in arrayOf("kotlin-stdlib", "jna-")) { // shade stdlib and jna
                if (it.name.startsWith(name)) {
                    return@mapNotNull if (it.isDirectory) it else zipTree(it)
                }
            }
            null
        })
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
