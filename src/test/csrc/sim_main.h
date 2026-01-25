#pragma once

#include <cstdint>
#include <iostream>
#include <string>
#include <type_traits>
#include <utility>

struct SimConfig {
  std::string image;
  std::string flash;
  std::string mem_size_str;
  std::string wave_path = "sim.fst";
  uint64_t mem_size_bytes = 2ULL * 1024 * 1024 * 1024; // match RAM_SIZE in DifftestMem1R1W
  uint64_t max_cycles = 0;
  uint64_t reset_cycles = 16;
  bool enable_wave = false;
};

template <typename...>
using void_t = void;

template <int N, typename T, typename = void>
struct UartOutLane {
  static void print(T *) {}
};

#define UART_OUT_LANES(X) \
  X(0) X(1) X(2) X(3) X(4) X(5) X(6) X(7) X(8) X(9) X(10) X(11) X(12) X(13) X(14) X(15) X(16)

#define DEFINE_UART_OUT_LANE(N)                                                                     \
  template <typename T>                                                                             \
  struct UartOutLane<N, T, void_t<decltype(std::declval<T>().io_uart_##N##_out_valid),             \
                                    decltype(std::declval<T>().io_uart_##N##_out_ch)>> {             \
    static void print(T *dut) {                                                                     \
      if (dut->io_uart_##N##_out_valid) {                                                           \
        std::cout << static_cast<char>(dut->io_uart_##N##_out_ch);                                    \
        std::cout.flush();                                                                          \
      }                                                                                             \
    }                                                                                               \
  };

UART_OUT_LANES(DEFINE_UART_OUT_LANE)

template <typename T, typename = void>
struct SingleUartOut {
  static void print(T *) {}
};

template <typename T>
struct SingleUartOut<T, void_t<decltype(std::declval<T>().io_uart_out_valid),
                                 decltype(std::declval<T>().io_uart_out_ch)>> {
  static void print(T *dut) {
    if (dut->io_uart_out_valid) {
      std::cout << static_cast<char>(dut->io_uart_out_ch);
      std::cout.flush();
    }
  }
};

template <typename T>
void print_all_uart_outs(T *dut) {
  // Expand once via X-macro to cover all available UART lanes.
#define CALL_UART_OUT_LANE(N) UartOutLane<N, T>::print(dut);
  UART_OUT_LANES(CALL_UART_OUT_LANE)
#undef CALL_UART_OUT_LANE
  SingleUartOut<T>::print(dut);
}

#undef DEFINE_UART_OUT_LANE
#undef UART_OUT_LANES
