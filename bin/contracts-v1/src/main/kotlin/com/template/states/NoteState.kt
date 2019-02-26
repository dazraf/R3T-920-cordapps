package com.template.states

import com.template.contracts.NoteContract
import net.corda.core.contracts.*
import net.corda.core.crypto.NullKeys
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import java.util.*

@BelongsToContract(NoteContract::class)
data class NoteState(val amount: Amount<Issued<Currency>>, override val owner: AbstractParty) : OwnableState {
  override fun withNewOwner(newOwner: AbstractParty): CommandAndState {
    val newState = this.copy(owner = newOwner)
    return CommandAndState(NoteContract.Commands.Move(), newState)
  }

  fun withoutOwner() = copy(owner = AnonymousParty(NullKeys.NullPublicKey))

  override val participants: List<AbstractParty>
    get() = listOf(owner)
}
