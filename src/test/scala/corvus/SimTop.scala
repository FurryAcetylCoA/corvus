package corvus.test

import circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import utility._
import device.AXI4MemorySlave
import org.chipsalliance.cde.config._
import freechips.rocketchip.amba.axi4.AXI4Bundle
import chisel3.experimental.dataview._
import corvus.CorvusConfig
import corvus.Top
import device.{AXI4Flash, AXI4UART}
import corvus.axi4_network.{AXI4Network, AXI4NetworkParams, AXI4ControllerRegion}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.diplomacy.LazyModule
import utils.VerilogAXI4Record
import corvus.device.StandAloneUART
import corvus.device.StandAloneFlash
import corvus.device.StandAloneUART16550
import difftest.UARTIO
import freechips.rocketchip.diplomacy.DisableMonitors

class SimTop extends Module with RequireAsyncReset {
  implicit private val p: CorvusConfig = CorvusConfig()
  val diplomacyConfig = new Config((_, _, _) => { case LogUtilsOptionsKey => LogUtilsOptions(false, false, true) })

  val io = IO(new Bundle {
    val uart = Vec(p.numSCore + 1, new UARTIO)
  })

  val corvusTop = Module(new Top)

  // Build a small AXI4 fabric for the peripheral port and plug in UART/Flash slaves
  val peripheralParams = corvusTop.io.peripheral.params

  val peripheralDeviceBus = Module(new AXI4Network(
    AXI4NetworkParams(
      numCores = 1,
      controllers = Seq(
        AXI4ControllerRegion("uart", 0x40600000L, 0x40L),
        AXI4ControllerRegion("flash", 0x10000000L, 0x10000000L)
      ),
      core0Base = 0x0L,
      perCoreMemoryBytes = 0x80000000L,
      bundleParams = peripheralParams,
      maxTransferBytes = peripheralParams.dataBits / 8
    )
  ))

  peripheralDeviceBus.io.cores.head <> corvusTop.io.peripheral

  val uartLM = DisableMonitors(q => LazyModule(new StandAloneUART(false, 0x40600000L, 6, 64, 1)(q)))(diplomacyConfig)
  val uart = Module(uartLM.module)
  peripheralDeviceBus.io.controllers(0) <> uartLM.axi4node.get.getWrappedValue
  io.uart.head <> uart.io

  val flashLM = DisableMonitors(q => LazyModule(new StandAloneFlash(false, 0x10000000L, 28, 64, 1)(q)))(diplomacyConfig)
  val flash = Module(flashLM.module)
  peripheralDeviceBus.io.controllers(1) <> flashLM.axi4node.get.getWrappedValue

  val simUart16550LMs = Seq.fill(p.numSCore)(DisableMonitors(q => LazyModule(new StandAloneUART16550(
    useTL = false,
    baseAddress = 0x310b0000L,
    addrWidth = 12,
    dataWidth = peripheralParams.dataBits,
    hartNum = 1
  )(q)))(diplomacyConfig))
  val simUart16550s = simUart16550LMs.map(lm => Module(lm.module))
  simUart16550LMs.zip(corvusTop.io.simUart).foreach { case (lm, axi) =>
    axi <> lm.axi4node.get.getWrappedValue
  }
  simUart16550s zip io.uart.tail foreach { case (uart16550, uartIO) =>
    uart16550.io <> uartIO
  }

  val l_simAXIMems = corvusTop.memoryBus.inner.wrapper.slaveNodes.map { slaveNode =>
    AXI4MemorySlave(
      slaveNode,
      (slaveNode.portParams.head.slaves.head.address.head.mask + 1).toLong,
      useBlackBox = true,
      dynamicLatency = false
    )(diplomacyConfig)
  }
  val simAXIMems = l_simAXIMems.map(x => Module(x.module))
  l_simAXIMems.zip(corvusTop.io.memory).foreach { case (simMemIO, topMemIO) =>
    simMemIO.io_axi4.elements.head._2 <> topMemIO.viewAs[AXI4Bundle]
  }

  corvusTop.io.extIntrs := 0.U.asTypeOf(corvusTop.io.extIntrs)
  corvusTop.io.extIntrs zip simUart16550s.map(_.io_int.head.head) foreach { case (topIntrs, int) =>
    topIntrs := 0.U.asTypeOf(topIntrs)
    topIntrs(p.uartIRQNum - 1) := int
  }
  corvusTop.io.riscv_rst_vec.foreach(_ := 0x80000000L.U)

  // soc.io.rtc_clock is a div100 of soc.io.clock
  val rtcClockDiv = 100
  val rtcTickCycle = rtcClockDiv / 2
  val rtcCounter = RegInit(0.U(log2Ceil(rtcTickCycle + 1).W))
  rtcCounter := Mux(rtcCounter === (rtcTickCycle - 1).U, 0.U, rtcCounter + 1.U)
  val rtcClock = RegInit(false.B)
  when (rtcCounter === 0.U) {
    rtcClock := ~rtcClock
  }
  corvusTop.io.rtc_clock := rtcClock
}

object Elaborate extends App {
  implicit private val p: CorvusConfig = CorvusConfig()

   ChiselStage.emitSystemVerilogFile(
    new SimTop,
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
