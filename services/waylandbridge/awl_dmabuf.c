/* awl_dmabuf.c — zwp_linux_dmabuf_v1 v4 (registers the buffer; a commit dup's
 * its fd into the surface's frame queue, awl_surface_apply_buffer, and the
 * renderer imports the EGLImage from the queue element).
 *
 * v4: the format/modifier events are deprecated and must not be sent; the
 * capabilities travel in a zwp_linux_dmabuf_feedback_v1 object instead
 * (format table + main device + one tranche). v1..v3 clients still get the
 * legacy events, and both paths describe the same (format, modifier) set. */
#define _GNU_SOURCE     /* bionic: memfd_create */
#include "awl_internal.h"

#include <errno.h>
#include <string.h>
#include <sys/mman.h>   /* memfd_create, mmap — the feedback format table */
#include <sys/stat.h>   /* fstat: dma-buf inode, and the DRM node's st_rdev */
#include <sys/types.h>  /* dev_t */
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#define AWL_DMABUF_VERSION 4   /* feedback-capable; <4 gets the legacy events */

/* v3 event flow: params.created(buffer); create_immed builds the buffer directly */

struct awl_dmabuf_format {
    uint32_t format;
    uint64_t modifier;
};

static const struct awl_dmabuf_format k_supported[] = {
    { AWL_FORMAT_ARGB8888, DRM_FORMAT_MOD_INVALID },
    { AWL_FORMAT_ARGB8888, DRM_FORMAT_MOD_LINEAR },
    { AWL_FORMAT_XRGB8888, DRM_FORMAT_MOD_INVALID },
    { AWL_FORMAT_XRGB8888, DRM_FORMAT_MOD_LINEAR },
};

/* wl_buffer.destroy request: destroy the resource (triggers dmabuf_buffer_destroy_handler) */
static void dmabuf_wl_buffer_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct wl_buffer_interface dmabuf_buffer_iface = {
    .destroy = dmabuf_wl_buffer_destroy,
};

static void dmabuf_buffer_destroy_handler(struct wl_resource* res) {
    struct awl_buffer* b = wl_resource_get_user_data(res);
    if (!b) return;
    /* Strip the surfaces' pending/current/latched references (dispatch
     * thread). Queued frames keep their own fd dup + wrapper reference: the
     * wrapper only loses its resource here (under g_bufref_lock — a release
     * being sent from another thread finishes first) and is freed by the
     * last frame that leaves a queue. */
    awl_surface_buffer_gone(res);
    pthread_mutex_lock(&g_bufref_lock);
    b->resource = NULL;
    pthread_mutex_unlock(&g_bufref_lock);
    wl_resource_set_user_data(res, NULL);
    awl_buffer_unref(b);
}

static struct awl_buffer* dmabuf_buffer_create(struct wl_client* client,
                                               uint32_t id, uint32_t version,
                                               int fd, uint32_t w, uint32_t h,
                                               uint32_t stride, uint32_t format,
                                               uint64_t modifier) {
    if (format != AWL_FORMAT_ARGB8888 && format != AWL_FORMAT_XRGB8888) {
        LOGE("dmabuf unsupported format 0x%08x", format);
        return NULL;
    }
    struct awl_buffer* b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    atomic_init(&b->refs, 1);          /* the resource's reference */
    b->dmabuf_fd = fd;                 /* take over the fd */
    struct stat st;                    /* identity once, here — the queue element
                                        * carries it, the render side compares
                                        * inodes with no per-frame fstat */
    b->ino = fstat(fd, &st) == 0 ? (uint64_t)st.st_ino : 0;
    b->width = w;
    b->height = h;
    b->stride = stride;
    b->drm_format = format;

    b->resource = wl_resource_create(client, &wl_buffer_interface,
                                     version, id);
    if (!b->resource) {
        close(fd);
        free(b);
        return NULL;
    }
    wl_resource_set_implementation(b->resource, &dmabuf_buffer_iface, b,
                                   dmabuf_buffer_destroy_handler);
    LOGD("dmabuf buffer %ux%u stride=%u fmt=%c%c%c%c mod=0x%llx fd=%d",
            w, h, stride,
            (char)(format & 0xff), (char)((format >> 8) & 0xff),
            (char)((format >> 16) & 0xff), (char)((format >> 24) & 0xff),
            (unsigned long long)modifier, fd);
    return b;
}

/* ---------------- zwp_linux_dmabuf_feedback_v1 (v4) ----------------
 * The client asks for a feedback object (default, or per surface) and reads:
 *   format_table  a shared memory table of {u32 format, u32 pad, u64 modifier}
 *   main_device   the dev_t clients should allocate on
 *   one tranche   target device + the table indices that are usable, closed by
 *                 tranche_done; the object is closed by done()
 * The description is static: anland imports whatever this table describes as
 * an EGLImage, so nothing changes with surface size — get_surface_feedback
 * returns the same set as get_default_feedback. */

/* wire layout of one format_table entry (see the protocol description) */
struct awl_fmt_table_entry {
    uint32_t format;
    uint32_t padding;
    uint64_t modifier;
};

/* DRM render node the clients allocate on, as a dev_t, resolved once. The
 * clients run in a container where the node is bind-mounted at the same path,
 * so st_rdev is directly usable as the "which device" identity they need to
 * recognise. ANLAND_DRM_DEVICE is the override the in-container session
 * already exports. */
static dev_t drm_device_id(void) {
    static dev_t cached;
    static int have;
    if (!have) {
        const char* p = getenv("ANLAND_DRM_DEVICE");
        if (!p || !*p) p = "/dev/dri/renderD128";
        struct stat st;
        if (stat(p, &st) == 0)
            cached = st.st_rdev;
        else
            LOGE("dmabuf: stat %s failed: %s — feedback advertises no device",
                 p, strerror(errno));
        have = 1;
        if (cached)
            LOGI("dmabuf: feedback device %s → dev_t %llu", p,
                 (unsigned long long)cached);
    }
    return cached;
}

/* append one dev_t to a wl_array in the wire shape the client expects (the
 * array carries the raw dev_t bytes) */
static int array_add_device(struct wl_array* a, dev_t dev) {
    void* p = wl_array_add(a, sizeof(dev));
    if (!p)
        return -1;
    memcpy(p, &dev, sizeof(dev));
    return 0;
}

static void feedback_finish(struct wl_resource* res) {
    zwp_linux_dmabuf_feedback_v1_send_done(res);
}

static void feedback_send(struct wl_resource* res) {
    const size_t n = sizeof(k_supported) / sizeof(k_supported[0]);
    const size_t bytes = n * sizeof(struct awl_fmt_table_entry);
    dev_t dev = drm_device_id();
    struct wl_array a;

    /* format table: a memfd the client mmaps */
    int fd = memfd_create("awl-dmabuf-fmt", MFD_CLOEXEC);
    if (fd < 0) {
        LOGE("dmabuf: format table memfd failed: %s", strerror(errno));
        feedback_finish(res);
        return;
    }
    if (ftruncate(fd, (off_t)bytes) != 0) {
        LOGE("dmabuf: format table ftruncate failed: %s", strerror(errno));
        close(fd);
        feedback_finish(res);
        return;
    }
    void* map = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        LOGE("dmabuf: format table mmap failed: %s", strerror(errno));
        close(fd);
        feedback_finish(res);
        return;
    }
    struct awl_fmt_table_entry* tab = map;
    for (size_t i = 0; i < n; i++) {
        tab[i].format = k_supported[i].format;
        tab[i].padding = 0;
        tab[i].modifier = k_supported[i].modifier;
    }
    munmap(map, bytes);
    lseek(fd, 0, SEEK_SET);
    zwp_linux_dmabuf_feedback_v1_send_format_table(res, fd, (uint32_t)bytes);
    close(fd);

    /* main_device */
    wl_array_init(&a);
    if (array_add_device(&a, dev) != 0) {
        wl_array_release(&a);
        feedback_finish(res);
        return;
    }
    zwp_linux_dmabuf_feedback_v1_send_main_device(res, &a);
    wl_array_release(&a);

    /* one tranche: the (single) device clients should use, every table index,
     * no flags */
    wl_array_init(&a);
    if (array_add_device(&a, dev) != 0) {
        wl_array_release(&a);
        feedback_finish(res);
        return;
    }
    zwp_linux_dmabuf_feedback_v1_send_tranche_target_device(res, &a);
    wl_array_release(&a);

    wl_array_init(&a);
    uint16_t* idx = wl_array_add(&a, n * sizeof(uint16_t));
    if (idx) {
        for (size_t i = 0; i < n; i++)
            idx[i] = (uint16_t)i;
        zwp_linux_dmabuf_feedback_v1_send_tranche_formats(res, &a);
    }
    wl_array_release(&a);

    zwp_linux_dmabuf_feedback_v1_send_tranche_flags(res, 0);
    zwp_linux_dmabuf_feedback_v1_send_tranche_done(res);
    feedback_finish(res);
}

static void feedback_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct zwp_linux_dmabuf_feedback_v1_interface feedback_iface = {
    .destroy = feedback_destroy,
};

/* Both requests hand out the same static description. */
static void dmabuf_new_feedback(struct wl_client* c, struct wl_resource* res,
                                uint32_t id) {
    struct wl_resource* fb = wl_resource_create(
            c, &zwp_linux_dmabuf_feedback_v1_interface,
            wl_resource_get_version(res), id);
    if (!fb) {
        wl_client_post_no_memory(c);
        return;
    }
    wl_resource_set_implementation(fb, &feedback_iface, NULL, NULL);
    feedback_send(fb);
}

static void dmabuf_get_default_feedback(struct wl_client* c,
                                        struct wl_resource* res, uint32_t id) {
    LOGD("get_default_feedback id=%u", id);
    dmabuf_new_feedback(c, res, id);
}

static void dmabuf_get_surface_feedback(struct wl_client* c,
                                        struct wl_resource* res, uint32_t id,
                                        struct wl_resource* surface) {
    (void)surface;   /* per-surface feedback would be identical */
    LOGD("get_surface_feedback id=%u", id);
    dmabuf_new_feedback(c, res, id);
}

/* ---------------- zwp_linux_buffer_params_v1 ---------------- */

struct awl_params {
    struct wl_resource* resource;
    uint64_t modifier;
    uint32_t width, height, stride;
    uint32_t format;
    int fd;
    int has_fd, has_geometry, has_format;
};

static void params_destroy(struct wl_client* c, struct wl_resource* res) {
    struct awl_params* p = wl_resource_get_user_data(res);
    if (p && p->fd >= 0) close(p->fd);
    wl_resource_destroy(res);
}

static void params_add(struct wl_client* c, struct wl_resource* res,
                       int32_t fd, uint32_t plane_idx, uint32_t offset,
                       uint32_t stride, uint32_t modifier_hi, uint32_t modifier_lo) {
    struct awl_params* p = wl_resource_get_user_data(res);
    LOGD("params_add fd=%d plane=%u off=%u stride=%u", fd, plane_idx, offset, stride);
    if (!p) {
        close(fd);
        return;
    }
    if (plane_idx != 0) {
        LOGE("multi-plane dmabuf unsupported (plane %u)", plane_idx);
        close(fd);
        wl_resource_post_error(res,
                ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_PLANE_IDX,
                "only single plane supported");
        return;
    }
    if (p->fd >= 0) close(p->fd);   /* repeated add overwrites */
    p->fd = fd;
    p->has_fd = 1;
    (void)offset;
    p->stride = stride;
    p->modifier = ((uint64_t)modifier_hi << 32) | modifier_lo;
}

static void params_create(struct wl_client* c, struct wl_resource* res,
                          int32_t width, int32_t height, uint32_t format,
                          uint32_t flags) {
    struct awl_params* p = wl_resource_get_user_data(res);
    if (!p || !p->has_fd || width <= 0 || height <= 0) {
        wl_resource_post_error(res,
                ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,
                "incomplete dmabuf params");
        return;
    }
    struct awl_buffer* b = dmabuf_buffer_create(c, 0, 1, p->fd, width, height,
                                                p->stride, format, p->modifier);
    if (!b) {
        zwp_linux_buffer_params_v1_send_failed(res);
        return;
    }
    p->fd = -1;   /* ownership transferred; params destroyed by the client afterwards */
    p->has_fd = 0;
    zwp_linux_buffer_params_v1_send_created(res, b->resource);
}

static void params_create_immed(struct wl_client* c, struct wl_resource* res,
                                uint32_t buffer_id, int32_t width, int32_t height,
                                uint32_t format, uint32_t flags) {
    struct awl_params* p = wl_resource_get_user_data(res);
    if (!p || !p->has_fd || width <= 0 || height <= 0) {
        wl_resource_post_error(res,
                ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,
                "incomplete dmabuf params");
        return;
    }
    struct awl_buffer* b = dmabuf_buffer_create(c, buffer_id, 1, p->fd,
                                                width, height, p->stride,
                                                format, p->modifier);
    if (!b) {
        wl_resource_post_error(res,
                ZWP_LINUX_BUFFER_PARAMS_V1_ERROR_INVALID_WL_BUFFER,
                "unsupported format");
        close(p->fd);
        p->fd = -1;
        p->has_fd = 0;
        return;
    }
    p->fd = -1;
    p->has_fd = 0;
}

static const struct zwp_linux_buffer_params_v1_interface params_iface = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};

static void params_res_destroy(struct wl_resource* res) {
    struct awl_params* p = wl_resource_get_user_data(res);
    if (p) {
        if (p->fd >= 0) close(p->fd);
        free(p);
    }
}

static void dmabuf_create_params(struct wl_client* c,
                                 struct wl_resource* dmabuf_res, uint32_t id) {
    LOGD("create_params id=%u", id);
    struct wl_resource* res = wl_resource_create(
            c, &zwp_linux_buffer_params_v1_interface,
            wl_resource_get_version(dmabuf_res), id);
    struct awl_params* p = calloc(1, sizeof(*p));
    if (!res || !p) {
        if (res) wl_resource_destroy(res);
        wl_resource_post_no_memory(dmabuf_res);
        return;
    }
    p->fd = -1;
    wl_resource_set_implementation(res, &params_iface, p, params_res_destroy);
}

static void dmabuf_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct zwp_linux_dmabuf_v1_interface dmabuf_iface = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
    /* v4; never invoked by a v<4 client */
    .get_default_feedback = dmabuf_get_default_feedback,
    .get_surface_feedback = dmabuf_get_surface_feedback,
};

static void dmabuf_bind(struct wl_client* client, void* data,
                        uint32_t version, uint32_t id) {
    uint32_t v = version < AWL_DMABUF_VERSION ? version : AWL_DMABUF_VERSION;
    struct wl_resource* res = wl_resource_create(
            client, &zwp_linux_dmabuf_v1_interface, v, id);
    wl_resource_set_implementation(res, &dmabuf_iface, NULL, NULL);
    /* v4 deprecated the format/modifier events: sending them to a modern
     * client is a protocol violation — it reads the capabilities from the
     * feedback object instead. */
    if (v >= 4) {
        LOGD("dmabuf bind v%u (feedback mode)", v);
        return;
    }
    for (size_t i = 0; i < sizeof(k_supported) / sizeof(k_supported[0]); i++) {
        zwp_linux_dmabuf_v1_send_format(res, k_supported[i].format);
        if (v >= ZWP_LINUX_DMABUF_V1_MODIFIER_SINCE_VERSION)
            zwp_linux_dmabuf_v1_send_modifier(
                    res, k_supported[i].format,
                    (uint32_t)(k_supported[i].modifier >> 32),
                    (uint32_t)(k_supported[i].modifier & 0xffffffff));
    }
}

void awl_dmabuf_setup(void) {
    if (!wl_global_create(g_srv.display,
                          &zwp_linux_dmabuf_v1_interface, AWL_DMABUF_VERSION,
                          NULL, dmabuf_bind))
        LOGE("zwp_linux_dmabuf_v1 global create failed");
}
