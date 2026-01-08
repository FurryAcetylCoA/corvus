li t1, 0x40600000

li t0, 0x48
sb t0, 4(t1)
li t0, 0x65
sb t0, 4(t1)
li t0, 0x6c
sb t0, 4(t1)
li t0, 0x6c
sb t0, 4(t1)
li t0, 0x6f
sb t0, 4(t1)
li t0, 0x2c
sb t0, 4(t1)
li t0, 0x20
sb t0, 4(t1)
li t0, 0x77
sb t0, 4(t1)
li t0, 0x6f
sb t0, 4(t1)
li t0, 0x72
sb t0, 4(t1)
li t0, 0x6c
sb t0, 4(t1)
li t0, 0x64
sb t0, 4(t1)
li t0, 0x21
sb t0, 4(t1)
li t0, 0x0a
sb t0, 4(t1)

loop:

j loop
