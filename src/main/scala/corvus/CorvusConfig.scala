package corvus

case class CorvusSyncTreeConfig() {
  val syncTreeFactor: Int = 4
  val flagWidth: Int = 2
}
case class CorvusStateBusConfig() {
  val dstWidth: Int = 16
  val payloadWidth: Int = 48
  val ringNodeQueueDepth: Int = 4
}
case class CorvusConfig() {
  private def isPowerOfTwo(value: Int): Boolean =
    value > 0 && (value & (value - 1)) == 0

  // 在这里定义你的配置参数
  val numSCore: Int = 8
  val simCoreDBusAddrWidth: Int = 32
  val simCoreDBusDataWidth: Int = 64
  val nMBus: Int = 2
  val nSBus: Int = 2
  val nStateBus: Int = nMBus + nSBus
  val toCoreStateBusBufferDepth: Int = 4
  val fromCoreStateBusBufferDepth: Int = 4
  val syncTreeConfig = CorvusSyncTreeConfig()
  val stateBusConfig = CorvusStateBusConfig()
  val satelliteIRQNum: Int = 5
  val uartIRQNum: Int = 0xa

  require(isPowerOfTwo(nMBus), "nMBus must be power of 2 and > 0")
  require(nSBus > 0, "nSBus must be > 0")
  require(isPowerOfTwo(nStateBus), "nStateBus must be power of 2 and > 0")
}
