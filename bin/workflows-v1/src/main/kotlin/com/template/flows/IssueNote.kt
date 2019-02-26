package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.NoteContract
import com.template.states.NoteState
import net.corda.core.contracts.*
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import java.util.*

@StartableByRPC
class IssueNote(private val amount: Amount<Currency>) : FlowLogic<String>() {
  companion object {
    object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new Note.")
    object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
    object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
    object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
      override fun childProgressTracker() = FinalityFlow.tracker()
    }

    fun tracker() = ProgressTracker(
      GENERATING_TRANSACTION,
      VERIFYING_TRANSACTION,
      SIGNING_TRANSACTION,
      FINALISING_TRANSACTION
    )
  }

  override val progressTracker = tracker()

  @Suspendable
  override fun call(): String {
    // Obtain a reference to the notary we want to use.
    val notary = serviceHub.networkMapCache.notaryIdentities[0]

    // Stage 1.
    progressTracker.currentStep = GENERATING_TRANSACTION
    // Generate an unsigned transaction.
    val owner = serviceHub.myInfo.legalIdentities.first()
    val issuedAmount = Amount(
      amount.quantity,
      Issued(
        PartyAndReference(owner, OpaqueBytes.of(1)),
        amount.token)
    )

    val noteState = NoteState(issuedAmount, owner)
    val txCommand = Command(NoteContract.Commands.Create(), noteState.participants.map { it.owningKey })
    val txBuilder = TransactionBuilder(notary)
      .addOutputState(noteState, NoteContract.ID)
      .addCommand(txCommand)

    // Stage 2.
    progressTracker.currentStep = VERIFYING_TRANSACTION
    // Verify that the transaction is valid.
    txBuilder.verify(serviceHub)

    // Stage 3.
    progressTracker.currentStep = SIGNING_TRANSACTION
    // Sign the transaction.
    val signedTransaction = serviceHub.signInitialTransaction(txBuilder)

    // Stage 4.
    progressTracker.currentStep = FINALISING_TRANSACTION
    // Notarise and record the transaction in both parties' vaults.

    val st = subFlow(FinalityFlow(signedTransaction, emptyList(), FINALISING_TRANSACTION.childProgressTracker()))
    return StateRef(st.id, 0).toString()
  }
}