/*
 * mb_info —— 设备与 Root 环境信息（供 App 展示/排障）
 * 输出格式：ENV|key|value
 * 镜像备份 提取辅助模块 v2.1 (C) Kazaf
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/utsname.h>

static void prop(const char *name, const char *fallback) {
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "getprop %s", name);
    FILE *f = popen(cmd, "r");
    char buf[512] = {0};
    if (f) {
        if (fgets(buf, sizeof(buf) - 1, f)) {
            size_t n = strlen(buf);
            while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = '\0';
        }
        pclose(f);
    }
    printf("ENV|%s|%s\n", name, buf[0] ? buf : (fallback ? fallback : ""));
}

int main(void) {
    prop("ro.product.model", "unknown");
    prop("ro.product.manufacturer", "unknown");
    prop("ro.product.device", "unknown");
    prop("ro.build.version.release", "?");
    prop("ro.build.version.sdk", "?");
    prop("ro.build.fingerprint", "?");
    prop("ro.build.version.security_patch", "?");
    prop("ro.product.cpu.abilist", "?");
    prop("ro.boot.slot_suffix", "none");

    struct utsname u;
    if (uname(&u) == 0) {
        printf("ENV|kernel|%s\n", u.release);
        printf("ENV|arch|%s\n", u.machine);
    }
    printf("ENV|uid|%d\n", (int)getuid());
    printf("ENV|cpu_cores|%ld\n", sysconf(_SC_NPROCESSORS_ONLN));
    return 0;
}
