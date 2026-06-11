# Native ICMP traceroute engine for the "ping" check. Pure C, no STL — so there
# is no libc++_shared.so to bundle. Produces libmeshcheck_ping.so per ABI.
cmake_minimum_required(VERSION 3.22.1)
project(meshcheck_ping C)

add_library(meshcheck_ping SHARED traceroute.c)

# android/log for diagnostics; JNI headers come from the NDK toolchain.
find_library(log-lib log)
target_link_libraries(meshcheck_ping ${log-lib})

# Android 15 requires 16KB-aligned .so segments. NDK r27+ does this by default;
# the explicit flag is a belt-and-suspenders for older linkers.
target_link_options(meshcheck_ping PRIVATE "-Wl,-z,max-page-size=16384")
