package com.upgrade.tests

import net.corda.core.internal.*
import net.corda.core.utilities.loggerFor
import org.apache.commons.lang.SystemUtils
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class JarGenerator(private val rootDirectory : Path = Paths.get(".").resolve("..").toAbsolutePath()) {
  companion object {
    private val log = loggerFor<JarGenerator>()
  }

  val jars : Map<String, Path>

  init {
    val projectRoot = findGradlewDir(rootDirectory)
    val gradlew = projectRoot / (if (SystemUtils.IS_OS_WINDOWS) "gradlew.bat" else "gradlew")
    val exitCode = ProcessBuilder(gradlew.toString(), "jar").directory(projectRoot.toFile()).inheritIO().start().waitFor()
    check(exitCode == 0) { "Unable to generate CorDapp jar from local project in $projectRoot (exit=$exitCode)" }
    jars = projectRoot.findProjectJars()
  }

  fun copyJar(moduleName: String, destinationPath: Path) {
    jars[moduleName]?.apply {
      copyTo(destinationPath / this.fileName.toString(), StandardCopyOption.REPLACE_EXISTING)
    } ?: error("cannot find module $moduleName")
  }

  private fun findGradlewDir(path: Path): Path {
    return generateSequence(path) { current ->
      if ((current / "gradlew").exists() && (current / "gradlew.bat").exists()) {
        null
      } else {
        current.parent
      }
    }.last()
  }
}

fun Path.findProjectJars(): Map<String, Path> {
  return this.list()
    .filter { it.isDirectory() }
    .flatMap { directory ->
      val libsDir = directory / "build" / "libs"
      val libs = when {
        libsDir.exists() -> libsDir.list().filter { it.toString().endsWith(".jar") }
        else -> emptyList()
      }
      libs.map { directory.fileName.toString() to it }
    }
    .toMap()
}

