/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package corvus.device

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.interrupts._
import utility.IntBuffer
import device.standalone.StandAloneDevice
import device.standalone.StandAloneDeviceImp
import device.AXI4UART
import freechips.rocketchip.tilelink.TLToAXI4
import device.AXI4UART16550
import device.UART16550Params

class StandAloneUART16550 (
  useTL: Boolean = false,
  baseAddress: BigInt,
  addrWidth: Int,
  dataWidth: Int = 64,
  hartNum: Int
)(implicit p: Parameters) extends StandAloneDevice(
  useTL, baseAddress, addrWidth, dataWidth, hartNum
) {

  def addressSet: AddressSet = AddressSet(baseAddress, (1L << addrWidth) - 1)

  private val uart16550 = LazyModule(new AXI4UART16550(UART16550Params(
    address = baseAddress,
    beatBytes = dataWidth / 8
  )))
  uart16550.controlXing() := TLToAXI4() := xbar

  private val uartIntSink = IntSinkNode(IntSinkPortSimple())
  uartIntSink := uart16550.intXing()

  class StandAloneUART16550Imp(outer: StandAloneUART16550)(implicit p: Parameters) extends StandAloneDeviceImp(outer) {
    val io = IO(new difftest.UARTIO)
    val io_int = uartIntSink.makeIOs()
    io <> outer.uart16550.module.io.uart
    outer.uart16550.module.io.tx.ready := true.B
    outer.uart16550.module.io.rx.valid := false.B
    outer.uart16550.module.io.rx.bits := 0.U
  }

  override lazy val module = new StandAloneUART16550Imp(this)

}
