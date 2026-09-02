#define _GNU_SOURCE
#include "native_audio.h"
#include "protocol.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>

#define TAG "AnlandAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Channel-count preferences; the device may override (we read back the actuals).
 * Sample rate is never pinned -- AAudio picks the device-optimal rate and we honour
 * it, telling the producer so PipeWire matches. See protocol.h. */
#define WANT_PLAY_CHANNELS 2
#define WANT_CAP_CHANNELS  1
#define MAX_DGRAM          (64 * 1024)
#define MIC_MAX_FRAMES     1024   /* upper bound on frames per mic read */

struct audio_bridge {
    volatile bool running;
    volatile bool mic_enabled;

    /* The live connection. Set by audio_set_ctx() from the render thread, read by
     * the audio threads -- same lightweight convention as the event thread's
     * s->ctx. get_audio_fd() returns -1 in fallback, so a stale-but-valid ctx just
     * yields no fd rather than misbehaving. */
    display_ctx *volatile ctx;

    pthread_t play_thread;
    pthread_t cap_thread;

    AAudioStream *play;   /* output: desktop -> speaker */
    AAudioStream *rec;    /* input:  mic -> producer    */

    /* Actual device-chosen formats, read back after the streams open. */
    int play_rate, play_channels;
    int cap_rate, cap_channels;

    /* Latency presets in ms (0 = engine default), set from the settings UI. */
    volatile int play_latency_ms;
    volatile int cap_latency_ms;
    volatile bool resend_formats;   /* a preset changed -> re-announce on the live fd */

    /* Set by the AAudio error callback or a failed playback write. The playback
     * thread owns stream teardown and recreation. */
    volatile bool play_rebuild;

    uint8_t rx[MAX_DGRAM];
};

static struct audio_bridge g = {0};

static int current_fd(void)
{
    display_ctx *ctx = g.ctx;
    return ctx ? get_audio_fd(ctx) : -1;
}

/* ---- AAudio stream helpers ---- */

static void play_error_cb(AAudioStream *stream, void *user_data,
                          aaudio_result_t error)
{
    struct audio_bridge *b = user_data;
    (void)stream;
    LOGE("playback stream error: %s -- scheduling rebuild",
         AAudio_convertResultToText(error));
    b->play_rebuild = true;
}

static AAudioStream *open_stream(aaudio_direction_t dir, int channels,
                                 AAudioStream_errorCallback error_cb,
                                 void *user_data)
{
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || !b)
        return NULL;

    AAudioStreamBuilder_setDirection(b, dir);
    /* UNSPECIFIED rate: let the device pick its optimal/native rate; we read it back. */
    AAudioStreamBuilder_setSampleRate(b, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    if (error_cb)
        AAudioStreamBuilder_setErrorCallback(b, error_cb, user_data);

    AAudioStream *stream = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !stream) {
        LOGE("open %s stream failed: %s",
             dir == AAUDIO_DIRECTION_OUTPUT ? "output" : "input",
             AAudio_convertResultToText(r));
        return NULL;
    }
    return stream;
}

/* Convert a latency preset (ms) to a frame count at the given rate. 0 ms -> 0 (let
 * the producer pick the default quantum). */
static uint32_t ms_to_frames(int ms, int rate)
{
    if (ms <= 0 || rate <= 0)
        return 0;
    return (uint32_t)(((long)ms * rate) / 1000);
}

/* Tell the producer the device-chosen format + latency preset for one direction. */
static void send_format(int fd, uint32_t role, uint32_t rate, uint32_t channels,
                        uint32_t quantum)
{
    struct audio_format f = {
        .rate = rate,
        .channels = channels,
        .format = AUDIO_FORMAT_S16LE,
        .role = role,
        .quantum = quantum,
    };
    struct audio_msg h = { .type = AUDIO_MSG_FORMAT, .size = sizeof(f) };
    struct iovec iov[2] = {
        { .iov_base = &h, .iov_len = sizeof(h) },
        { .iov_base = &f, .iov_len = sizeof(f) },
    };
    struct msghdr m = { .msg_iov = iov, .msg_iovlen = 2 };
    sendmsg(fd, &m, MSG_DONTWAIT | MSG_NOSIGNAL);
}

/* ---- playback: socket -> speaker ---- */

/* Reopen only the output stream after an AAudio route/error failure. The display
 * connection and native window remain untouched. */
static bool reopen_play_stream(void)
{
    if (g.play) {
        AAudioStream_requestStop(g.play);
        AAudioStream_close(g.play);
        g.play = NULL;
    }
    g.play = open_stream(AAUDIO_DIRECTION_OUTPUT, WANT_PLAY_CHANNELS,
                         play_error_cb, &g);
    if (!g.play)
        return false;
    g.play_rate = AAudioStream_getSampleRate(g.play);
    g.play_channels = AAudioStream_getChannelCount(g.play);
    AAudioStream_requestStart(g.play);
    LOGI("playback stream opened: %d Hz x%d", g.play_rate, g.play_channels);
    return true;
}

static void *play_thread_func(void *arg)
{
    (void)arg;
    LOGI("playback thread started");

    bool had_fd = false;   /* drives a one-shot format handshake per connection */

    while (g.running) {
        if (g.play_rebuild || !g.play) {
            g.play_rebuild = false;
            if (!reopen_play_stream()) {
                usleep(200000);
                continue;
            }
            had_fd = false;
            g.resend_formats = true;
        }

        int fd = current_fd();
        if (fd < 0) {
            had_fd = false;
            usleep(20000);
            continue;
        }

        /* Hand the producer the real device formats + latency presets for both
         * directions: once when the socket comes up (just left fallback), and again
         * whenever a preset changes so it re-sizes its PipeWire nodes live. */
        if (!had_fd || g.resend_formats) {
            g.resend_formats = false;
            send_format(fd, AUDIO_ROLE_PLAYBACK, g.play_rate, g.play_channels,
                        ms_to_frames(g.play_latency_ms, g.play_rate));
            send_format(fd, AUDIO_ROLE_CAPTURE, g.cap_rate, g.cap_channels,
                        ms_to_frames(g.cap_latency_ms, g.cap_rate));
            had_fd = true;
        }

        struct pollfd pfd = { .fd = fd, .events = POLLIN };
        if (poll(&pfd, 1, 200) <= 0)
            continue;
        if (pfd.revents & (POLLHUP | POLLERR)) {
            usleep(20000);
            continue;
        }

        ssize_t n = recv(fd, g.rx, sizeof(g.rx), 0);
        if (n < (ssize_t)sizeof(struct audio_msg))
            continue;

        struct audio_msg h;
        memcpy(&h, g.rx, sizeof(h));
        if (h.type != AUDIO_MSG_PCM || !g.play)
            continue;   /* the producer only sends PCM back; formats flow upstream */

        size_t avail = (size_t)n - sizeof(struct audio_msg);
        size_t bytes = h.size < avail ? h.size : avail;
        int32_t frames = (int32_t)(bytes / (sizeof(int16_t) * g.play_channels));
        if (frames <= 0)
            continue;

        /* A negative write result means the stream disconnected or became invalid;
         * rebuild it on the next loop iteration instead of writing into a dead
         * stream indefinitely. */
        aaudio_result_t wr = AAudioStream_write(
            g.play, g.rx + sizeof(struct audio_msg), frames,
            20 * 1000 * 1000L);
        if (wr < 0) {
            LOGE("playback write failed: %s -- rebuilding stream",
                 AAudio_convertResultToText(wr));
            g.play_rebuild = true;
        }
    }

    LOGI("playback thread stopped");
    return NULL;
}

/* ---- capture: mic -> socket ---- */

static void *cap_thread_func(void *arg)
{
    (void)arg;
    LOGI("capture thread started");

    bool started = false;
    int16_t buf[MIC_MAX_FRAMES * WANT_CAP_CHANNELS];
    /* ~10 ms per read at the device rate, capped to the buffer. */
    int32_t mic_frames = g.cap_rate / 100;
    if (mic_frames <= 0)
        mic_frames = 1;
    if (mic_frames > MIC_MAX_FRAMES)
        mic_frames = MIC_MAX_FRAMES;

    while (g.running) {
        int fd = current_fd();
        if (!g.mic_enabled || fd < 0) {
            if (started && g.rec) {
                AAudioStream_requestStop(g.rec);
                started = false;
            }
            usleep(20000);
            continue;
        }
        if (!g.rec) {
            usleep(20000);
            continue;
        }
        if (!started) {
            if (AAudioStream_requestStart(g.rec) != AAUDIO_OK) {
                usleep(50000);
                continue;
            }
            started = true;
        }

        int32_t got = AAudioStream_read(g.rec, buf, mic_frames, 100 * 1000 * 1000L);
        if (got <= 0)
            continue;

        uint32_t bytes = (uint32_t)got * sizeof(int16_t) * g.cap_channels;
        struct audio_msg h = { .type = AUDIO_MSG_PCM, .size = bytes };
        struct iovec iov[2] = {
            { .iov_base = &h, .iov_len = sizeof(h) },
            { .iov_base = buf, .iov_len = bytes },
        };
        struct msghdr m = { .msg_iov = iov, .msg_iovlen = 2 };
        sendmsg(fd, &m, MSG_DONTWAIT | MSG_NOSIGNAL);   /* drop if the socket is full */
    }

    if (started && g.rec)
        AAudioStream_requestStop(g.rec);
    LOGI("capture thread stopped");
    return NULL;
}

/* ---- public API ---- */

void audio_start(void)
{
    if (g.running)
        return;

    /* Open the output stream and read back the rate/channels the device actually
     * chose -- this is the real playback capability we negotiate with the producer. */
    g.play_rate = 48000;
    g.play_channels = WANT_PLAY_CHANNELS;
    reopen_play_stream();

    /* Open the input stream even before the mic is enabled; it is started/stopped
     * by the capture thread. May be NULL if RECORD_AUDIO is not granted. */
    g.cap_rate = 48000;
    g.cap_channels = WANT_CAP_CHANNELS;
    g.rec = open_stream(AAUDIO_DIRECTION_INPUT, WANT_CAP_CHANNELS, NULL, NULL);
    if (g.rec) {
        g.cap_rate = AAudioStream_getSampleRate(g.rec);
        g.cap_channels = AAudioStream_getChannelCount(g.rec);
    }
    LOGI("device formats: playback %d Hz x%d, capture %d Hz x%d",
         g.play_rate, g.play_channels, g.cap_rate, g.cap_channels);

    g.running = true;
    pthread_create(&g.play_thread, NULL, play_thread_func, NULL);
    pthread_create(&g.cap_thread, NULL, cap_thread_func, NULL);
    LOGI("audio bridge started (play=%p rec=%p)", (void *)g.play, (void *)g.rec);
}

void audio_stop(void)
{
    if (!g.running)
        return;
    g.running = false;
    pthread_join(g.play_thread, NULL);
    pthread_join(g.cap_thread, NULL);

    if (g.play) {
        AAudioStream_requestStop(g.play);
        AAudioStream_close(g.play);
        g.play = NULL;
    }
    if (g.rec) {
        AAudioStream_requestStop(g.rec);
        AAudioStream_close(g.rec);
        g.rec = NULL;
    }
    g.ctx = NULL;
    LOGI("audio bridge stopped");
}

void audio_set_ctx(display_ctx *ctx)
{
    g.ctx = ctx;
}

void audio_set_mic_enabled(int enabled)
{
    g.mic_enabled = enabled != 0;
    LOGI("mic %s", g.mic_enabled ? "enabled" : "disabled");
}

void audio_set_latency(int speaker_ms, int mic_ms)
{
    g.play_latency_ms = speaker_ms;
    g.cap_latency_ms = mic_ms;
    g.resend_formats = true;   /* picked up by the playback thread on the live fd */
    LOGI("latency preset: speaker=%dms mic=%dms", speaker_ms, mic_ms);
}
