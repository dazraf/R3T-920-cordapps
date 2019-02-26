package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.NoteContract
import com.template.states.NoteState
import com.template.util.StateRefParser
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.utilities.loggerFor


@InitiatingFlow
@StartableByRPC
class TransferNote(private val noteStateRefString: String, private val newOwner: Party) : FlowLogic<SignedTransaction>() {
  companion object {
    private val log = loggerFor<TransferNote>()
    object GENERATING_TRANSACTION : Step("Generating transaction based on new Note.")
    object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
    object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
    object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
      override fun childProgressTracker() = CollectSignaturesFlow.tracker()
    }

    object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
      override fun childProgressTracker() = FinalityFlow.tracker()
    }

    fun tracker() = ProgressTracker(
      GENERATING_TRANSACTION,
      VERIFYING_TRANSACTION,
      SIGNING_TRANSACTION,
      GATHERING_SIGS,
      FINALISING_TRANSACTION
    )
  }

  override val progressTracker = tracker()

  @Suspendable
  override fun call(): SignedTransaction {
    val noteStateRef = StateRefParser.parse(noteStateRefString)

    // Obtain a reference to the notary we want to use.
    val notary = serviceHub.networkMapCache.notaryIdentities[0]
    // Stage 1.
    progressTracker.currentStep = GENERATING_TRANSACTION

    val criteria = QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED, stateRefs = listOf(noteStateRef))

    val sar = serviceHub.vaultService.queryBy(NoteState::class.java, criteria).states.first()
    val outputState = sar.state.data.withNewOwner(newOwner)
    val txBuilder = TransactionBuilder(notary)
      .addInputState(sar)
      .addOutputState(outputState.ownableState, NoteContract.ID)
      .addCommand(outputState.command, sar.state.data.owner.owningKey, newOwner.owningKey)
    // Stage 2.
    progressTracker.currentStep = VERIFYING_TRANSACTION
    // Verify that the transaction is valid.
    txBuilder.verify(serviceHub)

    // Stage 3.
    progressTracker.currentStep = SIGNING_TRANSACTION
    // Sign the transaction.
    val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

    // Stage 4.
    progressTracker.currentStep = GATHERING_SIGS
    // Send the state to the counterparty, and receive it back with their signature.
    val otherPartySession = initiateFlow(newOwner)
    val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession)))

    // Stage 5.
    progressTracker.currentStep = FINALISING_TRANSACTION
    // Notarise and record the transaction in both parties' vaults.
    return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession)))
  }

  @InitiatedBy(TransferNote::class)
  class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
      val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
        override fun checkTransaction(stx: SignedTransaction) = requireThat {
          log.info("checking transaction on receiver node")
          val output = stx.tx.outputs.single().data
          "This must be an Note transaction." using (output is NoteState)
          val iou = output as NoteState
          "The new owner needs to be me" using (iou.owner == serviceHub.myInfo.legalIdentities.first())
        }
      }
      val txId = subFlow(signTransactionFlow).id

      return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
    }
  }
}

