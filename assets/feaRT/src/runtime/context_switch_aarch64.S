.text

// void switchTo(Fiber* from, Fiber* to)
// from = x0, to = x1
// Fiber.sp is at offset 0 in the struct
//
// Save/restore layout (20 slots, 160 bytes, bottom to top):
//   sp+0:   d8,  d9
//   sp+16:  d10, d11
//   sp+32:  d12, d13
//   sp+48:  d14, d15
//   sp+64:  x19, x20
//   sp+80:  x21, x22
//   sp+96:  x23, x24
//   sp+112: x25, x26
//   sp+128: x27, x28
//   sp+144: x29, x30

#if defined(__APPLE__)
.global _switchTo
_switchTo:
#else
.global switchTo
.type switchTo, @function
switchTo:
#endif
    // Allocate space for 20 slots (160 bytes)
    sub sp, sp, #160

    // Save SIMD callee-saved registers (d8-d15)
    stp d8,  d9,  [sp, #0]
    stp d10, d11, [sp, #16]
    stp d12, d13, [sp, #32]
    stp d14, d15, [sp, #48]

    // Save GP callee-saved registers (x19-x28, x29, x30)
    stp x19, x20, [sp, #64]
    stp x21, x22, [sp, #80]
    stp x23, x24, [sp, #96]
    stp x25, x26, [sp, #112]
    stp x27, x28, [sp, #128]
    stp x29, x30, [sp, #144]

    // Save current stack pointer into from->sp
    mov x2, sp
    str x2, [x0]

    // Load new stack pointer from to->sp
    ldr x2, [x1]
    mov sp, x2

    // Restore GP callee-saved registers
    ldp x29, x30, [sp, #144]
    ldp x27, x28, [sp, #128]
    ldp x25, x26, [sp, #112]
    ldp x23, x24, [sp, #96]
    ldp x21, x22, [sp, #80]
    ldp x19, x20, [sp, #64]

    // Restore SIMD callee-saved registers
    ldp d14, d15, [sp, #48]
    ldp d12, d13, [sp, #32]
    ldp d10, d11, [sp, #16]
    ldp d8,  d9,  [sp, #0]

    // Deallocate
    add sp, sp, #160

    ret

// Entry point for freshly created fibers.
// The initial stack is set up with 20 slots matching the layout above.
// After switchTo restores and deallocates, x19 = Fiber*, x30 = fiber_entry.
// switchTo's `ret` jumps to x30, which is this function.

#if defined(__APPLE__)
.global _fiber_entry
_fiber_entry:
#else
.global fiber_entry
.type fiber_entry, @function
fiber_entry:
#endif
    // x19 = Fiber* (restored by switchTo's ldp x19, x20)
    // Pass it as the first argument to fiber_trampoline
    mov x0, x19
#if defined(__APPLE__)
    bl _fiber_trampoline
#else
    bl fiber_trampoline
#endif
    // fiber_trampoline should never return
    brk #0
