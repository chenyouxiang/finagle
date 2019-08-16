package com.twitter.finagle.toggle

import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.FunSuite
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class NullToggleMapTest extends FunSuite with GeneratorDrivenPropertyChecks {

  private val IntGen = arbitrary[Int]

  test("apply") {
    val toggle = NullToggleMap("hi")
    forAll(IntGen) { i =>
      assert(!toggle.isDefinedAt(i))
    }
  }

  test("iterator") {
    assert(NullToggleMap.iterator.isEmpty)
  }

}
