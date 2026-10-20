#pragma once

#include <string_view>

#define ISOLATED_MAGIC "isolated"
#define WEBVIEW_ZYGOTE_MAGIC "webview_zygote"
#define WEBVIEW_ZYGOTE_UID 1053

namespace DenyRequest {
enum : int {
    ENFORCE,
    DISABLE,
    ADD,
    REMOVE,
    LIST,
    STATUS,
    ENFORCE_SULIST,
    DISABLE_SULIST,
    ADD_SULIST,
    REMOVE_SULIST,
    LIST_SULIST,
    STATUS_SULIST,

    END
};
}

namespace DenyResponse {
enum : int {
    OK,
    ENFORCED,
    NOT_ENFORCED,
    ITEM_EXIST,
    ITEM_NOT_EXIST,
    INVALID_PKG,
    NO_NS,
    ERROR,
    SULIST_ENFORCED,
    SULIST_NOT_ENFORCED,
    SULIST_NO_DISABLE,

    END
};
}

// CLI entries
int enable_deny();
int disable_deny();
int add_list(int client, bool sulist = false);
int rm_list(int client, bool sulist = false);
void ls_list(int client, bool sulist = false);
int enable_sulist();
int disable_sulist();

bool proc_context_match(int pid, std::string_view context);
void *logcat(void *arg);
extern bool logcat_exit;
