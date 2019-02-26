package com.upgrade.tests

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Future
import kotlin.test.assertEquals

class UpgradeTests {
  companion object {
    private val log = loggerFor<UpgradeTests>()
  }
  private val bankA = TestIdentity(CordaX500Name("BankA", "", "GB"))
  private val bankB = TestIdentity(CordaX500Name("BankB", "", "US"))

  @Test
  fun `that we can issue and transfer`() {
    val jarGenerator = JarGenerator()

    val testDirectory = Files.createTempDirectory("corda-upgrade-").apply {
      toFile().deleteOnExit()
      log.info("baseDirectory is $this")
    }

    withDriver(testDirectory) {
      withNodes { partyAHandle, partyBHandle ->
        assertEquals(bankB.name, partyAHandle.resolveName(bankB.name))
        assertEquals(bankA.name, partyBHandle.resolveName(bankA.name))
        println("registered flows")
        println(partyAHandle.rpc.registeredFlows())
      }
      listOf(bankA, bankB).forEach { bank ->
        (testDirectory / bank.name.organisation / "cordapps").apply {
          deleteContents()
          jarGenerator.copyJar("contracts-v1", this)
          jarGenerator.copyJar("workflows-v1", this)
        }
      }
      withNodes { partyAHandle, partyBHandle ->
        withNodes { partyAHandle, partyBHandle ->
          // startup the nodes and then shut them down
          assertEquals(bankB.name, partyAHandle.resolveName(bankB.name))
          assertEquals(bankA.name, partyBHandle.resolveName(bankA.name))
          println("registered flows")
          println(partyAHandle.rpc.registeredFlows())
        }
      }
    }
    println("test complete")
  }

  private fun DriverDSL.withNodes(callback: (NodeHandle, NodeHandle) -> Unit) {
    val (partyAHandle, partyBHandle) = startNodes(bankA, bankB)
    callback(partyAHandle, partyBHandle)
    partyAHandle.stop()
    partyBHandle.stop()
  }

  // Runs a test inside the Driver DSL, which provides useful functions for starting nodes, etc.
  private fun withDriver(baseDirectory: Path, test: DriverDSL.() -> Unit) = driver(
    DriverParameters(isDebug = true, startNodesInProcess = true, driverDirectory = baseDirectory)
  ) { test() }

  // Makes an RPC call to retrieve another node's name from the network map.
  private fun NodeHandle.resolveName(name: CordaX500Name) = rpc.wellKnownPartyFromX500Name(name)!!.name

  // Resolves a list of futures to a list of the promised values.
  private fun <T> List<Future<T>>.waitForAll(): List<T> = map { it.getOrThrow() }

  // Starts multiple nodes simultaneously, then waits for them all to be ready.
  private fun DriverDSL.startNodes(vararg identities: TestIdentity) = identities
    .map { startNode(providedName = it.name) }
    .waitForAll()
}

operator fun Path.div(name: String) : Path {
  return this.resolve(name)
}

fun Path.deleteContents() {
  toFile().deleteContents()
}

fun File.deleteContents() {
  this.listFiles()?.let { files ->
    files.forEach {
      it.deleteRecursively()
    }
  }
}
