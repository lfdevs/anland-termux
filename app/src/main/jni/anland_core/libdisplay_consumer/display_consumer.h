#ifndef DISPLAY_CONSUMER_H
#define DISPLAY_CONSUMER_H

#include <stdint.h>
#include <stddef.h>
#include "../common/protocol.h"

typedef struct display_ctx display_ctx;
int connect_to_deamon_with_fd(display_ctx **out, int ctrl_fd);
int  connect_to_deamon(display_ctx **ctx, const char *socket_path);
void disconnect(display_ctx *ctx);
int  set_screen_info(display_ctx *ctx, uint32_t width, uint32_t height, uint32_t format, uint32_t refresh);
int  push_dmabufs(display_ctx *ctx, const int *fds, const struct buf_info *infos, int count);
int  select_dmabuf(display_ctx *ctx, int idx);
/* 1 = rendered (possibly without a fence), 0 = waiting, -1 = connection error,
 * -2 = aborted. The caller owns *fence_fd and must queue or cancel with it.
 * Use bounded timeouts so the render thread can check for shutdown. */
int  wait_refresh_done(display_ctx *ctx, int timeout_ms, int *fence_fd);
int  push_input_event(display_ctx *ctx, const struct InputEvent *event);
int  push_input_event_with_length(display_ctx *ctx, const struct InputEvent *event, void* payload, size_t size);
int  set_fallback_callback(display_ctx *ctx, void (*on_fallback)(void *), void *userdata);
int  poll_output_event(display_ctx *ctx, struct OutputEvent *event, int timeout_ms);
int  poll_output_event_extend_data(display_ctx *ctx, void* payload, size_t size, int timeout_ms);
int  set_exit_fallback_callback(display_ctx *ctx, void (*on_exit_fallback)(void *), void *userdata);
int  get_data_fd(display_ctx *ctx);
int  get_audio_fd(display_ctx *ctx);
void handle_unhandled_event(display_ctx *ctx, const struct OutputEvent *event);


struct resources {
    uint32_t service_type;
    int32_t type;
    uint32_t num;
    int* fds;
};
struct service_info {
    uint32_t type;
    //资源分配函数指针
    struct resources (*allocate_resource)(uint32_t* args);//only support 3 args
    void (*free_resource)(struct resources res);
};
typedef struct service_info service_info;
typedef struct resources resources;
void allocate_services(struct display_ctx *ctx, struct service_info *services, int num_services);
void handle_resource_request(struct display_ctx *ctx, struct OutputEvent *event);
void free_resources(struct display_ctx *ctx);//释放资源，保留服务信息
#endif
