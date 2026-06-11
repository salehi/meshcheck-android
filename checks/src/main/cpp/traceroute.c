// Native ICMP traceroute engine for the MeshCheck "ping" check.
//
// The platform's "ping" check is a traceroute (see doc/ping-check-contract.md):
// TTL-limited ICMP echoes record the route, and the target-reaching hop's
// statistics become the top-level packet/RTT figures.
//
// We use an UNPRIVILEGED ICMP datagram socket — socket(AF_INET, SOCK_DGRAM,
// IPPROTO_ICMP). This works without root on Android because net.ipv4
// .ping_group_range defaults open to all GIDs. On such a socket the kernel
// owns the ICMP id and computes the checksum, so we only write type/code/seq.
//
// Intermediate routers reply with ICMP Time Exceeded, which is delivered on the
// socket error queue (IP_RECVERR + recvmsg(MSG_ERRQUEUE)); the target replies
// with a normal echo reply on the ordinary recv path. android.system.Os does
// not expose recvmsg/MSG_ERRQUEUE until API 33 and minSdk here is 21 — that is
// the whole reason this engine is native.
//
// All this code does is probe and collect raw per-hop data; the measurement
// JSON, outcome rule, and signing all live in Kotlin (PingCheck.kt), so the
// byte-exact output stays consistent with the other executors.

#include <jni.h>

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#include <linux/errqueue.h>

// Linux-specific constants that may be absent from some bionic header sets.
#ifndef IP_RECVERR
#define IP_RECVERR 11
#endif
#ifndef IP_TTL
#define IP_TTL 2
#endif

#define ICMP_ECHO_REQUEST 8
#define ICMP_ECHO_REPLY 0
#define ECHO_PACKET_LEN 16  // 8-byte ICMP header + 8-byte payload
#define PER_HOP_MS 1000     // ceiling on how long one TTL waits for answers

typedef struct {
    int max_ttl;
    int probes;
    long long *send_ns;   // [(max_ttl + 1) * probes]  monotonic send time per seq
    char *hop_ip;         // [(max_ttl + 1) * INET_ADDRSTRLEN]
    int *hop_has_ip;      // [max_ttl + 1]
    double *hop_rtt;      // [(max_ttl + 1) * probes]
    int *hop_rtt_count;   // [max_ttl + 1]
    int *hop_is_target;   // [max_ttl + 1]
    int reached;
    int max_probed_ttl;
} trace_ctx;

static long long now_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static long long now_realtime_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long) ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

static void record_ip(trace_ctx *c, int ttl, const char *ip) {
    if (ttl < 1 || ttl > c->max_ttl) return;
    if (!c->hop_has_ip[ttl]) {
        char *dst = c->hop_ip + (size_t) ttl * INET_ADDRSTRLEN;
        strncpy(dst, ip, INET_ADDRSTRLEN - 1);
        dst[INET_ADDRSTRLEN - 1] = '\0';
        c->hop_has_ip[ttl] = 1;
    }
}

static void record_rtt(trace_ctx *c, int ttl, double rtt_ms) {
    if (ttl < 1 || ttl > c->max_ttl) return;
    if (c->hop_rtt_count[ttl] < c->probes) {
        c->hop_rtt[(size_t) ttl * c->probes + c->hop_rtt_count[ttl]] = rtt_ms;
        c->hop_rtt_count[ttl]++;
    }
}

// rtt for a probe identified by its sequence number, or -1 if untracked.
static double rtt_for_seq(trace_ctx *c, int seq) {
    if (seq < 0 || seq >= (c->max_ttl + 1) * c->probes) return -1.0;
    if (c->send_ns[seq] <= 0) return -1.0;
    return (double) (now_monotonic_ns() - c->send_ns[seq]) / 1e6;
}

// Drain normal echo replies (the target answering) until the socket blocks.
static void drain_replies(trace_ctx *c, int fd) {
    for (;;) {
        unsigned char buf[512];
        struct sockaddr_in from;
        socklen_t from_len = sizeof(from);
        ssize_t n = recvfrom(fd, buf, sizeof(buf), 0, (struct sockaddr *) &from, &from_len);
        if (n < 0) break;          // EAGAIN — queue drained
        if (n < 8) continue;
        if (buf[0] != ICMP_ECHO_REPLY) continue;

        int seq = (buf[6] << 8) | buf[7];
        int ttl = c->probes > 0 ? seq / c->probes : 0;
        double rtt = rtt_for_seq(c, seq);
        if (rtt >= 0.0) record_rtt(c, ttl, rtt);

        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
        record_ip(c, ttl, ip);
        if (ttl >= 1 && ttl <= c->max_ttl) c->hop_is_target[ttl] = 1;
        c->reached = 1;
    }
}

// Drain the error queue: ICMP Time Exceeded from intermediate routers carries
// the offender's address via SO_EE_OFFENDER, and the original probe (in the
// iovec) gives us back our seq for RTT correlation.
static void drain_errqueue(trace_ctx *c, int fd, int current_ttl) {
    for (;;) {
        unsigned char data[512];   // the original outgoing probe is echoed here
        char ctrl[512];
        struct sockaddr_in from;
        struct iovec iov = {.iov_base = data, .iov_len = sizeof(data)};
        struct msghdr msg;
        memset(&msg, 0, sizeof(msg));
        msg.msg_name = &from;
        msg.msg_namelen = sizeof(from);
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl;
        msg.msg_controllen = sizeof(ctrl);

        ssize_t n = recvmsg(fd, &msg, MSG_ERRQUEUE);
        if (n < 0) break;          // EAGAIN — queue drained

        int seq = -1;
        if (n >= 8 && data[0] == ICMP_ECHO_REQUEST) {
            seq = (data[6] << 8) | data[7];
        }
        int ttl = (seq >= 0 && c->probes > 0) ? seq / c->probes : current_ttl;

        for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != NULL;
             cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level != IPPROTO_IP || cmsg->cmsg_type != IP_RECVERR) {
                continue;
            }
            struct sock_extended_err *ee = (struct sock_extended_err *) CMSG_DATA(cmsg);
            if (ee->ee_origin != SO_EE_ORIGIN_ICMP) continue;

            struct sockaddr *off = SO_EE_OFFENDER(ee);
            if (off->sa_family == AF_INET) {
                char ip[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &((struct sockaddr_in *) off)->sin_addr, ip, sizeof(ip));
                record_ip(c, ttl, ip);
            }
            double rtt = rtt_for_seq(c, seq);
            if (rtt >= 0.0) record_rtt(c, ttl, rtt);
        }
    }
}

// Sets PingNativeResult.setupError and returns the (otherwise default) object.
static jobject result_with_error(JNIEnv *env, jclass cls, jobject res, const char *message) {
    jfieldID f = (*env)->GetFieldID(env, cls, "setupError", "Ljava/lang/String;");
    (*env)->SetObjectField(env, res, f, (*env)->NewStringUTF(env, message));
    return res;
}

JNIEXPORT jobject JNICALL
Java_io_meshcheck_checks_PingNative_traceroute(
        JNIEnv *env, jobject thiz, jstring target_ipv4, jint max_ttl, jint probes_per_hop,
        jlong deadline_epoch_ms) {
    (void) thiz;

    jclass cls = (*env)->FindClass(env, "io/meshcheck/checks/PingNativeResult");
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
    jobject res = (*env)->NewObject(env, cls, ctor);

    if (max_ttl < 1 || probes_per_hop < 1) {
        return result_with_error(env, cls, res, "invalid traceroute bounds");
    }

    const char *ip_chars = (*env)->GetStringUTFChars(env, target_ipv4, NULL);
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    int parsed = inet_pton(AF_INET, ip_chars, &dst.sin_addr);
    (*env)->ReleaseStringUTFChars(env, target_ipv4, ip_chars);
    if (parsed != 1) {
        return result_with_error(env, cls, res, "target is not an IPv4 address");
    }

    int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP);
    if (fd < 0) {
        char msg[128];
        snprintf(msg, sizeof(msg), "icmp socket: %s", strerror(errno));
        return result_with_error(env, cls, res, msg);
    }
    int on = 1;
    setsockopt(fd, IPPROTO_IP, IP_RECVERR, &on, sizeof(on));

    trace_ctx c;
    memset(&c, 0, sizeof(c));
    c.max_ttl = max_ttl;
    c.probes = probes_per_hop;
    int seq_slots = (max_ttl + 1) * probes_per_hop;
    c.send_ns = calloc((size_t) seq_slots, sizeof(long long));
    c.hop_ip = calloc((size_t) (max_ttl + 1) * INET_ADDRSTRLEN, 1);
    c.hop_has_ip = calloc((size_t) (max_ttl + 1), sizeof(int));
    c.hop_rtt = calloc((size_t) (max_ttl + 1) * probes_per_hop, sizeof(double));
    c.hop_rtt_count = calloc((size_t) (max_ttl + 1), sizeof(int));
    c.hop_is_target = calloc((size_t) (max_ttl + 1), sizeof(int));

    long long deadline_ms = (long long) deadline_epoch_ms;
    int hit_deadline = 0;

    for (int ttl = 1; ttl <= max_ttl; ttl++) {
        if (now_realtime_ms() >= deadline_ms) {
            hit_deadline = 1;
            break;
        }
        setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl));
        c.max_probed_ttl = ttl;

        unsigned char echo[ECHO_PACKET_LEN];
        memset(echo, 0, sizeof(echo));
        echo[0] = ICMP_ECHO_REQUEST;  // type; code/checksum/id left 0 (kernel fills)
        for (int p = 0; p < probes_per_hop; p++) {
            int seq = ttl * probes_per_hop + p;
            echo[6] = (unsigned char) (seq >> 8);
            echo[7] = (unsigned char) (seq & 0xff);
            c.send_ns[seq] = now_monotonic_ns();
            sendto(fd, echo, sizeof(echo), 0, (struct sockaddr *) &dst, sizeof(dst));
        }

        long long hop_deadline = now_realtime_ms() + PER_HOP_MS;
        if (hop_deadline > deadline_ms) hop_deadline = deadline_ms;
        for (;;) {
            long long remaining = hop_deadline - now_realtime_ms();
            if (remaining <= 0) break;
            struct pollfd pfd = {.fd = fd, .events = POLLIN, .revents = 0};
            int pr = poll(&pfd, 1, (int) remaining);
            if (pr <= 0) break;  // timeout or poll error
            if (pfd.revents & POLLERR) drain_errqueue(&c, fd, ttl);
            if (pfd.revents & POLLIN) drain_replies(&c, fd);
            if (c.reached) break;
            if (c.hop_rtt_count[ttl] >= probes_per_hop) break;
        }
        if (c.reached) break;
    }

    int timed_out = !c.reached && (hit_deadline || now_realtime_ms() >= deadline_ms);
    int hop_count = c.max_probed_ttl;

    // Flatten per-hop RTTs into a single array + offset index.
    int total_rtts = 0;
    for (int ttl = 1; ttl <= hop_count; ttl++) total_rtts += c.hop_rtt_count[ttl];

    jintArray hop_ttl_arr = (*env)->NewIntArray(env, hop_count);
    jbooleanArray hop_target_arr = (*env)->NewBooleanArray(env, hop_count);
    jobjectArray hop_ip_arr =
            (*env)->NewObjectArray(env, hop_count, (*env)->FindClass(env, "java/lang/String"), NULL);
    jdoubleArray rtt_flat_arr = (*env)->NewDoubleArray(env, total_rtts);
    jintArray rtt_offset_arr = (*env)->NewIntArray(env, hop_count + 1);

    int rtt_cursor = 0;
    int offset0 = 0;
    (*env)->SetIntArrayRegion(env, rtt_offset_arr, 0, 1, &offset0);
    for (int ttl = 1; ttl <= hop_count; ttl++) {
        int i = ttl - 1;
        (*env)->SetIntArrayRegion(env, hop_ttl_arr, i, 1, &ttl);
        jboolean is_target = c.hop_is_target[ttl] ? JNI_TRUE : JNI_FALSE;
        (*env)->SetBooleanArrayRegion(env, hop_target_arr, i, 1, &is_target);

        if (c.hop_has_ip[ttl]) {
            jstring s = (*env)->NewStringUTF(env, c.hop_ip + (size_t) ttl * INET_ADDRSTRLEN);
            (*env)->SetObjectArrayElement(env, hop_ip_arr, i, s);
            (*env)->DeleteLocalRef(env, s);  // long routes would otherwise exhaust local refs
        }

        if (c.hop_rtt_count[ttl] > 0) {
            (*env)->SetDoubleArrayRegion(env, rtt_flat_arr, rtt_cursor,
                                         c.hop_rtt_count[ttl],
                                         &c.hop_rtt[(size_t) ttl * c.probes]);
            rtt_cursor += c.hop_rtt_count[ttl];
        }
        (*env)->SetIntArrayRegion(env, rtt_offset_arr, ttl, 1, &rtt_cursor);
    }

    (*env)->SetBooleanField(env, res, (*env)->GetFieldID(env, cls, "reached", "Z"),
                            c.reached ? JNI_TRUE : JNI_FALSE);
    (*env)->SetBooleanField(env, res, (*env)->GetFieldID(env, cls, "timedOut", "Z"),
                            timed_out ? JNI_TRUE : JNI_FALSE);
    (*env)->SetIntField(env, res, (*env)->GetFieldID(env, cls, "hopCount", "I"), hop_count);
    (*env)->SetObjectField(env, res, (*env)->GetFieldID(env, cls, "hopTtl", "[I"), hop_ttl_arr);
    (*env)->SetObjectField(env, res, (*env)->GetFieldID(env, cls, "hopIp", "[Ljava/lang/String;"),
                           hop_ip_arr);
    (*env)->SetObjectField(env, res, (*env)->GetFieldID(env, cls, "hopIsTarget", "[Z"),
                           hop_target_arr);
    (*env)->SetObjectField(env, res, (*env)->GetFieldID(env, cls, "rttFlat", "[D"), rtt_flat_arr);
    (*env)->SetObjectField(env, res, (*env)->GetFieldID(env, cls, "rttOffset", "[I"),
                           rtt_offset_arr);

    free(c.send_ns);
    free(c.hop_ip);
    free(c.hop_has_ip);
    free(c.hop_rtt);
    free(c.hop_rtt_count);
    free(c.hop_is_target);
    close(fd);

    return res;
}
