/* awl_presentation.c — wp_presentation (presentation-time).
 *
 * KWin's nested-Wayland backend refuses to start when this global is missing
 * (wayland_display.cpp returns false on !m_presentationTime), and it uses the
 * feedback to pace its own compositing.
 *
 * wp_presentation_feedback has NO requests — only events, and the protocol says
 * the object is destroyed automatically once presented or discarded is sent.
 * So there is no interface struct to fill in: the resource is created with a
 * NULL implementation (nothing can ever be dispatched on it) and only a destroy
 * listener to keep our bookkeeping straight.
 *
 * The feedback object is created on the dispatch thread and completed by the
 * render thread from awl_surface_presented() — the same hand-off the frame
 * callbacks use, and the same constraint applies: wl_resource_destroy mutates
 * the client's object map, so a presenter may only *send* here and the object
 * is retired later on the dispatch thread (next feedback() request on the same
 * surface, or surface teardown).
 *
 * Timestamps use CLOCK_MONOTONIC, the clock the clock_id event advertises. They
 * mark the moment the renderer reports the frame, not the panel's vsync: anland
 * has no hardware timestamp to offer, so the hw_clock / hw_completion flags are
 * deliberately left clear. */
#include "awl_internal.h"

#include <string.h>
#include <time.h>

static uint32_t g_pres_seq;   /* bumped under the surface's ev_lock */

static void pres_now(uint32_t* sec_hi, uint32_t* sec_lo, uint32_t* nsec) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t s = (uint64_t)ts.tv_sec;
    *sec_hi = (uint32_t)(s >> 32);
    *sec_lo = (uint32_t)(s & 0xffffffffu);
    *nsec = (uint32_t)ts.tv_nsec;
}

/* Nanoseconds until the next refresh, from the mode wl_output advertises. */
static uint32_t pres_refresh_ns(void) {
    int32_t hz = g_srv.info.refresh_hz > 0 ? g_srv.info.refresh_hz : 60;
    return (uint32_t)(1000000000ull / (uint32_t)hz);
}

static void pres_fb_res_destroy(struct wl_resource* res) {
    struct awl_pres_fb* p = wl_resource_get_user_data(res);
    if (!p) return;
    pthread_mutex_lock(&p->s->ev_lock);
    wl_list_remove(&p->link);
    pthread_mutex_unlock(&p->s->ev_lock);
    wl_resource_set_user_data(res, NULL);
    free(p);
}

static void pres_feedback(struct wl_client* client, struct wl_resource* res,
                          uint32_t id, struct wl_resource* surface_res) {
    struct wl_resource* fb = wl_resource_create(
            client, &wp_presentation_feedback_interface,
            wl_resource_get_version(res), id);
    if (!fb) {
        wl_client_post_no_memory(client);
        return;
    }
    struct awl_surface* s =
            surface_res ? wl_resource_get_user_data(surface_res) : NULL;
    if (!s || !s->resource) {
        /* Not one of ours (foreign or already dead surface): the object still
         * has to complete so the client's id stays valid. */
        wl_resource_set_implementation(fb, NULL, NULL, NULL);
        wp_presentation_feedback_send_discarded(fb);
        return;
    }

    struct awl_pres_fb* p = calloc(1, sizeof(*p));
    if (!p) {
        wl_resource_destroy(fb);
        wl_client_post_no_memory(client);
        return;
    }
    p->resource = fb;
    p->s = s;
    wl_resource_set_implementation(fb, NULL, p, pres_fb_res_destroy);

    pthread_mutex_lock(&s->ev_lock);
    /* Retire feedback objects whose event already went out from the render
     * thread, before parking the new one — the same one-request-delay rule as
     * the frame callbacks: the client keeps a different id until delete_id
     * ships, so this is protocol-safe and bounds the parked set. */
    struct awl_pres_fb* it;
    struct awl_pres_fb* tmp;
    wl_list_for_each_safe(it, tmp, &s->pres_fbs, link) {
        if (it->detached)
            wl_resource_destroy(it->resource);   /* listener unlinks + frees */
    }
    wl_list_insert(s->pres_fbs.prev, &p->link);
    pthread_mutex_unlock(&s->ev_lock);
    LOGD("presentation feedback on surface %llu", (unsigned long long)s->id);
}

static void pres_destroy(struct wl_client* c, struct wl_resource* res) {
    wl_resource_destroy(res);
}

static const struct wp_presentation_interface pres_iface = {
    .destroy = pres_destroy,
    .feedback = pres_feedback,
};

static void pres_bind(struct wl_client* client, void* data, uint32_t version,
                      uint32_t id) {
    uint32_t v = version < 2 ? version : 2;
    struct wl_resource* res = wl_resource_create(
            client, &wp_presentation_interface, v, id);
    if (!res) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(res, &pres_iface, NULL, NULL);
    /* The clock is announced when the global is bound, before any feedback
     * object exists. */
    wp_presentation_send_clock_id(res, (uint32_t)CLOCK_MONOTONIC);
    LOGD("presentation bound v%u (CLOCK_MONOTONIC)", v);
}

/* Called from awl_surface_presented() with s->ev_lock held (render thread) —
 * the same place wl_callback.done goes out: send only, retire later. */
void awl_presentation_presented(struct awl_surface* s) {
    if (wl_list_empty(&s->pres_fbs))
        return;
    uint32_t sec_hi, sec_lo, nsec;
    pres_now(&sec_hi, &sec_lo, &nsec);
    uint32_t refresh = pres_refresh_ns();
    uint64_t seq = (uint64_t)++g_pres_seq;
    struct awl_pres_fb* p;
    wl_list_for_each(p, &s->pres_fbs, link) {
        if (p->detached)
            continue;
        wp_presentation_feedback_send_presented(
                p->resource, sec_hi, sec_lo, nsec, refresh,
                (uint32_t)(seq >> 32), (uint32_t)(seq & 0xffffffffu), 0);
        p->detached = 1;
    }
}

void awl_presentation_setup(void) {
    if (!wl_global_create(g_srv.display, &wp_presentation_interface, 2,
                          NULL, pres_bind))
        LOGE("wp_presentation global create failed");
    else
        LOGI("wp_presentation global ready");
}
