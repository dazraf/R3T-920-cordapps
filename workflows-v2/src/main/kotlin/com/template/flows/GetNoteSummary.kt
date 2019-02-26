package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.states.NoteState
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor

/**
 * This data structure remains constant across both versions
 * we used to have a standard query result that will determine if the upgrade has happened
 */
@CordaSerializable
data class NoteSummary(
  /**
   * total balance of the notes
   */
  val balance: Long = 0,
  /**
   * number of notes with a non null description
   */
  val descriptionCount : Int = 0
)

@StartableByRPC
class GetNoteSummary : FlowLogic<NoteSummary>() {
  companion object {
    private val log = loggerFor<GetNoteSummary>()
    object RETRIEVING_BALANCE : ProgressTracker.Step("Retrieving balance for notes")
    object BALANCE_RETRIEVED : ProgressTracker.Step("Balance retrieved")

    fun tracker() = ProgressTracker(
      RETRIEVING_BALANCE,
      BALANCE_RETRIEVED
    )
  }

  override val progressTracker = tracker()

  @Suspendable
  override fun call(): NoteSummary {
    progressTracker.currentStep = RETRIEVING_BALANCE
    log.info("getting balance for node ${serviceHub.myInfo.legalIdentities.first().name}")

    val qc = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)

    val result = serviceHub.vaultService.queryBy(NoteState::class.java, qc)
      .states
      .apply {
        log.info("states:")
        this.forEach {
          log.info(it.toString())
        }
      }
      .map { it.state.data }
      .fold(NoteSummary()) { acc, value ->
        val descriptionCount = if (value.description != null) {
          1
        } else {
          0
        }
        acc.copy(balance = acc.balance + value.amount.quantity, descriptionCount = acc.descriptionCount + descriptionCount)
      }

    progressTracker.currentStep = BALANCE_RETRIEVED
    return result
  }
}