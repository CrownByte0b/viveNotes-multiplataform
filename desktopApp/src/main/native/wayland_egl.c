#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wayland-client.h>
#include <wayland-egl.h>
#include <EGL/egl.h>
#include "viewporter-client-protocol.h"

typedef struct CurrentBinding {
    EGLDisplay display;
    EGLSurface draw, read;
    EGLContext context;
    EGLenum api;
    struct CurrentBinding *previous;
} CurrentBinding;
static _Thread_local CurrentBinding *current_bindings;

typedef struct {
    struct wl_display *display; /* Borrowed from JBR; never disconnected here. */
    struct wl_event_queue *queue;
    struct wl_registry *registry;
    struct wl_compositor *compositor;
    struct wl_subcompositor *subcompositor;
    struct wp_viewporter *viewporter;
    int ready;
} ConnectionState;

typedef struct WaylandConnection {
    ConnectionState state;
    struct WaylandConnection *next;
} WaylandConnection;
static WaylandConnection *connections;

typedef struct {
    struct wl_display *display;
    WaylandConnection *connection;
    struct wl_surface *surface;
    struct wl_subsurface *subsurface;
    struct wp_viewport *viewport;
    struct wl_egl_window *window;
    EGLDisplay egl_display;
    EGLContext context;
    EGLSurface egl_surface;
    int width, height, logical_width, logical_height, x, y;
} RenderTarget;

static void fail(JNIEnv *env, const char *operation) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), operation);
}

static void registry_global(void *data, struct wl_registry *registry, uint32_t name,
                            const char *interface, uint32_t version) {
    ConnectionState *r = data;
    if (strcmp(interface, "wl_compositor") == 0 && version >= 3)
        r->compositor = wl_registry_bind(registry, name, &wl_compositor_interface, 3);
    else if (strcmp(interface, "wl_subcompositor") == 0)
        r->subcompositor = wl_registry_bind(registry, name, &wl_subcompositor_interface, 1);
    else if (strcmp(interface, "wp_viewporter") == 0)
        r->viewporter = wl_registry_bind(registry, name, &wp_viewporter_interface, 1);
}
static void registry_removed(void *data, struct wl_registry *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}
static const struct wl_registry_listener registry_listener = {registry_global, registry_removed};

/* A registry consumes server resources until the display disconnects. Share one for all layers,
   rather than creating a fresh registry whenever a menu opens. JBR owns the display lifetime. */
static WaylandConnection *connection_for(struct wl_display *display) {
    for (WaylandConnection *c = connections; c; c = c->next)
        if (c->state.display == display) return c->state.ready ? c : NULL;
    WaylandConnection *c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    c->next = connections; connections = c;
    c->state.display = display;
    c->state.queue = wl_display_create_queue(display);
    if (!c->state.queue) return NULL;
    struct wl_display *wrapper = wl_proxy_create_wrapper(display);
    if (!wrapper) return NULL;
    wl_proxy_set_queue((struct wl_proxy *)wrapper, c->state.queue);
    c->state.registry = wl_display_get_registry(wrapper);
    wl_proxy_wrapper_destroy(wrapper);
    if (!c->state.registry) return NULL;
    wl_registry_add_listener(c->state.registry, &registry_listener, &c->state);
    if (wl_display_roundtrip_queue(display, c->state.queue) < 0) return NULL;
    c->state.ready = c->state.compositor && c->state.subcompositor && c->state.viewporter;
    return c->state.ready ? c : NULL;
}

static void destroy(RenderTarget *r) {
    if (!r) return;
    if (r->egl_display != EGL_NO_DISPLAY) {
        /* A layer can be removed during another layer's Compose callback. Never restore a
           saved binding to a context that this removal is about to destroy. */
        for (CurrentBinding *b = current_bindings; b; b = b->previous) {
            if (b->context == r->context && r->context != EGL_NO_CONTEXT) {
                b->display = EGL_NO_DISPLAY; b->context = EGL_NO_CONTEXT;
                b->draw = b->read = EGL_NO_SURFACE;
            }
        }
        eglMakeCurrent(r->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (r->egl_surface != EGL_NO_SURFACE) eglDestroySurface(r->egl_display, r->egl_surface);
        if (r->context != EGL_NO_CONTEXT) eglDestroyContext(r->egl_display, r->context);
        /* eglGetDisplay is shared with other users of this connection: do not eglTerminate it. */
    }
    if (r->window) wl_egl_window_destroy(r->window);
    if (r->viewport) wp_viewport_destroy(r->viewport);
    if (r->subsurface) wl_subsurface_destroy(r->subsurface);
    if (r->surface) wl_surface_destroy(r->surface);
    if (r->display) wl_display_flush(r->display);
    free(r);
}

#define JNI_METHOD(name) Java_com_vivenotes_desktop_rendering_WaylandEgl_##name

JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv *env, jclass type, jlong display, jlong parent,
                                           jint width, jint height) {
    (void)type;
    if (!display || !parent || width <= 0 || height <= 0) {
        fail(env, "Invalid Wayland surface dimensions or handles"); return 0;
    }
    RenderTarget *r = calloc(1, sizeof(*r));
    if (!r) { fail(env, "Cannot allocate a Wayland render target"); return 0; }
    const char *error = "Cannot create a Wayland render target";
    r->display = (struct wl_display *)(intptr_t)display;
    r->connection = connection_for(r->display);
    if (!r->connection) {
        error = "Wayland compositor lacks subsurface or viewporter support"; goto failed;
    }
    r->surface = wl_compositor_create_surface(r->connection->state.compositor);
    if (!r->surface) goto failed;
    r->subsurface = wl_subcompositor_get_subsurface(r->connection->state.subcompositor, r->surface,
                                                   (struct wl_surface *)(intptr_t)parent);
    if (!r->subsurface) goto failed;
    wl_subsurface_set_desync(r->subsurface);
    r->viewport = wp_viewporter_get_viewport(r->connection->state.viewporter, r->surface);
    if (!r->viewport) goto failed;
    /* Empty child input regions keep mouse, pen, touch and DnD on JBR's parent surface. */
    struct wl_region *empty = wl_compositor_create_region(r->connection->state.compositor);
    if (!empty) goto failed;
    wl_surface_set_input_region(r->surface, empty);
    wl_region_destroy(empty);
    r->window = wl_egl_window_create(r->surface, width, height);
    if (!r->window) goto failed;
    r->width = width; r->height = height;
    r->egl_display = eglGetDisplay((EGLNativeDisplayType)r->display);
    if (r->egl_display == EGL_NO_DISPLAY || !eglInitialize(r->egl_display, NULL, NULL)) {
        error = "Cannot initialize Wayland EGL"; goto failed;
    }
    if (!eglBindAPI(EGL_OPENGL_API)) { error = "Cannot bind OpenGL through EGL"; goto failed; }
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_STENCIL_SIZE, 8, EGL_NONE
    };
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(r->egl_display, attributes, &config, 1, &count) || count == 0) {
        error = "No suitable Wayland EGL framebuffer configuration"; goto failed;
    }
    r->context = eglCreateContext(r->egl_display, config, EGL_NO_CONTEXT, NULL);
    if (r->context == EGL_NO_CONTEXT) { error = "Cannot create a Wayland EGL context"; goto failed; }
    r->egl_surface = eglCreateWindowSurface(r->egl_display, config, (EGLNativeWindowType)r->window, NULL);
    if (r->egl_surface == EGL_NO_SURFACE) { error = "Cannot create a Wayland EGL window surface"; goto failed; }
    if (!eglMakeCurrent(r->egl_display, r->egl_surface, r->egl_surface, r->context)) {
        error = "Cannot activate the Wayland EGL context"; goto failed;
    }
    eglSwapInterval(r->egl_display, 0);
    return (jlong)(intptr_t)r;
failed:
    destroy(r);
    fail(env, error);
    return 0;
}

JNIEXPORT void JNICALL JNI_METHOD(configure)(JNIEnv *env, jclass type, jlong handle, jint width, jint height,
                                             jint logical_width, jint logical_height, jint x, jint y) {
    (void)type;
    RenderTarget *r = (RenderTarget *)(intptr_t)handle;
    if (!r || width <= 0 || height <= 0 || logical_width <= 0 || logical_height <= 0) {
        fail(env, "Invalid Wayland EGL geometry"); return;
    }
    if (r->width != width || r->height != height) {
        wl_egl_window_resize(r->window, width, height, 0, 0);
        r->width = width; r->height = height;
    }
    if (r->logical_width != logical_width || r->logical_height != logical_height) {
        wp_viewport_set_destination(r->viewport, logical_width, logical_height);
        r->logical_width = logical_width; r->logical_height = logical_height;
    }
    if (r->x != x || r->y != y) {
        wl_subsurface_set_position(r->subsurface, x, y); r->x = x; r->y = y;
    }
}

JNIEXPORT void JNICALL JNI_METHOD(makeCurrent)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    RenderTarget *r = (RenderTarget *)(intptr_t)handle;
    if (!r || !eglBindAPI(EGL_OPENGL_API) || !eglMakeCurrent(r->egl_display, r->egl_surface, r->egl_surface, r->context))
        fail(env, "Cannot activate the Wayland EGL context");
}
JNIEXPORT jlong JNICALL JNI_METHOD(saveCurrent)(JNIEnv *env, jclass type) {
    (void)type;
    CurrentBinding *b = malloc(sizeof(*b));
    if (!b) { fail(env, "Cannot save the EGL context binding"); return 0; }
    b->display = eglGetCurrentDisplay(); b->draw = eglGetCurrentSurface(EGL_DRAW);
    b->read = eglGetCurrentSurface(EGL_READ); b->context = eglGetCurrentContext(); b->api = eglQueryAPI();
    b->previous = current_bindings; current_bindings = b;
    return (jlong)(intptr_t)b;
}
JNIEXPORT void JNICALL JNI_METHOD(restoreCurrent)(JNIEnv *env, jclass type, jlong binding) {
    (void)env; (void)type;
    CurrentBinding *b = (CurrentBinding *)(intptr_t)binding;
    if (!b) return;
    EGLDisplay active = eglGetCurrentDisplay();
    if (active != EGL_NO_DISPLAY) eglMakeCurrent(active, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglBindAPI(b->api);
    if (b->display != EGL_NO_DISPLAY) eglMakeCurrent(b->display, b->draw, b->read, b->context);
    CurrentBinding **entry = &current_bindings;
    while (*entry && *entry != b) entry = &(*entry)->previous;
    if (*entry) *entry = b->previous;
    free(b);
}
JNIEXPORT void JNICALL JNI_METHOD(present)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    RenderTarget *r = (RenderTarget *)(intptr_t)handle;
    if (!r || !eglSwapBuffers(r->egl_display, r->egl_surface)) {
        fail(env, "Cannot present the Wayland EGL buffer"); return;
    }
    wl_display_flush(r->display);
    wl_display_dispatch_queue_pending(r->display, r->connection->state.queue);
}
JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type; destroy((RenderTarget *)(intptr_t)handle);
}
JNIEXPORT jlong JNICALL JNI_METHOD(surface)(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    RenderTarget *r = (RenderTarget *)(intptr_t)handle;
    return r ? (jlong)(intptr_t)r->surface : 0;
}
JNIEXPORT void JNICALL JNI_METHOD(placeAbove)(JNIEnv *env, jclass type, jlong handle, jlong sibling) {
    (void)env; (void)type;
    RenderTarget *r = (RenderTarget *)(intptr_t)handle;
    if (r && sibling) wl_subsurface_place_above(r->subsurface, (struct wl_surface *)(intptr_t)sibling);
}
static void *get_proc(void *context, const char *name) { (void)context; return (void *)eglGetProcAddress(name); }
JNIEXPORT jlong JNICALL JNI_METHOD(getProcAddress)(JNIEnv *env, jclass type) {
    (void)env; (void)type; return (jlong)(intptr_t)get_proc;
}
JNIEXPORT jstring JNICALL JNI_METHOD(renderer)(JNIEnv *env, jclass type, jlong handle) {
    (void)type; (void)handle;
    typedef const unsigned char *(*get_string)(unsigned int);
    get_string query = (get_string)eglGetProcAddress("glGetString");
    const unsigned char *name = query ? query(0x1F01) : NULL;
    return (*env)->NewStringUTF(env, name ? (const char *)name : "OpenGL through EGL");
}
JNIEXPORT jint JNICALL JNI_METHOD(connectionCount)(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int count = 0;
    for (WaylandConnection *c = connections; c; c = c->next) count++;
    return count;
}
