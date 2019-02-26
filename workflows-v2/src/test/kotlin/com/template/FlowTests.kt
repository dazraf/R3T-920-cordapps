package com.template

import com.template.flows.GetNoteSummary
import com.template.flows.IssueNote
import com.template.flows.NoteSummary
import com.template.flows.TransferNote
import net.corda.core.contracts.Amount
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.driver.NodeHandle
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class FlowTests {
  private val network = MockNetwork(MockNetworkParameters(
    networkParameters = testNetworkParameters(minimumPlatformVersion = 4),
    cordappsForAllNodes = listOf(TestCordapp.findCordapp("com.template.contracts"))
  ))
  private val a = network.createNode()
  private val b = network.createNode()

  init {
    listOf(a, b).forEach {
      it.registerInitiatedFlow(TransferNote.Acceptor::class.java)
    }
  }

  @Before
  fun setup() {
    network.runNetwork()
  }

  @After
  fun tearDown() = network.stopNodes()

  @Test
  fun `that we can issue and transfer`() {
    val f1 = a.startFlow(IssueNote(Amount.parseCurrency("1000.00 USD"))).toCompletableFuture()
    network.runNetwork(1000)
    val stateRef = f1.getOrThrow()
    checkSummary(a, NoteSummary(1_000_00, 1))

    // ENABLE for signature constraints
//    a.services.vaultService.queryBy(NoteState::class.java).states.map { it.state.constraint }.any {
//      it is SignatureAttachmentConstraint
//    }.apply {
//      assertTrue(this, "that there are states with signature attachments constraints")
//    }

    val f2 = a.startFlow(TransferNote(stateRef, b.info.legalIdentities.first())).toCompletableFuture()
    network.runNetwork(1000)
    f2.getOrThrow()
    checkSummary(b, NoteSummary(1_000_00, 1))
    checkSummary(a, NoteSummary(0, 0))

//    b.services.vaultService.queryBy(NoteState::class.java).states.map { it.state.constraint }.any {
//      it is SignatureAttachmentConstraint
//    }.apply {
//      assertTrue(this, "that there are states with signature attachments constraints")
//    }
  }

  private fun checkSummary(party: StartedMockNode, expected: NoteSummary) {
    val fActual = party.startFlow(GetNoteSummary()).toCompletableFuture()
    network.runNetwork(1000)
    val actual = fActual.getOrThrow()
    assertEquals(expected, actual, "${party.info.legalIdentities.first().name.organisation} should have $expected but instead has $actual")
  }

}