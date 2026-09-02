#ifndef NATIVE_AUDIO_H
#define NATIVE_AUDIO_H

#include "display_consumer.h"

/*
 * Consumer-side audio bridge over the display library's audio socket.
 *
 *   - playback: reads desktop PCM datagrams from the socket and writes them to an
 *                AAudio output stream (the Android speaker);
 *   - capture : reads the Android mic via an AAudio input stream and writes PCM
 *                datagrams to the socket (the producer exposes them as a Linux
 *                recording source). Only active while the mic is enabled.
 *
 * Playback is driven by an AAudio data callback. The socket thread appends PCM to a
 * frame-aligned ring buffer and the callback supplies near-silent keepalive samples
 * on underrun. The output stream is rebuilt independently after AAudio failures and
 * may idle-stop after silence; the display connection is never rebuilt for this.
 * The capture stream is opened once and only started while the mic is enabled. The
 * active display_ctx (and thus the live audio fd) is swapped via audio_set_ctx().
 * The audio fd is owned by the display library -- never closed here.
 */

void audio_start(void);
void audio_stop(void);

/* Point the bridge at the current connection (its audio fd is fetched live via
 * get_audio_fd), or NULL to detach. Mirrors the event thread's use of s->ctx. */
void audio_set_ctx(display_ctx *ctx);

/* Enable/disable microphone capture (the up path). Off by default; the Java layer
 * turns it on only once RECORD_AUDIO has been granted. */
void audio_set_mic_enabled(int enabled);

/* Latency presets, separately for the speaker (playback) and microphone (capture)
 * paths, in milliseconds. 0 = let the engine pick the default quantum. The value is
 * converted to a frame count using the device's negotiated rate and forwarded to the
 * producer, which applies it as the PipeWire node.latency. Takes effect immediately
 * (the formats are re-announced on the live connection). */
void audio_set_latency(int speaker_ms, int mic_ms);

/* Keep the output stream hot for burst reliability, or allow it to idle-stop after
 * silence to save standby power. Disabled by default. */
void audio_set_keepalive(int enabled);

#endif
