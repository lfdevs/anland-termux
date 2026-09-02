#define _GNU_SOURCE
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <poll.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <dirent.h>
#include <unistd.h>

#include "anw_hidden.h"
#include "camera_service.h"
#include "display_consumer.h"
#include "native_audio.h"
#include "protocol.h"
#include "socket_utils.h"

#define TAG "Anland"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define PIXEL_FORMAT_RGBA_8888 1
#define MAX_COLLECT_BUFS 8

static int cfg_custom_width = 0;
static int cfg_custom_height = 0;

/* Saved JVM / activity reference for event-thread JNI callbacks. */
static JavaVM *g_jvm = NULL;
static jobject g_activity_obj = NULL;

static struct anw_api api;
static bool api_loaded = false;
static void on_fallback(void *userdata);

static void on_exit_fallback(void *userdata);
struct consumer_state {
    pthread_mutex_t lock;
    ANativeWindow *window;
    display_ctx *ctx;
    pthread_t render_thread;
    volatile bool running;

    //Note: it is Deamon's Reconnect, not Fallback Flag
    //Fallback is maintained by display lib, and the consumer should not care about it.
    volatile bool need_reconnect;

    int buf_count;
    int dmabuf_fds[MAX_COLLECT_BUFS];
    struct buf_info dmabuf_infos[MAX_COLLECT_BUFS];
    ANativeWindowBuffer *buf_anb[MAX_COLLECT_BUFS];

    int screen_w;
    int screen_h;

    // Latest display refresh rate (milli-Hz) reported from Java. Read on
    // (re)connect to seed the producer; updated live by nativeSetRefreshRate.
    volatile uint32_t refresh_mhz;

    // Event (output) thread
    pthread_t event_thread;
    volatile bool event_running;
    /* True while event_thread holds a started-but-not-yet-joined thread. stop only
     * signals (never joins -- on_fallback can run ON the event thread); the join is
     * deferred to the next start_event_thread() (create time), which runs on the
     * render thread and so cannot self-join. */
    bool event_thread_joinable;
};

static struct consumer_state g_state = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
};

/* Connection config, set from Java via nativeConfigure() and read on each
 * (re)connect in do_connect(). Guarded by cfg_lock. */
static pthread_mutex_t cfg_lock = PTHREAD_MUTEX_INITIALIZER;
static char cfg_socket_path[256] = "/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock";
static bool cfg_use_root = false;
static bool cfg_use_compatible_bridge = false;
static int cfg_compatible_fd = -1;
static char cfg_helper_path[512] = "";
static char cfg_bridge_path[512] = "";

static bool motion_has_last = false;
static float motion_last_x = 0.0f;
static float motion_last_y = 0.0f;

static int collect_dmabufs(struct consumer_state *s)
{
    ANativeWindow *win = s->window;
    int target = s->buf_count;
    int found = 0;

    LOGI("collecting %d dma-bufs via dequeue/queue", target);

    for (int attempt = 0; attempt < target * 4 && found < target; attempt++) {
        ANativeWindowBuffer *anb = NULL;
        int fence = -1;
        if (api.dequeueBuffer(win, &anb, &fence) != 0 || !anb) {
            LOGE("dequeueBuffer failed on attempt %d", attempt);
            if (fence >= 0)
                close(fence);
            break;
        }
        if (fence >= 0)
            close(fence);   /* enumeration only: no need to wait the fence */

        if (!anb->handle || anb->handle->numFds < 1) {
            LOGE("dequeued buffer has no dma-buf handle on attempt %d", attempt);
            api.cancelBuffer(win, anb, -1);
            continue;
        }

        int fd = anb->handle->data[0];   /* first fd backs the dma-buf */
        int stride = anb->stride, width = anb->width, height = anb->height;

        /* deduplicate by ANativeWindowBuffer pointer (stable per queue slot) */
        bool dup_found = false;
        for (int i = 0; i < found; i++) {
            if (s->buf_anb[i] == anb) {
                dup_found = true;
                break;
            }
        }

        /* post it back so the next dequeue rotates to another slot */
        api.queueBuffer(win, anb, -1);

        if (dup_found)
            continue;

        int dup_fd = dup(fd);
        if (dup_fd < 0)
            continue;

        s->buf_anb[found] = anb;
        s->dmabuf_fds[found] = dup_fd;
        s->dmabuf_infos[found].stride = stride * 4;
        s->dmabuf_infos[found].width  = width;
        s->dmabuf_infos[found].height = height;
        s->dmabuf_infos[found].format = PIXEL_FORMAT_RGBA_8888;
        s->dmabuf_infos[found].modifier = 0;
        s->dmabuf_infos[found].offset = 0;
        LOGI("  buf[%d]: anb=%p fd=%d dup=%d %dx%d stride=%d",
             found, (void *)anb, fd, dup_fd, width, height, stride);
        found++;
    }

    if (found < target) {
        LOGE("only collected %d/%d", found, target);
        for (int i = 0; i < found; i++) {
            close(s->dmabuf_fds[i]);
            s->dmabuf_fds[i] = -1;
        }
        return -1;
    }

    s->buf_count = found;
    LOGI("collected %d dma-bufs", found);
    return 0;
}

static void cleanup_dmabufs(struct consumer_state *s)
{
    for (int i = 0; i < s->buf_count; i++) {
        if (s->dmabuf_fds[i] >= 0) {
            close(s->dmabuf_fds[i]);
            s->dmabuf_fds[i] = -1;
        }
    }
    s->buf_count = 0;
}

/* Report the current display refresh rate to the producer over the data
 * channel, reusing the InputEvent framing (see INPUT_TYPE_DISPLAY_REFRESH).
 * No-op when disconnected or rate unknown. */
static void send_refresh_rate(struct consumer_state *s)
{
    if (!s->ctx || s->refresh_mhz == 0)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_DISPLAY_REFRESH,
        .display = { .refresh_mhz = s->refresh_mhz },
    };
    push_input_event(s->ctx, &ev);
}

/*
 * Event thread: listens for output events (clipboard, etc.) from the producer
 * on the data_fd. Runs while s->event_running is true.
 */
static void *event_thread_func(void *arg)
{
    struct consumer_state *s = arg;
    LOGI("event thread started");

    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
        LOGE("event thread: AttachCurrentThread failed");
        return NULL;
    }

    /* Find classes/methods once */
    jclass ctxClass = (*env)->GetObjectClass(env, g_activity_obj);
    jmethodID setClipMethod = (*env)->GetMethodID(env, ctxClass, "nativeSetClipboardText", "(Ljava/lang/String;)V");
    if (!setClipMethod) {
        LOGE("event thread: nativeSetClipboardText not found");
        (*g_jvm)->DetachCurrentThread(g_jvm);
        return NULL;
    }

    while (s->event_running) {
        if (!s->ctx) {
            usleep(50000);
            continue;
        }

        struct OutputEvent ev;
        int ret = poll_output_event(s->ctx, &ev, 500);
        if (ret <= 0)
            continue;

        if (ev.type == OUTPUT_TYPE_RESOURCES_REQUEST) {
            /* Producer is asking for a service's fds (e.g. camera). The display lib
             * matches the type against the registered services and sends the
             * pre-created fds back over SCM_RIGHTS. */
            handle_resource_request(s->ctx, &ev);
        } else if (ev.type == OUTPUT_TYPE_CLIPBOARD && ev.clipboard.size > 0) {
            char *buf = malloc(ev.clipboard.size + 1);
            if (!buf)
                continue;

            if (poll_output_event_extend_data(s->ctx, buf, ev.clipboard.size, 5000) == 1) {
                buf[ev.clipboard.size] = '\0';
                jstring jstr = (*env)->NewStringUTF(env, buf);
                if (jstr) {
                    (*env)->CallVoidMethod(env, g_activity_obj, setClipMethod, jstr);
                    (*env)->DeleteLocalRef(env, jstr);
                }
            }
            free(buf);
        } else {
            /* Unknown or zero-length event: drain any trailing data if size > 0 */
            LOGI("event thread: unknown output event type=%u size=%u", ev.type, ev.clipboard.size);
        }
    }

    (*g_jvm)->DetachCurrentThread(g_jvm);
    LOGI("event thread stopped");
    return NULL;
}

static void join_event_thread(struct consumer_state *s)
{
    /* Idempotent. MUST be called only from a non-event thread (render / JNI teardown);
     * never from on_fallback (which may run on the event thread). */
    if (s->event_thread_joinable) {
        pthread_join(s->event_thread, NULL);
        s->event_thread_joinable = false;
    }
}

static void start_event_thread(struct consumer_state *s)
{
    if (s->event_running)
        return;
    /* Reap the previous stopped-but-unjoined thread before spawning a new one. Runs on
     * the render thread (on_exit_fallback), so this join can't self-deadlock. */
    join_event_thread(s);
    s->event_running = true;
    if (pthread_create(&s->event_thread, NULL, event_thread_func, s) == 0)
        s->event_thread_joinable = true;
    else
        s->event_running = false;
}

static void stop_event_thread(struct consumer_state *s)
{
    /* Signal only -- do NOT join here. enter_fallback()->on_fallback() can execute on
     * the event thread itself, so joining would self-deadlock. The handle stays in
     * event_thread (event_thread_joinable) and is reaped at create time by the next
     * start_event_thread() (or do_connect's reconnect path, both on the render
     * thread). */
    s->event_running = false;
}

/*
 * "Connect with root" handshake. The app cannot connect() to a root-owned
 * daemon socket directly, so it listens on a bridge socket, launches the bundled
 * helper through `su -c`, and the helper (as root) connects to the daemon and
 * passes the connected fd back over the bridge. Returns the received fd (caller
 * owns it) or -1 on failure.
 */
static int recv_fd_via_root_helper(const char *daemon_sock,
                                   const char *helper_path,
                                   const char *bridge_path)
{
    if (helper_path[0] == '\0' || bridge_path[0] == '\0') {
        LOGE("root helper: helper/bridge path not configured");
        return -1;
    }

    unlink(bridge_path);

    int lfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (lfd < 0) {
        LOGE("root helper: socket() failed: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, bridge_path, sizeof(addr.sun_path) - 1);

    if (bind(lfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("root helper: bind(%s) failed: %s", bridge_path, strerror(errno));
        close(lfd);
        return -1;
    }
    /* Root helper runs in a different SELinux/uid context; make the socket file
     * reachable. (Root bypasses DAC, but be permissive anyway.) */
    chmod(bridge_path, 0777);

    if (listen(lfd, 1) < 0) {
        LOGE("root helper: listen() failed: %s", strerror(errno));
        close(lfd);
        unlink(bridge_path);
        return -1;
    }

    /* Build the command su runs: "<helper> <daemon_sock> <bridge_path>". */
    char inner[1100];
    snprintf(inner, sizeof(inner), "%s %s %s",
             helper_path, daemon_sock, bridge_path);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("root helper: fork() failed: %s", strerror(errno));
        close(lfd);
        unlink(bridge_path);
        return -1;
    }
    if (pid == 0) {
        execlp("su", "su", "-c", inner, (char *)NULL);
        _exit(127);   /* su not found / exec failed */
    }

    /* Wait for the helper to connect (root prompt may take a while). */
    int fd = -1;
    struct pollfd pfd = { .fd = lfd, .events = POLLIN };
    if (poll(&pfd, 1, 30000) > 0 && (pfd.revents & POLLIN)) {
        int cfd = accept(lfd, NULL, NULL);
        if (cfd >= 0) {
            char b;
            int got = 0;
            if (recv_fds(cfd, &b, 1, &fd, 1, &got) < 0 || got < 1)
                fd = -1;
            close(cfd);
        }
    } else {
        LOGE("root helper: timed out waiting for helper connection");
    }

    int status = 0;
    waitpid(pid, &status, 0);
    close(lfd);
    unlink(bridge_path);

    if (fd < 0)
        LOGE("root helper: did not receive daemon fd (su status=%d)", status);
    return fd;
}

static int do_connect(struct consumer_state *s)
{
    /* Snapshot the connection config for this attempt. */
    pthread_mutex_lock(&cfg_lock);
    bool use_root = cfg_use_root;
    bool use_compatible_bridge = cfg_use_compatible_bridge;
    bool compatible_fd_available = cfg_compatible_fd >= 0;
    char sock_path[sizeof(cfg_socket_path)];
    char helper_path[sizeof(cfg_helper_path)];
    char bridge_path[sizeof(cfg_bridge_path)];
    memcpy(sock_path, cfg_socket_path, sizeof(sock_path));
    memcpy(helper_path, cfg_helper_path, sizeof(helper_path));
    memcpy(bridge_path, cfg_bridge_path, sizeof(bridge_path));
    pthread_mutex_unlock(&cfg_lock);

    if (use_compatible_bridge && !compatible_fd_available) {
        LOGI("compatible bridge has not supplied a socket fd yet");
        return -1;
    }

    const char *sock = sock_path;

    if (s->ctx) {
        audio_set_ctx(NULL);   /* detach audio before the old ctx (and its fd) dies */
        stop_event_thread(s);
        join_event_thread(s);
        disconnect(s->ctx);
        s->ctx = NULL;
    }
    cleanup_dmabufs(s);

    ANativeWindow *win = s->window;
    pthread_mutex_lock(&cfg_lock);
    int cw = cfg_custom_width;
    int ch = cfg_custom_height;
    pthread_mutex_unlock(&cfg_lock);

    if (cw > 0 && ch > 0) {
        s->screen_w = cw;
        s->screen_h = ch;
    } else {
       s->screen_w = ANativeWindow_getWidth(win);
       s->screen_h = ANativeWindow_getHeight(win);
    }

    /* dequeueBuffer needs the window connected to an API first (ANativeWindow_lock
     * did this internally). Disconnect first so reconnect is idempotent. */
    anw_api_disconnect(win, ANW_API_CPU);
    if (anw_api_connect(win, ANW_API_CPU) != 0) {
        LOGE("api_connect(CPU) failed");
        return -1;
    }

    ANativeWindow_setBuffersGeometry(win, s->screen_w, s->screen_h,
                                     AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    int min_undequeued = 0;
    api.query(win, ANATIVEWINDOW_QUERY_MIN_UNDEQUEUED_BUFFERS, &min_undequeued);
    int total = min_undequeued + 2;
    if (total > MAX_COLLECT_BUFS)
        total = MAX_COLLECT_BUFS;

    api.setBufferCount(win, total);

    s->buf_count = total;
    if (collect_dmabufs(s) < 0)
        return -1;

    LOGI("connecting to %s (%dx%d, %d bufs, root=%d, compatible=%d)", sock,
         s->screen_w, s->screen_h, s->buf_count, use_root,
         use_compatible_bridge);

    if (use_compatible_bridge) {
        pthread_mutex_lock(&cfg_lock);
        /* Keep the Binder-supplied descriptor as a master copy. Each display
         * context owns a duplicate, while the Termux bridge keeps the actual
         * daemon connection alive across consumer reconnect attempts. */
        int compatible_fd = cfg_compatible_fd >= 0 ? dup(cfg_compatible_fd) : -1;
        pthread_mutex_unlock(&cfg_lock);
        if (compatible_fd < 0) {
            LOGE("could not duplicate compatible bridge socket fd: %s", strerror(errno));
            return -1;
        }
        if (connect_to_deamon_with_fd(&s->ctx, compatible_fd) < 0) {
            LOGE("connect_to_deamon_with_fd from compatible bridge failed");
            return -1;
        }
    } else if (use_root) {
        int ctrl_fd = recv_fd_via_root_helper(sock, helper_path, bridge_path);
        if (ctrl_fd < 0) {
            LOGE("root helper connect failed");
            return -1;
        }
        if (connect_to_deamon_with_fd(&s->ctx, ctrl_fd) < 0) {
            LOGE("connect_to_deamon_with_fd failed");
            return -1;
        }
    } else if (connect_to_deamon(&s->ctx, sock) < 0) {
        LOGE("connect_to_deamon failed");
        return -1;
    }

    set_screen_info(s->ctx, s->screen_w, s->screen_h,
                    PIXEL_FORMAT_RGBA_8888, s->refresh_mhz);
    push_dmabufs(s->ctx, s->dmabuf_fds, s->dmabuf_infos, s->buf_count);

    /* Register the camera service only when it was initialised (i.e. the user
     * enabled it in settings and granted CAMERA). allocate_services() stores this
     * pointer by reference, so it must outlive the ctx -> keep it static. The
     * producer drives it via RESOURCES_REQUEST (handled on the event thread). */
    if (camera_service_is_ready()) {
        static struct service_info camera_svc;
        camera_svc.type = SERVICE_TYPE_CAMERA;
        camera_svc.allocate_resource = camera_allocate_resource;
        camera_svc.free_resource = camera_free_resource;
        allocate_services(s->ctx, &camera_svc, 1);
    }

    set_fallback_callback(s->ctx, on_fallback, s);
    set_exit_fallback_callback(s->ctx, on_exit_fallback, s);

    audio_set_ctx(s->ctx);   /* audio fd is now live; threads pick it up via get_audio_fd */

    s->need_reconnect = false;
    LOGI("connected");
    return 0;
}

static void on_fallback(void *userdata)
{
    struct consumer_state *s = userdata;
    LOGI("fallback triggered");

    audio_set_ctx(NULL);   /* the lib has closed the audio fd; stop touching it */

    // Disable clip listener on Java side before stopping event thread
    if (g_jvm && g_activity_obj) {
        JNIEnv *env = NULL;
        bool attached = false;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0)
                attached = true;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
            jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
            if (mid)
                (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        }
        if (attached)
            (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    stop_event_thread(s);
}

static void on_exit_fallback(void *userdata)
{
    struct consumer_state *s = userdata;
    LOGI("exit fallback triggered");

    send_refresh_rate(&g_state);
    
    JNIEnv *env = NULL;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
        LOGE("on_exit_fallback: AttachCurrentThread failed");
        return;
    }

    // Enable clip listener on Java side
    jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
    jmethodID listenMid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
    if (listenMid)
        (*env)->CallVoidMethod(env, g_activity_obj, listenMid, JNI_TRUE);

    start_event_thread(s);

    // Initial clipboard sync: read current system clipboard and send to producer
    jmethodID syncMethod = (*env)->GetMethodID(env, cls, "nativeClipboardSync", "()V");
    if (syncMethod)
        (*env)->CallVoidMethod(env, g_activity_obj, syncMethod);

    (*g_jvm)->DetachCurrentThread(g_jvm);
}

static void *render_thread_func(void *arg)
{
    struct consumer_state *s = arg;
    LOGI("render thread started");

    while (s->running) {
        if (s->need_reconnect) {
            LOGI("reconnecting...");
            if (do_connect(s) < 0) {
                usleep(500000);
                continue;
            }
        }

        ANativeWindowBuffer *anb = NULL;
        int acqfence = -1;
        if (api.dequeueBuffer(s->window, &anb, &acqfence) != 0 || !anb) {
            usleep(16000);
            continue;
        }
        /* Emulate ANativeWindow_lock: CPU-wait the acquire fence so the buffer is
         * already safe to write (SurfaceFlinger done reading the previous frame)
         * before we hand it to the producer. A sync_file fd signals POLLIN. */
        if (acqfence >= 0) {
            struct pollfd fpfd = { .fd = acqfence, .events = POLLIN };
            poll(&fpfd, 1, 1000);
            close(acqfence);
        }

        int idx = -1;
        for (int i = 0; i < s->buf_count; i++) {
            if (s->buf_anb[i] == anb) {
                idx = i;
                break;
            }
        }

        if (idx < 0) {
            api.queueBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }

        if (select_dmabuf(s->ctx, idx) < 0) {
            api.queueBuffer(s->window, anb, -1);
            usleep(16000);
            continue;
        }

        /* The producer renders into the buffer and hands back a render-done fence
         * over data_fd (reverse). Queue with it so SurfaceFlinger waits GPU-side
         * before scanout -- this lets the producer submit before its GPU render
         * completes (no glFinish stall). rfence == -1 falls back to "ready now". */
        int rfence = refresh_done(s->ctx);
        api.queueBuffer(s->window, anb, rfence);
    }

    LOGI("render thread stopped");
    return NULL;
}

/* ---------- JNI ---------- */

static void copy_jstring(JNIEnv *env, jstring js, char *dst, size_t dstsz)
{
    if (!js) {
        dst[0] = '\0';
        return;
    }
    const char *s = (*env)->GetStringUTFChars(env, js, NULL);
    if (s) {
        strncpy(dst, s, dstsz - 1);
        dst[dstsz - 1] = '\0';
        (*env)->ReleaseStringUTFChars(env, js, s);
    } else {
        dst[0] = '\0';
    }
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeConfigure(
    JNIEnv *env, jclass clazz, jstring socketPath, jboolean useRoot,
    jstring helperPath, jstring bridgePath)
{
    pthread_mutex_lock(&cfg_lock);
    char tmp[sizeof(cfg_socket_path)];
    copy_jstring(env, socketPath, tmp, sizeof(tmp));
    if (tmp[0] != '\0')
        memcpy(cfg_socket_path, tmp, sizeof(cfg_socket_path));
    cfg_use_root = (useRoot == JNI_TRUE);
    copy_jstring(env, helperPath, cfg_helper_path, sizeof(cfg_helper_path));
    copy_jstring(env, bridgePath, cfg_bridge_path, sizeof(cfg_bridge_path));
    pthread_mutex_unlock(&cfg_lock);

    LOGI("configured: socket=%s root=%d helper=%s bridge=%s",
         cfg_socket_path, cfg_use_root, cfg_helper_path, cfg_bridge_path);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetCompatibleMode(
    JNIEnv *env, jclass clazz, jboolean enabled)
{
    (void)env;
    (void)clazz;

    pthread_mutex_lock(&cfg_lock);
    cfg_use_compatible_bridge = (enabled == JNI_TRUE);
    if (!cfg_use_compatible_bridge && cfg_compatible_fd >= 0) {
        close(cfg_compatible_fd);
        cfg_compatible_fd = -1;
    }
    pthread_mutex_unlock(&cfg_lock);

    LOGI("compatible bridge mode: %d", enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetCompatibleFd(
    JNIEnv *env, jclass clazz, jint fd)
{
    (void)env;
    (void)clazz;

    if (fd < 0)
        return;

    pthread_mutex_lock(&cfg_lock);
    if (cfg_compatible_fd >= 0)
        close(cfg_compatible_fd);
    cfg_compatible_fd = fd;
    pthread_mutex_unlock(&cfg_lock);

    LOGI("received compatible bridge socket fd=%d", fd);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetCustomResolution(
    JNIEnv* env, jclass clazz, jint width, jint height)
{
    pthread_mutex_lock(&cfg_lock);
    cfg_custom_width = width;
    cfg_custom_height = height;
    pthread_mutex_unlock(&cfg_lock);
    LOGI("custom resolution: %dx%d", width, height);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeStart(
    JNIEnv *env, jclass clazz, jobject surface, jobject callbackTarget)
{
    if (!api_loaded) {
        if (anw_api_load(&api) < 0) {
            LOGE("failed to load ANativeWindow hidden API");
            return;
        }
        api_loaded = true;
    }

    pthread_mutex_lock(&g_state.lock);

    if (g_state.running) {
        g_state.running = false;
        pthread_mutex_unlock(&g_state.lock);
        pthread_join(g_state.render_thread, NULL);
        pthread_mutex_lock(&g_state.lock);
    }

    if (g_state.ctx) {
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }
    motion_has_last = false;
    motion_has_last = false;
    cleanup_dmabufs(&g_state);

    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }

    g_state.window = ANativeWindow_fromSurface(env, surface);
    if (!g_state.window) {
        LOGE("ANativeWindow_fromSurface failed");
        pthread_mutex_unlock(&g_state.lock);
        return;
    }

    /* Save JVM & clipboard callback target for event-thread JNI callbacks. */
    if (!g_jvm) {
        (*env)->GetJavaVM(env, &g_jvm);
    }
    if (g_activity_obj) {
        (*env)->DeleteGlobalRef(env, g_activity_obj);
    }
    /* Static natives have no `thiz`; Java passes the Clipboard instance whose
     * nativeSetClipboardText / nativeClipListening / nativeClipboardSync methods
     * are called by the event thread. */
    g_activity_obj = (*env)->NewGlobalRef(env, callbackTarget);

    g_state.running = true;
    g_state.need_reconnect = true;
    pthread_create(&g_state.render_thread, NULL, render_thread_func, &g_state);

    /* Audio streams live independently of the connection; the render thread attaches
     * the fd via audio_set_ctx() once connected. */
    audio_start();

    pthread_mutex_unlock(&g_state.lock);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeStop(
    JNIEnv *env, jclass clazz)
{
    pthread_mutex_lock(&g_state.lock);

    if (g_state.running) {
        g_state.running = false;
        pthread_mutex_unlock(&g_state.lock);
        pthread_join(g_state.render_thread, NULL);
        pthread_mutex_lock(&g_state.lock);
    }

    /* Stop audio before the ctx (and its fd) is torn down. */
    audio_set_ctx(NULL);
    audio_stop();

    if (g_state.ctx) {
        stop_event_thread(&g_state);
        join_event_thread(&g_state);
        disconnect(g_state.ctx);
        g_state.ctx = NULL;
    }

    // Disable clip listener on Java side
    if (g_jvm && g_activity_obj) {
        JNIEnv *env = NULL;
        bool attached = false;
        if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == 0)
                attached = true;
        }
        if (env) {
            jclass cls = (*env)->GetObjectClass(env, g_activity_obj);
            jmethodID mid = (*env)->GetMethodID(env, cls, "nativeClipListening", "(Z)V");
            if (mid)
                (*env)->CallVoidMethod(env, g_activity_obj, mid, JNI_FALSE);
        }
        if (attached)
            (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    cleanup_dmabufs(&g_state);

    pthread_mutex_lock(&cfg_lock);
    if (cfg_compatible_fd >= 0) {
        close(cfg_compatible_fd);
        cfg_compatible_fd = -1;
    }
    pthread_mutex_unlock(&cfg_lock);

    if (g_state.window) {
        ANativeWindow_release(g_state.window);
        g_state.window = NULL;
    }

    pthread_mutex_unlock(&g_state.lock);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetRefreshRate(
    JNIEnv *env, jclass clazz, jfloat hz)
{
    if (hz <= 0.0f)
        return;
    g_state.refresh_mhz = (uint32_t)(hz * 1000.0f + 0.5f);
    // Apply live if already connected; otherwise do_connect() seeds it.
    send_refresh_rate(&g_state);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendTouch(
    JNIEnv *env, jclass clazz, jint action, jfloat x, jfloat y, jint pointer_id)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_TOUCH,
        .touch = { .action = action, .x = x, .y = y, .pointer_id = pointer_id },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendTouchFrame(
    JNIEnv *env, jclass clazz)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_TOUCH_FRAME,
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendKey(
    JNIEnv *env, jclass clazz, jint action, jint keycode)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_KEY,
        .key = { .action = action, .keycode = keycode },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendMouseMotion(
    JNIEnv *env, jclass clazz, jfloat x, jfloat y, jfloat dx, jfloat dy)
{
    if (!g_state.ctx)
        return;

    if (dx == 0.0f && dy == 0.0f && motion_has_last) {
        dx = x - motion_last_x;
        dy = y - motion_last_y;
    }

    motion_last_x = x;
    motion_last_y = y;
    motion_has_last = true;

    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_MOTION,
        .pointer_motion = { .x = x, .y = y, .dx = dx, .dy = dy },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendMouseButton(
    JNIEnv *env, jclass clazz, jint button, jboolean pressed)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_BUTTON,
        .pointer_button = { .button = button, .pressed = pressed ? 1 : 0 },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendMouseScroll(
    JNIEnv *env, jclass clazz, jint axis, jfloat value)
{
    if (!g_state.ctx)
        return;
    struct InputEvent ev = {
        .type = INPUT_TYPE_POINTER_AXIS,
        .pointer_axis = { .axis = axis, .value = value, .discrete = 0 },
    };
    push_input_event(g_state.ctx, &ev);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendClipboard(
    JNIEnv *env, jclass clazz, jbyteArray data)
{
    if (!g_state.ctx)
        return;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0)
        return;

    char *buf = malloc(len);
    if (!buf)
        return;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);

    struct InputEvent ev = {
        .type = INPUT_TYPE_CLIPBOARD,
        .clipboard = { .size = (uint32_t)len },
    };
    push_input_event_with_length(g_state.ctx, &ev, buf, len);
    free(buf);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSendTextInput(
    JNIEnv *env, jclass clazz, jbyteArray data)
{
    if (!g_state.ctx)
        return;

    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0)
        return;

    char *buf = malloc(len);
    if (!buf)
        return;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);

    struct InputEvent ev = {
        .type = INPUT_TYPE_TEXT_INPUT,
        .text_input = { .size = (uint32_t)len },
    };
    push_input_event_with_length(g_state.ctx, &ev, buf, len);
    free(buf);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetMicEnabled(
    JNIEnv *env, jclass clazz, jboolean enabled)
{
    audio_set_mic_enabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetAudioLatency(
    JNIEnv *env, jclass clazz, jint speakerMs, jint micMs)
{
    audio_set_latency(speakerMs, micMs);
}

JNIEXPORT void JNICALL
Java_com_anland_termux_Native_nativeSetAudioKeepalive(
    JNIEnv *env, jclass clazz, jboolean enabled)
{
    audio_set_keepalive(enabled == JNI_TRUE);
}
