const std = @import("std");

pub fn build(b: *std.Build) !void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const bdwgc = b.dependency("bdwgc", .{});

    const flags_common = [_][]const u8{
        // NOTE: These two are set by default when building libgc with CMake
        // https://github.com/ivmai/bdwgc/blob/242a3a7b6040c2680e009367e96a77b0e167619a/CMakeLists.txt#L139
        "-DALL_INTERIOR_POINTERS",
        "-DNO_EXECUTE_PERMISSION",
        // NOTE: This allows linking against the musl libc, which doesn't implement getcontext().
        "-DNO_GETCONTEXT",
        // // FIXME: libgc fails to recognize interior pointers somewhere when building in release mode,
        // //        enabling this circumvents this bug until it is investigated and fixed properly.
        // "-DGC_ASSERTIONS",
        // Thread support for multithreaded environments
        "-DGC_THREADS",
        "-DGC_PTHREADS",
        "-DGC_BUILTIN_ATOMIC",
        "-DPARALLEL_MARK",
        "-DTHREAD_LOCAL_ALLOC", // per-thread free-lists, avoid GC contention when allocating across multiple threads
    };
    const flags_wasi = [_][]const u8{
        "-D__wasi__",
        "-D_WASI_EMULATED_SIGNAL",
        "-Wl,wasi-emulated-signal",
        // FIXME: This gets set to 4096 by default which then causes wasi-libc to randomly abort().
        //        Should probably be fixed upstream.
        "-DHBLKSIZE=65536",
    };
    const flags = if (target.result.os.tag == .wasi)
        &(flags_common ++ flags_wasi)
    else
        &flags_common;

    const lib = b.addLibrary(.{
        .linkage = .static,
        .name = "gc",
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });

    lib.root_module.addCSourceFiles(.{
        .root = bdwgc.path(""),
        .files = &.{
            // https://github.com/ivmai/bdwgc/blob/242a3a7b6040c2680e009367e96a77b0e167619a/CMakeLists.txt#L171-L174
            "allchblk.c",
            "alloc.c",
            "blacklst.c",
            "dbg_mlc.c",
            "dyn_load.c",
            "finalize.c",
            "headers.c",
            "mach_dep.c",
            "malloc.c",
            "mallocx.c",
            "mark_rts.c",
            "mark.c",
            "misc.c",
            "new_hblk.c",
            "obj_map.c",
            "os_dep.c",
            "ptr_chck.c",
            "reclaim.c",
            "typd_mlc.c",
        },
        .flags = flags,
    });
    // Pthread support for multithreaded garbage collection
    lib.root_module.addCSourceFiles(.{
        .root = bdwgc.path(""),
        .files = &.{
            "pthread_support.c",
            "pthread_stop_world.c",
            "pthread_start.c",
            "thread_local_alloc.c",
            "specific.c",
        },
        .flags = flags,
    });
    lib.root_module.addIncludePath(bdwgc.path("include"));
    // Define the list of headers you want to install
    const headers = [_][]const u8{
        "gc.h",
        "gc_backptr.h",
        "gc_config_macros.h",
        "gc_inline.h",
        "gc_mark.h",
        "gc_tiny_fl.h",
        "gc_typed.h",
        "gc_version.h",
        "javaxfc.h",
        "leak_detector.h",
        "gc_pthread_redirects.h",
    };

    // Iterate and install each header individually
    for (headers) |header| {
        const src_path = b.fmt("include/{s}", .{header});
        lib.installHeader(bdwgc.path(src_path), header);
    }
    if (target.result.os.tag == .macos) {
        const macos_sdk = b.dependency("macos_sdk", .{});
        lib.root_module.addSystemFrameworkPath(macos_sdk.path("Frameworks"));
        lib.root_module.addSystemIncludePath(macos_sdk.path("include"));
        lib.root_module.addLibraryPath(macos_sdk.path("lib"));
    }
    if (target.result.os.tag == .macos) {
        lib.root_module.addCSourceFiles(.{
            .root = bdwgc.path(""),
            .files = &.{"darwin_stop_world.c"},
            .flags = flags,
        });
    }
    b.installArtifact(lib);
}