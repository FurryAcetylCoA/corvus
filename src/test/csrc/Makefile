ROOT := $(abspath ../../..)
RTL_DIR := $(ROOT)/build/rtl
C_SRC_DIR := $(abspath .)
XS_DIFFTEST_DIR := $(ROOT)/XiangShan/difftest
XS_COMMON_DIR := $(XS_DIFFTEST_DIR)/src/test/csrc/common
XS_CONFIG_DIR := $(XS_DIFFTEST_DIR)/config

BUILD_DIR ?= $(C_SRC_DIR)/obj_dir
VERILATOR ?= verilator
EMU_THREADS ?= 1

CXX_SRCS := $(C_SRC_DIR)/sim_main.cpp \
            $(XS_COMMON_DIR)/common.cpp \
            $(XS_COMMON_DIR)/compress.cpp \
            $(XS_COMMON_DIR)/elfloader.cpp \
            $(XS_COMMON_DIR)/ram.cpp \
            $(XS_COMMON_DIR)/flash.cpp \
            $(XS_CONFIG_DIR)/config.cpp

CFLAGS := -std=c++17 -mcmodel=medium -DNO_GZ_COMPRESSION -DNO_ZSTD_COMPRESSION \
          -I$(XS_COMMON_DIR) -I$(XS_CONFIG_DIR) -I$(C_SRC_DIR)
LDFLAGS := -pthread

VERILATOR_FLAGS := -O3 --sv --cc --exe --top-module SimTop --threads $(EMU_THREADS) \
  --Mdir $(BUILD_DIR) -I$(C_SRC_DIR) -I$(XS_COMMON_DIR) -I$(XS_CONFIG_DIR) \
  -y $(RTL_DIR) -f $(RTL_DIR)/filelist.f -v $(C_SRC_DIR)/DifftestMacros.v --trace-fst \
  -CFLAGS "$(CFLAGS)" -LDFLAGS "$(LDFLAGS)" \
  -Wno-fatal -Wno-UNOPTFLAT -Wno-BLKANDNBLK -Wno-STMTDLY -Wno-WIDTH --max-num-width 150000 --assert --x-assign unique --output-split 30000 --output-split-cfuncs 30000

SIM := $(BUILD_DIR)/VSimTop

.PHONY: sim clean

sim:
	time $(VERILATOR) $(VERILATOR_FLAGS) $(CXX_SRCS)
	time $(MAKE) VM_PARALLEL_BUILDS=1 -C $(BUILD_DIR) -f $(BUILD_DIR)/VSimTop.mk

clean:
	$(RM) -r $(BUILD_DIR)
