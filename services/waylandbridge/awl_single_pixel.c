/* awl_single_pixel.c — wp_single_pixel_buffer_manager_v1 (staging).
 *
 * A client that needs a solid colour — a compositor's background, a client-side
 * drop shadow, a letterbox bar — can ask for a 1x1 buffer instead of uploading
 * and maintaining a whole pool for it.
 *
 * The buffer carries no memory at all: awl_buffer.solid marks "this is a
 * colour", and awl_surface_apply_buffer records it as an shm-like source whose
 * pixel is awl_surface.solid_color. The renderer uploads that as a 1x1 texture
 * and the viewport scales it to the layer, which is exactly the intended use
 * (the protocol assumes viewporter is available).
 *
 * KWin's nested-Wayland backend treats this global as a hard requirement:
 * wayland_display.cpp returns false when m_singlePixelManager is missing, so an
 * unmodified compositor cannot run inside anland without it.
 *
 * v1 has a single create request (one premultiplied RGBA colour); the xyzw
 * variant from early drafts was removed upstream. */
#include "awl_internal.h"

#include <string.h>

/* One component of the wire's 0..UINT32_MAX percentage as a byte. */
static uint8_t spb_comp(uint32_t v) {
    return (uint8_t)(((uint64_t)v * 255u) / 0xffffffffull);
}

static void spb_buffer_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct wl_buffer_interface spb_buffer_iface = {
    .destroy = spb_buffer_destroy,
};

/* Same ownership shape as the dmabuf wl_buffer wrapper: the resource holds one
 * reference, a surface that latched it holds its own (awl_surface_buffer_gone
 * strips those), and the wrapper dies with the last reference. */
static void spb_buffer_gone(struct wl_resource* res) {
    struct awl_buffer* b = wl_resource_get_user_data(res);
    if (!b) return;
    awl_surface_buffer_gone(res);
    pthread_mutex_lock(&g_bufref_lock);
    b->resource = NULL;
    pthread_mutex_unlock(&g_bufref_lock);
    wl_resource_set_user_data(res, NULL);
    awl_buffer_unref(b);
}

static void spb_create_rgba(struct wl_client* client, struct wl_resource* res,
                            uint32_t id, uint32_t r, uint32_t g, uint32_t bl,
                            uint32_t a) {
    uint8_t cr = spb_comp(r);
    uint8_t cg = spb_comp(g);
    uint8_t cb = spb_comp(bl);
    uint8_t ca = spb_comp(a);

    struct awl_buffer* b = calloc(1, sizeof(*b));
    if (!b) {
        wl_client_post_no_memory(client);
        return;
    }
    atomic_init(&b->refs, 1);      /* the resource's reference */
    b->dmabuf_fd = -1;             /* no memory: solid_color is the whole source */
    b->ino = 0;
    b->width = 1;
    b->height = 1;
    b->stride = 4;
    b->drm_format = AWL_FORMAT_ARGB8888;   /* memory order B,G,R,A */
    b->solid = 1;
    b->solid_color = ((uint32_t)ca << 24) | ((uint32_t)cr << 16) |
                     ((uint32_t)cg << 8) | (uint32_t)cb;

    b->resource = wl_resource_create(client, &wl_buffer_interface, 1, id);
    if (!b->resource) {
        free(b);
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(b->resource, &spb_buffer_iface, b,
                                   spb_buffer_gone);
    LOGD("single-pixel buffer argb=0x%08x", b->solid_color);
}

static void spb_manager_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct wp_single_pixel_buffer_manager_v1_interface spb_iface = {
    .destroy = spb_manager_destroy,
    .create_u32_rgba_buffer = spb_create_rgba,
};

static void spb_bind(struct wl_client* client, void* data, uint32_t version,
                     uint32_t id) {
    uint32_t v = version < 1 ? version : 1;
    struct wl_resource* res = wl_resource_create(
            client, &wp_single_pixel_buffer_manager_v1_interface, v, id);
    if (!res) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(res, &spb_iface, NULL, NULL);
    LOGD("single-pixel manager bound v%u", v);
}

void awl_single_pixel_setup(void) {
    if (!wl_global_create(g_srv.display,
                          &wp_single_pixel_buffer_manager_v1_interface, 1,
                          NULL, spb_bind))
        LOGE("wp_single_pixel_buffer_manager_v1 global create failed");
    else
        LOGI("wp_single_pixel_buffer_manager_v1 global ready");
}
