package com.upgrade.tests

import org.junit.Assert.*
import org.junit.Test

class JarGeneratorTest {
  @Test
  fun `that we can retrieve the jars for the project`() {
    val result = JarGenerator().jars
    assertEquals(5, result.size)
  }
}