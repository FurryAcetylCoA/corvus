package corvus.xiangshan

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import xiangshan._
import utils._
import utility._
import huancun.{HCCacheParameters, HCCacheParamsKey, HuanCun, PrefetchRecv, TPmetaResp}
import system._
import device._
import chisel3.stage.ChiselGeneratorAnnotation
import org.chipsalliance.cde.config._
import freechips.rocketchip.devices.debug.DebugModuleKey
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.jtag.JTAGIO
import chisel3.experimental.annotate
import sifive.enterprise.firrtl.NestedPrefixModulesAnnotation

import device.SYSCNTConsts._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.regmapper._
import xiangshan.backend.fu.PMAConst

abstract class BaseXSSoc()(implicit p: Parameters) extends LazyModule
  with HasSoCParameter
  with BindingScope

class XSCorvusTop()(implicit p: Parameters) extends BaseXSSoc()
{
  val socMisc = LazyModule(new SoCMisc())

  println(s"FPGASoC cores: 1 banks: $L3NBanks block size: $L3BlockSize bus size: $L3OuterBusWidth")

  val core_with_l2 = LazyModule(new XSTile()(p.alter((site, here, up) => {
    case XSCoreParamsKey => tiles.head
    case PerfCounterOptionsKey => up(PerfCounterOptionsKey).copy(perfDBHartID = tiles.head.HartId)
  })))

  val l3cacheOpt = soc.L3CacheParamsOpt.map(l3param =>
    LazyModule(new HuanCun()(new Config((_, _, _) => {
      case HCCacheParamsKey => l3param.copy(
        hartIds = tiles.map(_.HartId),
        FPGAPlatform = debugOpts.FPGAPlatform
      )
      case MaxHartIdBits => p(MaxHartIdBits)
      case LogUtilsOptionsKey => p(LogUtilsOptionsKey)
      case PerfCounterOptionsKey => p(PerfCounterOptionsKey)
    })))
  )

  // receive all prefetch req from cores
  val memblock_pf_recv_nodes: Option[BundleBridgeSink[PrefetchRecv]] = core_with_l2.core_l3_pf_port.map(
	  _ => BundleBridgeSink(Some(() => new PrefetchRecv))
  )

  val l3_pf_sender_opt = soc.L3CacheParamsOpt.getOrElse(HCCacheParameters()).prefetch match {
    case Some(pf) => Some(BundleBridgeSource(() => new PrefetchRecv))
    case None => None
  }
  val clintIntNode = IntSourceNode(IntSourcePortSimple(1, 1, 2))
  val debugIntNode = IntSourceNode(IntSourcePortSimple(1, 1, 1))
  val plicIntNode = IntSourceNode(IntSourcePortSimple(1, 2, 1))
  val nmiIntNode = IntSourceNode(IntSourcePortSimple(1, 1, (new NonmaskableInterruptIO).elements.size))
  val beuIntNode = IntSinkNode(IntSinkPortSimple(1, 1))
  val l3IntNode = IntSinkNode(IntSinkPortSimple(1, 1))
  val clint = InModuleBody(clintIntNode.makeIOs())
  val debug = InModuleBody(debugIntNode.makeIOs())
  val plic = InModuleBody(plicIntNode.makeIOs())
  val nmi = InModuleBody(nmiIntNode.makeIOs())
  val beu = InModuleBody(beuIntNode.makeIOs())
  val l3 = InModuleBody(l3IntNode.makeIOs())

  core_with_l2.clint_int_node := clintIntNode
  core_with_l2.plic_int_node :*= plicIntNode
  core_with_l2.debug_int_node := debugIntNode
  core_with_l2.nmi_int_node := nmiIntNode
  beuIntNode := core_with_l2.beu_int_source
  socMisc.peripheral_ports := core_with_l2.tl_uncache
  core_with_l2.memory_port.foreach(port => socMisc.core_to_l3_ports :=* port)
  memblock_pf_recv_nodes.map(recv => {
    println(s"Connecting Core_0's L1 pf source to L3!")
    recv := core_with_l2.core_l3_pf_port.get
  })
  l3cacheOpt.map(_.ctlnode.map(_ := socMisc.peripheralXbar.get))
  l3cacheOpt.map(_.intnode.map(l3IntNode := IntBuffer() := _))

  val core_rst_nodes = if(l3cacheOpt.nonEmpty && l3cacheOpt.get.rst_nodes.nonEmpty){
    l3cacheOpt.get.rst_nodes.get
  } else {
    Seq(BundleBridgeSource(() => Reset()))
  }

  core_rst_nodes.zip(Seq(core_with_l2.core_reset_sink)).foreach({
    case (source, sink) =>  sink := source
  })

  l3cacheOpt match {
    case Some(l3) =>
      socMisc.l3_out :*= l3.node :*= socMisc.l3_banked_xbar.get
      l3.pf_recv_node.map(recv => {
        println("Connecting L1 prefetcher to L3!")
        recv := l3_pf_sender_opt.get
      })
      l3.tpmeta_recv_node.foreach(recv => {
        println(s"Connecting core_0\'s L2 TPmeta request to L3!")
        recv := core_with_l2.core_l3_tpmeta_source_port.get
      })
      l3.tpmeta_send_node.foreach(send => {
        val broadcast = LazyModule(new ValidIOBroadcast[TPmetaResp]())
        broadcast.node := send
        println(s"Connecting core_0\'s L2 TPmeta response to L3!")
        core_with_l2.core_l3_tpmeta_sink_port.get := broadcast.node
      })
    case None =>
  }

  class XSCorvusTopImp(wrapper: XSCorvusTop) extends LazyRawModuleImp(wrapper)
  {
    soc.XSTopPrefix.foreach { prefix =>
      val mod = this.toNamed
      annotate(this)(Seq(NestedPrefixModulesAnnotation(mod, prefix, true)))
    }

    val dma = IO(Flipped(new VerilogAXI4Record(socMisc.dma.elts.head.params)))
    val peripheral = IO(new VerilogAXI4Record(socMisc.peripheral.elts.head.params))
    val memory = IO(new VerilogAXI4Record(socMisc.memory.elts.head.params))

    socMisc.dma.elements.head._2 <> dma.viewAs[AXI4Bundle]
    dontTouch(dma)

    memory.viewAs[AXI4Bundle] <> socMisc.memory.elements.head._2
    peripheral.viewAs[AXI4Bundle] <> socMisc.peripheral.elements.head._2

    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(AsyncReset())
      val hartId = Input(UInt(p(MaxHartIdBits).W))
	    val clintTime = Input(ValidIO(UInt(64.W)))
      val riscv_halt = Output(Bool())
      val riscv_critical_error = Output(Bool())
      val riscv_rst_vec = Input(UInt(soc.PAddrBits.W))
    })

    val reset_sync = withClockAndReset(io.clock, io.reset) { ResetGen() }

    // override LazyRawModuleImp's clock and reset
    childClock := io.clock
    childReset := reset_sync

    // input
    dontTouch(io)
    dontTouch(memory)

    core_with_l2.module.io.hartId := io.hartId
    core_with_l2.module.io.msiInfo := 0.U.asTypeOf(core_with_l2.module.io.msiInfo)
    core_with_l2.module.io.clintTime := io.clintTime
    io.riscv_halt := core_with_l2.module.io.cpu_halt
    io.riscv_critical_error := core_with_l2.module.io.cpu_crtical_error
    val traceInterface = core_with_l2.module.io.traceCoreInterface
    core_with_l2.module.io.traceCoreInterface.fromEncoder := 0.U.asTypeOf(traceInterface.fromEncoder)

    core_with_l2.module.io.dft.foreach(dontTouch(_) := DontCare)
    core_with_l2.module.io.dft_reset.foreach(dontTouch(_) := DontCare)
    core_with_l2.module.io.reset_vector := io.riscv_rst_vec

    if(l3cacheOpt.isEmpty || l3cacheOpt.get.rst_nodes.isEmpty){
      // tie off core soft reset
      for(node <- core_rst_nodes){
        node.out.head._1 := false.B.asAsyncReset
      }
    }

    l3cacheOpt match {
      case Some(l3) =>
        l3.pf_recv_node match {
          case Some(recv) =>
            l3_pf_sender_opt.get.out.head._1.addr_valid := VecInit(memblock_pf_recv_nodes.get.in.head._1.addr_valid).asUInt.orR
            when(memblock_pf_recv_nodes.get.in.head._1.addr_valid) {
              l3_pf_sender_opt.get.out.head._1.addr := memblock_pf_recv_nodes.get.in.head._1.addr
              l3_pf_sender_opt.get.out.head._1.l2_pf_en := memblock_pf_recv_nodes.get.in.head._1.l2_pf_en
            }
          case None =>
        }
        l3.module.io.debugTopDown.robHeadPaddr := Seq(core_with_l2.module.io.debugTopDown.robHeadPaddr)
        Seq(core_with_l2).zip(l3.module.io.debugTopDown.addrMatch).foreach { case (tile, l3Match) => tile.module.io.debugTopDown.l3MissMatch := l3Match }
        core_with_l2.module.io.l3Miss := l3.module.io.l3Miss
      case None =>
    }

    l3cacheOpt match {
      case None =>
        core_with_l2.module.io.debugTopDown.l3MissMatch := false.B
        core_with_l2.module.io.l3Miss := false.B
      case _ =>
    }

    withClockAndReset(io.clock, reset_sync) {
      // Modules are reset one by one
      // reset ----> SYNC --> {SoCMisc, L3 Cache, Cores}
      val resetChain = Seq(Seq(socMisc.module) ++ l3cacheOpt.map(_.module))
      ResetGen(resetChain, reset_sync, !debugOpts.ResetGen)
      // Ensure that cores could be reset when l3cacheOpt.isEmpty.
      val syncResetCores = if(l3cacheOpt.nonEmpty) l3cacheOpt.map(_.module).get.reset.asBool else socMisc.module.reset.asBool
      Seq(ResetGen(Seq(Seq(core_with_l2.module)), (syncResetCores).asAsyncReset, !debugOpts.ResetGen))
    }

  }

  lazy val module = new XSCorvusTopImp(this)
}

class SoCMisc()(implicit p: Parameters) extends BaseSoC
  with HaveAXI4MemPort
  with PMAConst
  with HaveAXI4PeripheralPort
  with HaveSlaveAXI4Port
{
  override def onChipPeripheralRanges: Map[String, AddressSet] = Map(
    "BEU"   -> soc.BEURange,
    "PLL"   -> soc.PLLRange,
    "UARTLITE" -> soc.UARTLiteRange,
    "UART16550" -> soc.UART16550Range,
    "DEBUG" -> p(DebugModuleKey).get.address,
    "MMPMA" -> AddressSet(mmpma.address, mmpma.mask)
  ) ++ (
    if (soc.L3CacheParamsOpt.map(_.ctrl.isDefined).getOrElse(false))
      Map("L3CTL" -> AddressSet(soc.L3CacheParamsOpt.get.ctrl.get.address, 0xffff))
    else
      Map()
  )

  val peripheral_ports = TLTempNode()
  val core_to_l3_ports = TLTempNode()

  val l3_in = TLTempNode()
  val l3_out = TLTempNode()

  if (l3_banked_xbar.isDefined) {
    l3_in :*= TLEdgeBuffer(_ => true, Some("L3_in_buffer")) :*= l3_banked_xbar.get
    l3_banked_xbar.get := TLBuffer.chainNode(2) := l3_xbar.get
  }
  bankedNode match {
    case Some(bankBinder) =>
      bankBinder :*= TLLogger("MEM_L3", !debugOpts.FPGAPlatform && debugOpts.AlwaysBasicDB) :*= l3_out
    case None =>
  }

  if(soc.L3CacheParamsOpt.isEmpty){
    l3_out :*= l3_in
  }

  peripheralXbar.get := TLBuffer.chainNode(2, Some("L2_to_L3_peripheral_buffer")) := peripheral_ports

  l3_banked_xbar.get :=*
    TLLogger(s"L3_L2_0", !debugOpts.FPGAPlatform && debugOpts.AlwaysBasicDB) :=*
    TLBuffer() :=
    core_to_l3_ports

  class SoCMiscImp(wrapper: LazyModule) extends LazyModuleImp(wrapper)

  lazy val module = new SoCMiscImp(this)
}
