package corvus.axi4_network

import circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import chisel3.experimental.dataview._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import corvus.{CorvusConfig, Top}
import utils.VerilogAXI4Record

// The address window served by a memory controller.
case class AXI4ControllerRegion(
  name: String,
  addresses: Seq[AddressSet]
)

object AXI4ControllerRegion {
  def apply(name: String, base: BigInt, sizeBytes: BigInt): AXI4ControllerRegion = {
    AXI4ControllerRegion(name, Seq(AddressSet(base, sizeBytes - 1)))
  }
}

case class AXI4NetworkParams(
  numCores: Int,
  controllers: Seq[AXI4ControllerRegion],
  core0Base: BigInt,
  perCoreMemoryBytes: BigInt,
  bundleParams: AXI4BundleParameters,
  maxTransferBytes: Int,
  insertMasterBuffers: Boolean = true,
  insertSlaveBuffers: Boolean = true,
  coreNamePrefix: String = "core",
  controllerNamePrefix: String = "memctrl"
) {
  val beatBytes: Int = bundleParams.dataBits / 8

  val coreAddressSets: Seq[AddressSet] =
    (0 until numCores).map { idx =>
      AddressSet(core0Base + perCoreMemoryBytes * idx, perCoreMemoryBytes - 1)
    }
  coreAddressSets.zipWithIndex.foreach { case (range, idx) =>
    require(controllers.exists(_.addresses.map(_.overlaps(range)).reduce(_ || _)),
      s"Address window for core $idx (${range.base.toString(16)}-${range.max.toString(16)}) is not covered by any controller")
  }
}

class AXI4NetworkLazy(params: AXI4NetworkParams)(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()

  val masterNodes = (0 until params.numCores).map { idx =>
    val node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = s"${params.coreNamePrefix}_$idx",
        id = IdRange(0, 1 << params.bundleParams.idBits)
      ))
    )))
    if (params.insertMasterBuffers) {
      xbar := AXI4Buffer() := node
    } else {
      xbar := node
    }
    node
  }

  val slaveNodes = params.controllers.zipWithIndex.map { case (region, idx) =>
    val ctrlName = s"${params.controllerNamePrefix}_${region.name}".replaceAll("[^A-Za-z0-9_]", "_")
    val device = new SimpleDevice(ctrlName, Seq("corvus,axi4-memory"))
    val slave = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address = region.addresses,
        resources = device.reg("mem"),
        regionType = RegionType.UNCACHED,
        executable = true,
        device = Some(device),
        supportsRead = TransferSizes(1, params.maxTransferBytes),
        supportsWrite = TransferSizes(1, params.maxTransferBytes)
      )),
      beatBytes = params.beatBytes
    )))
    if (params.insertSlaveBuffers) {
      slave := AXI4Buffer() := xbar
    } else {
      slave := xbar
    }
    slave
  }

  class AXI4NetworkLazyImp(override val wrapper: AXI4NetworkLazy) extends LazyModuleImp(wrapper) {
    val masterIOs = masterNodes.zipWithIndex.map { case (node, idx) => node.makeIOs()(ValName(s"masterIO_$idx")) }
    val slaveIOs = slaveNodes.zipWithIndex.map { case (node, idx) => node.makeIOs()(ValName(s"slaveIO_$idx")) }
  }

  lazy val module: AXI4NetworkLazyImp = new AXI4NetworkLazyImp(this)
}

class AXI4Network(params: AXI4NetworkParams) extends Module {
  val inner = Module {
    val lm = DisableMonitors(p => LazyModule(new AXI4NetworkLazy(params)(p)))(Parameters.empty)
    lm.module
  }

  val masterPorts = inner.masterIOs.map(_.head)
  val slavePorts = inner.slaveIOs.map(_.head)

  val io = IO(new Bundle {
    val cores = MixedVec(masterPorts.map(_.params).map(param => Flipped(new VerilogAXI4Record(param))))
    val controllers = MixedVec(slavePorts.map(_.params).map(param => new VerilogAXI4Record(param)))
  })

  (slavePorts zip io.controllers).foreach { case (slavePort, ctrlIO) =>
    slavePort <> ctrlIO.viewAs[AXI4Bundle]
  }

  (masterPorts zip io.cores).foreach { case (masterPort, coreIO) =>
    masterPort <> coreIO.viewAs[AXI4Bundle]
  }
}

object Elaborate extends App {
  implicit val p: CorvusConfig = CorvusConfig()

  ChiselStage.emitSystemVerilogFile(
    new AXI4Network(
      AXI4NetworkParams(
        numCores = p.numSCore,
        controllers = Seq(
          AXI4ControllerRegion("mem0", 0x80000000L, p.numSCore * 0x10000000L / 2),
          AXI4ControllerRegion("mem1", 0x80000000L + p.numSCore * 0x10000000L / 2, p.numSCore * 0x10000000L / 2)
        ),
        core0Base = 0x80000000L,
        perCoreMemoryBytes = 0x10000000L,
        bundleParams = AXI4BundleParameters(
          addrBits = 48,
          dataBits = 256,
          idBits = 14
        ),
        maxTransferBytes = 64
      )
    ),
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
