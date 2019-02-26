package com.template.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StateRefParserTest {
  @Test
  fun `that we can parse a StateRef string`() {
    val str = "B1A430ECDF1E5BE8AF793B68CFB9CE01FA1ACDA814EB06EF8939403CA448AFA7(0)"
    val stateRef = StateRefParser.parse(str)
    assertEquals(str, stateRef.toString())
  }
}