#include <sys/types.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <set>
#include <map>

#include <consts.hpp>
#include <sqlite.hpp>
#include <core.hpp>

#include "deny.hpp"

using namespace std;

// For the following data structures:
// If package name == ISOLATED_MAGIC, or app ID == -1, it means isolated service
// If package name == WEBVIEW_ZYGOTE_MAGIC, or app ID == 1053, it means webview zygote

// Both the DenyList and the SuList use the same data layout, only the backing
// database table differs.
struct ListData {
    const char *table;
    // Package name -> list of process names
    unique_ptr<map<string, set<string, StringCmp>, StringCmp>> pkg_to_procs_;
    // app ID -> list of pkg names (string_view points to a pkg_to_procs_ key)
    unique_ptr<map<int, set<string_view>>> app_id_to_pkgs_;
};

static ListData deny_data{ .table = "denylist" };
static ListData su_data{ .table = "sulist" };

#define pkg_to_procs (*ld.pkg_to_procs_)
#define app_id_to_pkgs (*ld.app_id_to_pkgs_)

// Locks the data structures above
static pthread_mutex_t data_lock = PTHREAD_MUTEX_INITIALIZER;

atomic<bool> denylist_enforced = false;
atomic<bool> sulist_enforced_ = false;

static int get_app_id(const vector<int> &users, const string &pkg) {
    struct stat st{};
    char buf[PATH_MAX];
    for (const auto &user_id: users) {
        ssprintf(buf, sizeof(buf), "%s/%d/%s", APP_DATA_DIR, user_id, pkg.data());
        if (stat(buf, &st) == 0) {
            return to_app_id(st.st_uid);
        }
    }
    return 0;
}

static void collect_users(vector<int> &users) {
    auto data_dir = xopen_dir(APP_DATA_DIR);
    if (!data_dir)
        return;
    dirent *entry;
    while ((entry = xreaddir(data_dir.get()))) {
        users.emplace_back(parse_int(entry->d_name));
    }
}

static int get_app_id(const string &pkg) {
    if (pkg == ISOLATED_MAGIC)
        return -1;
    if (pkg == WEBVIEW_ZYGOTE_MAGIC)
        return WEBVIEW_ZYGOTE_UID;
    vector<int> users;
    collect_users(users);
    return get_app_id(users, pkg);
}

static void update_app_id(ListData &ld, int app_id, const string &pkg, bool remove) {
    if (app_id <= 0)
        return;
    if (remove) {
        if (auto it = app_id_to_pkgs.find(app_id); it != app_id_to_pkgs.end()) {
            it->second.erase(pkg);
            if (it->second.empty()) {
                app_id_to_pkgs.erase(it);
            }
        }
    } else {
        app_id_to_pkgs[app_id].emplace(pkg);
    }
}

// Leave /proc fd opened as we're going to read from it repeatedly
static DIR *procfp;

template<class F>
static void crawl_procfs(const F &fn) {
    rewinddir(procfp);
    dirent *dp;
    int pid;
    while ((dp = readdir(procfp))) {
        pid = parse_int(dp->d_name);
        if (pid > 0 && !fn(pid))
            break;
    }
}

static bool str_eql(string_view a, string_view b) { return a == b; }
static bool str_starts_with(string_view a, string_view b) { return a.starts_with(b); }

template<bool str_op(string_view, string_view) = str_eql>
static bool proc_name_match(int pid, string_view name) {
    char buf[4019];
    sprintf(buf, "/proc/%d/cmdline", pid);
    if (auto fp = open_file(buf, "re")) {
        fgets(buf, sizeof(buf), fp.get());
        if (str_op(buf, name)) {
            return true;
        }
    }
    return false;
}

bool proc_context_match(int pid, string_view context) {
    char buf[PATH_MAX];
    char con[1024] = {0};

    sprintf(buf, "/proc/%d", pid);
    if (lgetfilecon(buf, byte_data{ con, sizeof(con) })) {
        return string_view(con).starts_with(context);
    }
    return false;
}

template<bool matcher(int, string_view) = &proc_name_match>
static void kill_process(const char *name, bool multi = false) {
    crawl_procfs([=](int pid) -> bool {
        if (matcher(pid, name)) {
            kill(pid, SIGKILL);
            LOGD("denylist: kill PID=[%d] (%s)\n", pid, name);
            return multi;
        }
        return true;
    });
}

static bool validate(const char *pkg, const char *proc) {
    bool pkg_valid = false;
    bool proc_valid = true;

    if (str_eql(pkg, ISOLATED_MAGIC)) {
        pkg_valid = true;
        for (char c; (c = *proc); ++proc) {
            if (isalnum(c) || c == '_' || c == '.')
                continue;
            if (c == ':')
                break;
            proc_valid = false;
            break;
        }
    } else if (str_eql(pkg, WEBVIEW_ZYGOTE_MAGIC)) {
        pkg_valid = true;
        proc_valid = str_eql(proc, WEBVIEW_ZYGOTE_MAGIC);
    } else {
        for (char c; (c = *pkg); ++pkg) {
            if (isalnum(c) || c == '_')
                continue;
            if (c == '.') {
                pkg_valid = true;
                continue;
            }
            pkg_valid = false;
            break;
        }

        for (char c; (c = *proc); ++proc) {
            if (isalnum(c) || c == '_' || c == ':' || c == '.')
                continue;
            proc_valid = false;
            break;
        }
    }
    return pkg_valid && proc_valid;
}

static bool add_hide_set(ListData &ld, const char *pkg, const char *proc, bool kill = true) {
    auto p = pkg_to_procs[pkg].emplace(proc);
    if (!p.second)
        return false;
    LOGI("%s add: [%s/%s]\n", ld.table, pkg, proc);
    if (!denylist_enforced || !kill)
        return true;
    if (&ld == &su_data && !sulist_enforced_)
        return true;
    if (str_eql(pkg, ISOLATED_MAGIC)) {
        // Kill all matching isolated processes
        kill_process<&proc_name_match<str_starts_with>>(proc, true);
    } else {
        kill_process(proc);
    }
    return true;
}

static void scan_list_apps(ListData &ld) {
    if (!ld.app_id_to_pkgs_)
        return;

    app_id_to_pkgs.clear();

    char sql[4096];
    vector<int> users;
    collect_users(users);
    for (auto it = pkg_to_procs.begin(); it != pkg_to_procs.end();) {
        if (it->first == ISOLATED_MAGIC) {
            it++;
            continue;
        }
        if (it->first == WEBVIEW_ZYGOTE_MAGIC) {
            update_app_id(ld, WEBVIEW_ZYGOTE_UID, it->first, false);
            it++;
            continue;
        }
        int app_id = get_app_id(users, it->first);
        if (app_id == 0) {
            LOGI("%s rm: [%s]\n", ld.table, it->first.data());
            ssprintf(sql, sizeof(sql), "DELETE FROM %s WHERE package_name='%s'",
                     ld.table, it->first.data());
            db_exec(sql);
            it = pkg_to_procs.erase(it);
        } else {
            update_app_id(ld, app_id, it->first, false);
            it++;
        }
    }
}

void scan_deny_apps() {
    scan_list_apps(deny_data);
    scan_list_apps(su_data);
}

static void clear_data(ListData &ld) {
    ld.pkg_to_procs_.reset(nullptr);
    ld.app_id_to_pkgs_.reset(nullptr);
}

static bool ensure_data(ListData &ld) {
    if (ld.pkg_to_procs_)
        return true;

    LOGI("%s: initializing internal data structures\n", ld.table);

    default_new(ld.pkg_to_procs_);
    char query[64];
    ssprintf(query, sizeof(query), "SELECT * FROM %s", ld.table);
    bool res = db_exec(query, {}, [&ld](StringSlice columns, const DbValues &values) {
        const char *package_name;
        const char *process;
        for (int i = 0; i < columns.size(); ++i) {
            const auto &name = columns[i];
            if (name == "package_name") {
                package_name = values.get_text(i);
            } else if (name == "process") {
                process = values.get_text(i);
            }
        }
        add_hide_set(ld, package_name, process, false);
    });
    if (!res)
        goto error;

    default_new(ld.app_id_to_pkgs_);
    scan_list_apps(ld);

    if (&ld == &su_data) {
        // Modules commonly need to modify SystemUI and Settings, so they are always listed
        add_hide_set(ld, "com.android.systemui", "com.android.systemui", false);
        add_hide_set(ld, "com.android.settings", "com.android.settings", false);
        scan_list_apps(ld);
    }

    return true;

error:
    clear_data(ld);
    return false;
}

static int add_list(ListData &ld, const char *pkg, const char *proc) {
    if (proc[0] == '\0')
        proc = pkg;

    if (!validate(pkg, proc))
        return DenyResponse::INVALID_PKG;

    {
        mutex_guard lock(data_lock);
        if (!ensure_data(ld))
            return DenyResponse::ERROR;
        int app_id = get_app_id(pkg);
        if (app_id == 0)
            return DenyResponse::INVALID_PKG;
        if (!add_hide_set(ld, pkg, proc))
            return DenyResponse::ITEM_EXIST;
        auto it = pkg_to_procs.find(pkg);
        update_app_id(ld, app_id, it->first, false);
    }

    // Add to database
    char sql[4096];
    ssprintf(sql, sizeof(sql),
            "INSERT INTO %s (package_name, process) VALUES('%s', '%s')", ld.table, pkg, proc);
    return db_exec(sql) ? DenyResponse::OK : DenyResponse::ERROR;
}

int add_list(int client, bool sulist) {
    string pkg = read_string(client);
    string proc = read_string(client);
    return add_list(sulist ? su_data : deny_data, pkg.data(), proc.data());
}

static int rm_list(ListData &ld, const char *pkg, const char *proc) {
    {
        mutex_guard lock(data_lock);
        if (!ensure_data(ld))
            return DenyResponse::ERROR;

        bool remove = false;

        auto it = pkg_to_procs.find(pkg);
        if (it != pkg_to_procs.end()) {
            if (proc[0] == '\0') {
                update_app_id(ld, get_app_id(pkg), it->first, true);
                pkg_to_procs.erase(it);
                remove = true;
                LOGI("%s rm: [%s]\n", ld.table, pkg);
            } else if (it->second.erase(proc) != 0) {
                remove = true;
                LOGI("%s rm: [%s/%s]\n", ld.table, pkg, proc);
                if (it->second.empty()) {
                    update_app_id(ld, get_app_id(pkg), it->first, true);
                    pkg_to_procs.erase(it);
                }
            }
        }

        if (!remove)
            return DenyResponse::ITEM_NOT_EXIST;
    }

    char sql[4096];
    if (proc[0] == '\0')
        ssprintf(sql, sizeof(sql), "DELETE FROM %s WHERE package_name='%s'", ld.table, pkg);
    else
        ssprintf(sql, sizeof(sql),
                "DELETE FROM %s WHERE package_name='%s' AND process='%s'", ld.table, pkg, proc);
    return db_exec(sql) ? DenyResponse::OK : DenyResponse::ERROR;
}

int rm_list(int client, bool sulist) {
    string pkg = read_string(client);
    string proc = read_string(client);
    return rm_list(sulist ? su_data : deny_data, pkg.data(), proc.data());
}

void ls_list(int client, bool sulist) {
    ListData &ld = sulist ? su_data : deny_data;
    {
        mutex_guard lock(data_lock);
        if (!ensure_data(ld)) {
            write_int(client, static_cast<int>(DenyResponse::ERROR));
            return;
        }

        scan_list_apps(ld);
        write_int(client,static_cast<int>(DenyResponse::OK));

        for (const auto &[pkg, procs] : pkg_to_procs) {
            for (const auto &proc : procs) {
                write_int(client, pkg.size() + proc.size() + 1);
                xwrite(client, pkg.data(), pkg.size());
                xwrite(client, "|", 1);
                xwrite(client, proc.data(), proc.size());
            }
        }
    }
    write_int(client, 0);
    close(client);
}

int enable_deny() {
    if (denylist_enforced) {
        return DenyResponse::OK;
    } else {
        mutex_guard lock(data_lock);

        if (access("/proc/self/ns/mnt", F_OK) != 0) {
            LOGW("The kernel does not support mount namespace\n");
            return DenyResponse::NO_NS;
        }

        if (procfp == nullptr && (procfp = opendir("/proc")) == nullptr)
            return DenyResponse::ERROR;

        LOGI("* Enable DenyList\n");

        if (!ensure_data(deny_data))
            return DenyResponse::ERROR;

        denylist_enforced = true;

        if (!MagiskD::Get().zygisk_enabled()) {
            if (new_daemon_thread(&logcat)) {
                denylist_enforced = false;
                return DenyResponse::ERROR;
            }
        }

        // On Android Q+, also kill blastula pool and all app zygotes
        if (SDK_INT >= 29) {
            kill_process("usap32", true);
            kill_process("usap64", true);
            kill_process<&proc_context_match>("u:r:app_zygote:s0", true);
        }
    }

    MagiskD::Get().set_db_setting(DbEntryKey::DenylistConfig, true);
    return DenyResponse::OK;
}

int disable_deny() {
    if (sulist_enforced_)
        return DenyResponse::SULIST_NO_DISABLE;
    if (denylist_enforced.exchange(false)) {
        LOGI("* Disable DenyList\n");
    }
    MagiskD::Get().set_db_setting(DbEntryKey::DenylistConfig, false);
    return DenyResponse::OK;
}

int enable_sulist() {
    // SuList builds on the denylist unmount machinery, so make sure it is running
    int res = enable_deny();
    if (res != DenyResponse::OK)
        return res;
    {
        mutex_guard lock(data_lock);
        if (!ensure_data(su_data))
            return DenyResponse::ERROR;
    }
    if (!sulist_enforced_.exchange(true)) {
        LOGI("* Enable SuList\n");
        // Restart app zygotes / usap pool so new processes follow the new rules
        if (SDK_INT >= 29) {
            kill_process("usap32", true);
            kill_process("usap64", true);
            kill_process<&proc_context_match>("u:r:app_zygote:s0", true);
        }
    }
    MagiskD::Get().set_db_setting(DbEntryKey::SulistConfig, true);
    return DenyResponse::OK;
}

int disable_sulist() {
    if (sulist_enforced_.exchange(false)) {
        LOGI("* Disable SuList\n");
    }
    MagiskD::Get().set_db_setting(DbEntryKey::SulistConfig, false);
    return DenyResponse::OK;
}

bool sulist_enforced() {
    return sulist_enforced_;
}

void initialize_denylist() {
    if (!denylist_enforced) {
        if (MagiskD::Get().get_db_setting(DbEntryKey::DenylistConfig))
            enable_deny();
    }
    if (denylist_enforced && !sulist_enforced_) {
        if (MagiskD::Get().get_db_setting(DbEntryKey::SulistConfig) > 0)
            enable_sulist();
    }
}

static bool is_list_target(ListData &ld, int uid, string_view process) {
    if (!ensure_data(ld))
        return false;

    int app_id = to_app_id(uid);
    if (app_id >= 90000) {
        if (auto it = pkg_to_procs.find(ISOLATED_MAGIC); it != pkg_to_procs.end()) {
            for (const auto &s : it->second) {
                if (process.starts_with(s))
                    return true;
            }
        }
        return false;
    } else {
        auto it = app_id_to_pkgs.find(app_id);
        if (it == app_id_to_pkgs.end())
            return false;
        for (const auto &pkg : it->second) {
            if (pkg_to_procs.find(pkg)->second.count(process))
                return true;
        }
    }
    return false;
}

bool is_deny_target(int uid, string_view process) {
    mutex_guard lock(data_lock);
    return is_list_target(deny_data, uid, process);
}

bool is_sulist_target(int uid) {
    mutex_guard lock(data_lock);
    if (!ensure_data(su_data))
        return false;
    ListData &ld = su_data;
    return app_id_to_pkgs.contains(to_app_id(uid));
}

void update_deny_flags(int uid, rust::Str process, uint32_t &flags) {
    std::string_view proc{ process.begin(), process.end() };
    if (sulist_enforced_) {
        // SuList mode: Magisk is hidden from every app process that is NOT on the SuList
        mutex_guard lock(data_lock);
        if (!is_list_target(su_data, uid, proc))
            flags |= +ZygiskStateFlags::ProcessOnDenyList;
    } else if (is_deny_target(uid, proc)) {
        flags |= +ZygiskStateFlags::ProcessOnDenyList;
    }
    if (denylist_enforced) {
        flags |= +ZygiskStateFlags::DenyListEnforced;
    }
}
