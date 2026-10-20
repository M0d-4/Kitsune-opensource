#include <sys/wait.h>
#include <sys/mount.h>

#include <core.hpp>

#include "deny.hpp"

using namespace std;

[[noreturn]] static void usage() {
    fprintf(stderr,
R"EOF(DenyList Config CLI

Usage: magisk --denylist [action [arguments...] ]
Actions:
   status          Return the enforcement status
   enable          Enable denylist enforcement
   disable         Disable denylist enforcement
   add PKG [PROC]  Add a new target to the denylist
   rm PKG [PROC]   Remove target(s) from the denylist
   ls              Print the current denylist
   exec CMDs...    Execute commands in isolated mount
                   namespace and do all unmounts
   sulist [ACTION [ARGS...]]
                   SuList (only listed apps can see/use root). ACTION is one
                   of: status (default), enable, disable, add PKG [PROC],
                   rm PKG [PROC], ls

)EOF");
    exit(1);
}

void denylist_handler(int client) {
    if (client < 0) {
        revert_unmount();
        return;
    }

    int req = read_int(client);
    int res = DenyResponse::ERROR;

    switch (req) {
    case DenyRequest::ENFORCE:
        res = enable_deny();
        break;
    case DenyRequest::DISABLE:
        res = disable_deny();
        break;
    case DenyRequest::ADD:
        res = add_list(client);
        break;
    case DenyRequest::REMOVE:
        res = rm_list(client);
        break;
    case DenyRequest::LIST:
        ls_list(client);
        return;
    case DenyRequest::STATUS:
        res = denylist_enforced ? DenyResponse::ENFORCED : DenyResponse::NOT_ENFORCED;
        break;
    case DenyRequest::ENFORCE_SULIST:
        res = enable_sulist();
        break;
    case DenyRequest::DISABLE_SULIST:
        res = disable_sulist();
        break;
    case DenyRequest::ADD_SULIST:
        res = add_list(client, true);
        break;
    case DenyRequest::REMOVE_SULIST:
        res = rm_list(client, true);
        break;
    case DenyRequest::LIST_SULIST:
        ls_list(client, true);
        return;
    case DenyRequest::STATUS_SULIST:
        res = sulist_enforced() ? DenyResponse::SULIST_ENFORCED : DenyResponse::SULIST_NOT_ENFORCED;
        break;
    default:
        // Unknown request code
        break;
    }
    write_int(client, res);
    close(client);
}

int denylist_cli(rust::Vec<rust::String> &args) {
    if (args.empty())
        usage();

    // Convert rust strings into c strings
    size_t argc = args.size();
    std::vector<const char *> argv;
    ranges::transform(args, std::back_inserter(argv), [](rust::String &arg) { return arg.c_str(); });
    // End with nullptr
    argv.push_back(nullptr);

    int req;
    size_t base = 0;  // index of the first action argument
    if (argv[0] == "sulist"sv) {
        base = 1;
        const char *act = argv[1] ? argv[1] : "status";
        if (act == "status"sv)
            req = DenyRequest::STATUS_SULIST;
        else if (act == "enable"sv)
            req = DenyRequest::ENFORCE_SULIST;
        else if (act == "disable"sv)
            req = DenyRequest::DISABLE_SULIST;
        else if (act == "add"sv && argv[2])
            req = DenyRequest::ADD_SULIST;
        else if (act == "rm"sv && argv[2])
            req = DenyRequest::REMOVE_SULIST;
        else if (act == "ls"sv)
            req = DenyRequest::LIST_SULIST;
        else
            usage();
    } else if (argv[0] == "enable"sv)
        req = DenyRequest::ENFORCE;
    else if (argv[0] == "disable"sv)
        req = DenyRequest::DISABLE;
    else if (argv[0] == "add"sv)
        req = DenyRequest::ADD;
    else if (argv[0] == "rm"sv)
        req = DenyRequest::REMOVE;
    else if (argv[0] == "ls"sv)
        req = DenyRequest::LIST;
    else if (argv[0] == "status"sv)
        req = DenyRequest::STATUS;
    else if (argv[0] == "exec"sv && argc > 1) {
        xunshare(CLONE_NEWNS);
        xmount(nullptr, "/", nullptr, MS_PRIVATE | MS_REC, nullptr);
        revert_unmount();
        execvp(argv[1], (char **) argv.data() + 1);
        exit(1);
    } else {
        usage();
    }

    // Send request
    owned_fd fd = connect_daemon(RequestCode::DENYLIST);
    if (fd < 0) exit(1);

    write_int(fd, req);
    if (req == DenyRequest::ADD || req == DenyRequest::REMOVE ||
        req == DenyRequest::ADD_SULIST || req == DenyRequest::REMOVE_SULIST) {
        write_string(fd, argv[base + 1]);
        write_string(fd, argv[base + 2] ? argv[base + 2] : "");
    }

    // Get response
    int res = read_int(fd);
    if (res < 0 || res >= DenyResponse::END)
        res = DenyResponse::ERROR;
    switch (res) {
    case DenyResponse::NOT_ENFORCED:
        fprintf(stderr, "Denylist is not enforced\n");
        goto return_code;
    case DenyResponse::ENFORCED:
        fprintf(stderr, "Denylist is enforced\n");
        goto return_code;
    case DenyResponse::ITEM_EXIST:
        fprintf(stderr, "Target already exists in denylist\n");
        goto return_code;
    case DenyResponse::ITEM_NOT_EXIST:
        fprintf(stderr, "Target does not exist in denylist\n");
        goto return_code;
    case DenyResponse::NO_NS:
        fprintf(stderr, "The kernel does not support mount namespace\n");
        goto return_code;
    case DenyResponse::INVALID_PKG:
        fprintf(stderr, "Invalid package / process name\n");
        goto return_code;
    case DenyResponse::SULIST_ENFORCED:
        fprintf(stderr, "SuList is enforced\n");
        goto return_code;
    case DenyResponse::SULIST_NOT_ENFORCED:
        fprintf(stderr, "SuList is not enforced\n");
        goto return_code;
    case DenyResponse::SULIST_NO_DISABLE:
        fprintf(stderr, "Cannot disable while SuList is enforced\n");
        goto return_code;
    case DenyResponse::ERROR:
        fprintf(stderr, "deny: Daemon error\n");
        return -1;
    case DenyResponse::OK:
        break;
    default:
        __builtin_unreachable();
    }

    if (req == DenyRequest::LIST || req == DenyRequest::LIST_SULIST) {
        string out;
        for (;;) {
            read_string(fd, out);
            if (out.empty())
                break;
            printf("%s\n", out.data());
        }
    }

return_code:
    if (req == DenyRequest::STATUS)
        return res != DenyResponse::ENFORCED;
    if (req == DenyRequest::STATUS_SULIST)
        return res != DenyResponse::SULIST_ENFORCED;
    return res != DenyResponse::OK;
}
