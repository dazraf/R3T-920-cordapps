package com.upgrade.tests

import com.template.flows.GetNoteSummary
import com.template.flows.IssueNote
import com.template.flows.NoteSummary
import com.template.flows.TransferNote
import com.template.states.NoteState
import net.corda.core.contracts.Amount
import net.corda.core.contracts.SignatureAttachmentConstraint
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.TestIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.internal.waitForShutdown
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
    val jarMeister = JarMeister()

    val testDirectory = Files.createTempDirectory("corda-upgrade-").apply {
      toFile().deleteOnExit()
      log.info("baseDirectory is $this")
    }

    withDriver(testDirectory) {
      log.info("--------------------- Starting initial cluster --------------------")
      withNodes { partyA, partyB ->
        assertEquals(bankB.name, partyA.resolveName(bankB.name))
        assertEquals(bankA.name, partyB.resolveName(bankA.name))
        log.info("registered flows")
        partyA.rpc.registeredFlows().forEach(::println)
        log.info("--------------------- Shutting down cluster --------------------")
      }
      log.info("--------------------- Setting the v1 Cordapps --------------------")
      listOf(bankA, bankB).forEach { bank ->
        (testDirectory / bank.name.organisation / "cordapps").apply {
          deleteContents()
          jarMeister.copyJar("contracts-v1", this)
          jarMeister.copyJar("workflows-v1", this)
        }
      }
      log.info("--------------------- Starting cluster with v1 Cordapps --------------------")
      val state2 = withNodes { partyA, partyB ->
        log.info("registered flows")
        partyA.rpc.registeredFlows().forEach(::println)

        checkSummary(partyA, NoteSummary(0, 0))
        checkSummary(partyB, NoteSummary(0, 0))

        val state1 = partyA.rpc.startFlow(::IssueNote, Amount.parseCurrency("1000 USD")).returnValue.getOrThrow()
        checkSummary(partyA, NoteSummary(1_000_00, 0))
        checkSummary(partyB, NoteSummary(0, 0))
        checkForAttachmentConstraints(partyA, partyB)

        val state2 = partyA.rpc.startFlow(::TransferNote, state1, partyB.nodeInfo.legalIdentities.first()).returnValue.getOrThrow()
        checkSummary(partyA, NoteSummary(0, 0))
        checkSummary(partyB, NoteSummary(1_000_00, 0))
        checkForAttachmentConstraints(partyA, partyB)

        log.info("--------------------- Shutting down cluster --------------------")
        state2
      }
      log.info("--------------------- Setting the v2 Cordapps --------------------")
      listOf(bankA, bankB).forEach { bank ->
        (testDirectory / bank.name.organisation / "cordapps").apply {
          deleteContents()
          jarMeister.copyJar("contracts-v2", this)
          jarMeister.copyJar("workflows-v2", this)
        }
      }
      log.info("--------------------- Starting cluster with v2 Cordapps --------------------")
      withNodes { partyA, partyB -> println("registered flows")
        partyA.rpc.registeredFlows().forEach(::println)
        checkSummary(partyA, NoteSummary(0, 0))
        checkSummary(partyB, NoteSummary(1_000_00, 1))

        partyB.rpc.startFlow(::TransferNote, state2, partyA.nodeInfo.legalIdentities.first()).returnValue.getOrThrow()
        checkSummary(partyA, NoteSummary(1_000_00, 1))
        checkSummary(partyB, NoteSummary(0, 0))
        log.info("--------------------- Shutting down cluster --------------------")
      }
    }
//    testDirectory.deleteRecursively()
  }

  private fun checkForAttachmentConstraints(partyA: NodeHandle, partyB: NodeHandle) {
    partyA.rpc.vaultQuery(NoteState::class.java).states.all { it.state.constraint is SignatureAttachmentConstraint }
    partyB.rpc.vaultQuery(NoteState::class.java).states.all { it.state.constraint is SignatureAttachmentConstraint }
  }

  private fun checkSummary(party: NodeHandle, expected: NoteSummary) {
    val actual = party.rpc.startFlow(::GetNoteSummary).returnValue.getOrThrow()
    assertEquals(expected, actual, "${party.nodeInfo.legalIdentities.first().name.organisation} should have $expected but instead has $actual")
  }

  private fun <T> DriverDSL.withNodes(callback: (NodeHandle, NodeHandle) -> T) : T {
    val (partyAHandle, partyBHandle) = startNodes(bankA, bankB)
    val result = callback(partyAHandle, partyBHandle)
    listOf(partyAHandle, partyBHandle).forEach { it.rpc.shutdown() }
    listOf(partyAHandle, partyBHandle).forEach { it.rpc.waitForShutdown() }
    partyAHandle.stop()
    partyBHandle.stop()
    return result
  }

  // Runs a test inside the Driver DSL, which provides useful functions for starting nodes, etc.
  private fun withDriver(baseDirectory: Path, test: DriverDSL.() -> Unit) = driver(
    DriverParameters(
      isDebug = true,
      startNodesInProcess = false,
      driverDirectory = baseDirectory,
      notarySpecs = listOf(NotarySpec(name = DUMMY_NOTARY_NAME, validating = false)),
      inMemoryDB = false,
      networkParameters = testNetworkParameters(notaries = emptyList(), minimumPlatformVersion = 4)
    )
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

operator fun Path.div(name: String): Path {
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
