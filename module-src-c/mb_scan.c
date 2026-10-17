/*
 * mb_scan —— 设备接口扫描器（辅助不同机型的分区/路径差异）
 * 输出：
 *   PART|名字|解析后路径|字节数
 *   ENV|key|value    (cpu_cores / page_size / super / ab_slot / by_name_dir / mapper)
 * 兼容：by-name 移位的机型、device-mapper 双层链接、仅 /proc/partitions 可见的机型
 * 镜像备份 提取辅助模块 v2.0 (C) Kazaf
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>

static long long read_size_file(const char *path) {
    char buf[64] = {0};
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    (void)r;
    close(fd);
    long long sectors = atoll(buf);
    return sectors > 0 ? sectors * 512LL : -1;
}

static long long node_size(const char *real) {
    const char *base = strrchr(real, '/');
    base = base ? base + 1 : real;
    char sys[PATH_MAX];
    snprintf(sys, sizeof(sys), "/sys/class/block/%s/size", base);
    long long sz = read_size_file(sys);
    if (sz > 0) return sz;
    struct stat st;
    if (stat(real, &st) == 0 && S_ISBLK(st.st_mode)) {
        snprintf(sys, sizeof(sys), "/sys/dev/block/%u:%u/size", major(st.st_dev), minor(st.st_dev));
        sz = read_size_file(sys);
        if (sz > 0) return sz;
    }
    return -1;
}

/* 扫描一个 by-name 目录，输出 name|realpath|bytes；返回条目数 */
static int scan_by_name(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return 0;
    struct dirent *e;
    int n = 0;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char p[PATH_MAX], rp[PATH_MAX];
        snprintf(p, sizeof(p), "%s/%s", dir, e->d_name);
        if (realpath(p, rp) == NULL) snprintf(rp, sizeof(rp), "%s", p);
        long long sz = node_size(rp);
        printf("PART|%s|%s|%lld\n", e->d_name, rp, sz);
        n++;
    }
    closedir(d);
    return n;
}

int main(void) {
    /* --- 环境信息 --- */
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    printf("ENV|cpu_cores|%ld\n", cores);
    printf("ENV|page_size|%ld\n", sysconf(_SC_PAGESIZE));

    /* A/B 槽位 */
    char slot[64] = {0};
    FILE *f = fopen("/proc/cmdline", "r");
    if (f) {
        char line[2048] = {0};
        if (fgets(line, sizeof(line), f)) {
            char *p = strstr(line, "androidboot.slot_suffix=");
            if (p) {
                p += strlen("androidboot.slot_suffix=");
                int i = 0;
                while (p[i] && p[i] != ' ' && i < 63) { slot[i] = p[i]; i++; }
                slot[i] = '\0';
            }
        }
        fclose(f);
    }
    printf("ENV|ab_slot|%s\n", slot[0] ? slot : "none");

    /* mapper 存在性 */
    struct stat st;
    printf("ENV|mapper|%d\n", stat("/dev/block/mapper", &st) == 0);

    /* --- 分区枚举：多路径兼容 --- */
    const char *dirs[] = {
        "/dev/block/by-name",
        "/dev/block/bootdevice/by-name",
        NULL
    };
    int total = 0;
    const char *used_dir = NULL;
    for (int i = 0; dirs[i]; i++) {
        int n = scan_by_name(dirs[i]);
        if (n > 0 && !used_dir) {
            used_dir = dirs[i];
            total += n;
        }
    }
    /* 通配 platform/*\/by-name（无 shell，用 opendir 遍历） */
    if (total == 0) {
        DIR *pd = opendir("/dev/block/platform");
        if (pd) {
            struct dirent *pe;
            while ((pe = readdir(pd))) {
                if (pe->d_name[0] == '.') continue;
                char dir[PATH_MAX];
                snprintf(dir, sizeof(dir), "/dev/block/platform/%s/by-name", pe->d_name);
                int n = scan_by_name(dir);
                if (n > 0 && !used_dir) {
                    used_dir = dir;
                    total += n;
                }
            }
            closedir(pd);
        }
    }
    printf("ENV|by_name_dir|%s\n", used_dir ? used_dir : "none");

    /* super / 引导分区存在性 */
    char probe[PATH_MAX];
    const char *probe_dir = used_dir ? used_dir : "/dev/block/by-name";
    snprintf(probe, sizeof(probe), "%s/super", probe_dir);
    printf("ENV|super|%d\n", stat(probe, &st) == 0);
    snprintf(probe, sizeof(probe), "%s/boot", probe_dir);
    printf("ENV|boot|%d\n", stat(probe, &st) == 0);

    /* /proc/partitions 交叉核对：统计可见块设备数（用于发现未映射设备） */
    int proc_parts = 0;
    f = fopen("/proc/partitions", "r");
    if (f) {
        char line[512];
        while (fgets(line, sizeof(line), f)) {
            long long blocks = 0;
            char name[128];
            unsigned maj, min;
            if (sscanf(line, " %u %u %lld %127s", &maj, &min, &blocks, name) == 4) {
                proc_parts++;
            }
        }
        fclose(f);
    }
    printf("ENV|proc_partitions|%d\n", proc_parts);
    printf("ENV|count|%d\n", total);
    return total > 0 ? 0 : 1;
}
