/*
 * mb_parts —— 极速枚举 /dev/block/by-name 分区
 * 输出格式：名称|真实路径|扇区数(512B)
 * 镜像备份 提取辅助模块 v1.1.0 (C) Kazaf
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>

static void emit(const char *name, const char *path) {
    char rp[PATH_MAX];
    /* 完整规范化（by-name → mapper → dm-N 多层符号链接） */
    if (realpath(path, rp) == NULL) {
        snprintf(rp, sizeof(rp), "%s", path);
    }
    const char *base = strrchr(rp, '/');
    base = base ? base + 1 : rp;

    char sys[PATH_MAX];
    char szbuf[64] = {0};
    int fd;

    snprintf(sys, sizeof(sys), "/sys/class/block/%s/size", base);
    fd = open(sys, O_RDONLY);
    if (fd >= 0) {
        ssize_t r = read(fd, szbuf, sizeof(szbuf) - 1);
        (void)r;
        close(fd);
    }

    if (szbuf[0] == '\0') {
        struct stat st;
        if (stat(rp, &st) == 0 && S_ISBLK(st.st_mode)) {
            snprintf(sys, sizeof(sys), "/sys/dev/block/%u:%u/size",
                     major(st.st_dev), minor(st.st_dev));
            fd = open(sys, O_RDONLY);
            if (fd >= 0) {
                ssize_t r = read(fd, szbuf, sizeof(szbuf) - 1);
                (void)r;
                close(fd);
            }
        }
    }

    long long sectors = atoll(szbuf);
    if (sectors > 0) {
        printf("%s|%s|%lld\n", name, rp, sectors);
    } else {
        printf("%s|%s|0\n", name, rp);
    }
}

int main(void) {
    const char *dirs[] = {
        "/dev/block/by-name",
        "/dev/block/platform/*/by-name",
        "/dev/block/bootdevice/by-name",
        NULL
    };
    for (int i = 0; dirs[i]; i++) {
        DIR *d = opendir(dirs[i]);
        if (!d) continue;
        struct dirent *e;
        int count = 0;
        while ((e = readdir(d))) {
            if (e->d_name[0] == '.') continue;
            char p[PATH_MAX];
            snprintf(p, sizeof(p), "%s/%s", dirs[i], e->d_name);
            emit(e->d_name, p);
            count++;
        }
        closedir(d);
        if (count > 0) return 0;
    }
    return 1;
}
