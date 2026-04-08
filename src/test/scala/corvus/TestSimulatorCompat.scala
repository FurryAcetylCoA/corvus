package corvus

import chisel3.Module
import chiseltest.ChiselScalatestTester

trait TestSimulatorCompat extends ChiselScalatestTester { self: org.scalatest.TestSuite =>
  protected def simulate[T <: Module](module: => T)(body: T => Unit): Unit = {
    test(module)(body)
  }
}