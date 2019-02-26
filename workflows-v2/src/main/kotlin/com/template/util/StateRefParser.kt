package com.template.util

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash

object StateRefParser {
  private val re = Regex("([^()]+)\\((\\d+)\\)")

  fun parse(txt: String) : StateRef {
    val match = re.matchEntire(txt) ?: error("cannot parse \"$txt\" to <SecureHash>(<index>)")
    val secureHash = SecureHash.parse(match.groupValues[1])
    val index = match.groupValues[2].toInt()
    return StateRef(secureHash, index)
  }
}