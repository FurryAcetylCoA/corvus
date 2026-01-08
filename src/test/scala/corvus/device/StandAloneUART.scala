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

class StandAloneUART (
  useTL: Boolean = false,
  baseAddress: BigInt,
  addrWidth: Int,
  dataWidth: Int = 64,
  hartNum: Int
)(implicit p: Parameters) extends StandAloneDevice(
  useTL, baseAddress, addrWidth, dataWidth, hartNum
) {

  def addressSet: AddressSet = AddressSet(baseAddress, (1L << addrWidth) - 1)

  private val uart = LazyModule(new AXI4UART(Seq(addressSet)))
  uart.node := TLToAXI4() := xbar

  class StandAloneUARTImp(outer: StandAloneUART)(implicit p: Parameters) extends StandAloneDeviceImp(outer) {
    val io = IO(new difftest.UARTIO)
    io <> outer.uart.module.io.extra.get
  }

  override lazy val module = new StandAloneUARTImp(this)

}
