.text

# void switchTo(Fiber* from, Fiber* to)
# from = %rdi, to = %rsi
# Fiber.sp is at offset 0 in the struct

#if defined(__APPLE__)
.global _switchTo
_switchTo:
#else
.global switchTo
.type switchTo, @function
switchTo:
#endif
    # Save callee-saved registers (System V ABI)
    push %rbx
    push %rbp
    push %r12
    push %r13
    push %r14
    push %r15

    # Save current stack pointer into from->sp
    mov %rsp, (%rdi)

    # Load new stack pointer from to->sp
    mov (%rsi), %rsp

    # Restore callee-saved registers
    pop %r15
    pop %r14
    pop %r13
    pop %r12
    pop %rbp
    pop %rbx

    ret

# Entry point for freshly created fibers.
# The initial stack is set up so that switchTo's `ret` lands here.
# At this point %r12 holds the Fiber* (placed on initial stack setup).

#if defined(__APPLE__)
.global _fiber_entry
_fiber_entry:
#else
.global fiber_entry
.type fiber_entry, @function
fiber_entry:
#endif
    # %r12 = Fiber* (loaded by switchTo's `pop %r12`)
    # Pass it as first argument to the Zig trampoline
    mov %r12, %rdi
#if defined(__APPLE__)
    call _fiber_trampoline
#else
    call fiber_trampoline
#endif
    # fiber_trampoline should never return (it yields back to scheduler)
    ud2
