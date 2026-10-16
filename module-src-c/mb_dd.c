/*
 * mb_dd —— 单进程镜像提取器：dd + 内置 SHA-256（边读边算边写）
 * 用法：mb_dd if=<src> of=<dst> bs=<bytes> hash=<hashfile> [progress=<file>]
 * 退出码：0 成功；1 失败（stderr 输出原因）
 * 镜像备份 提取辅助模块 v1.1.0 (C) Kazaf
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>

/* ---------- SHA-256 (FIPS 180-4) ---------- */
typedef struct {
    uint32_t h[8];
    uint64_t len;
    unsigned char buf[64];
    size_t bufLen;
} sha256_ctx;

static const uint32_t K[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROTR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256_init(sha256_ctx *c) {
    c->h[0] = 0x6a09e667; c->h[1] = 0xbb67ae85; c->h[2] = 0x3c6ef372; c->h[3] = 0xa54ff53a;
    c->h[4] = 0x510e527f; c->h[5] = 0x9b05688c; c->h[6] = 0x1f83d9ab; c->h[7] = 0x5be0cd19;
    c->len = 0; c->bufLen = 0;
}

static void sha256_block(sha256_ctx *c, const unsigned char *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++)
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ROTR(w[i - 15], 7) ^ ROTR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = ROTR(w[i - 2], 17) ^ ROTR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3];
    uint32_t e = c->h[4], f = c->h[5], g = c->h[6], h = c->h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = ROTR(e, 6) ^ ROTR(e, 11) ^ ROTR(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + K[i] + w[i];
        uint32_t S0 = ROTR(a, 2) ^ ROTR(a, 13) ^ ROTR(a, 22);
        uint32_t mj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + mj;
        h = g; g = f; f = e; e = d + t1;
        d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}

static void sha256_update(sha256_ctx *c, const unsigned char *data, size_t n) {
    c->len += n;
    while (n > 0) {
        size_t take = 64 - c->bufLen;
        if (take > n) take = n;
        memcpy(c->buf + c->bufLen, data, take);
        c->bufLen += take;
        data += take;
        n -= take;
        if (c->bufLen == 64) {
            sha256_block(c, c->buf);
            c->bufLen = 0;
        }
    }
}

static void sha256_final(sha256_ctx *c, unsigned char out[32]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80;
    sha256_update(c, &pad, 1);
    unsigned char zero = 0;
    while (c->bufLen != 56) sha256_update(c, &zero, 1);
    unsigned char lenb[8];
    for (int i = 0; i < 8; i++) lenb[i] = (unsigned char)(bits >> (56 - i * 8));
    sha256_update(c, lenb, 8);
    for (int i = 0; i < 8; i++) {
        out[i * 4] = (unsigned char)(c->h[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(c->h[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(c->h[i] >> 8);
        out[i * 4 + 3] = (unsigned char)(c->h[i]);
    }
}

/* ---------- 进度输出 ---------- */
static void write_progress(const char *pf, long long done) {
    if (!pf) return;
    int fd = open(pf, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        char buf[32];
        int n = snprintf(buf, sizeof(buf), "%lld", done);
        ssize_t r = write(fd, buf, (size_t)n);
        (void)r;
        close(fd);
    }
}

int main(int argc, char **argv) {
    const char *in = NULL, *out = NULL, *hashf = NULL, *progf = NULL;
    size_t bs = 1024 * 1024;

    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "if=", 3) == 0) in = argv[i] + 3;
        else if (strncmp(argv[i], "of=", 3) == 0) out = argv[i] + 3;
        else if (strncmp(argv[i], "hash=", 5) == 0) hashf = argv[i] + 5;
        else if (strncmp(argv[i], "progress=", 9) == 0) progf = argv[i] + 9;
        else if (strncmp(argv[i], "bs=", 3) == 0) bs = (size_t)atoll(argv[i] + 3);
    }
    if (!in || !out) {
        fprintf(stderr, "usage: mb_dd if=<src> of=<dst> bs=<bytes> hash=<file> [progress=<file>]\n");
        return 1;
    }

    int fin = open(in, O_RDONLY);
    if (fin < 0) {
        fprintf(stderr, "mb_dd: cannot open %s: %s\n", in, strerror(errno));
        return 1;
    }
    int fout = open(out, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fout < 0) {
        fprintf(stderr, "mb_dd: cannot open %s: %s\n", out, strerror(errno));
        close(fin);
        return 1;
    }

    unsigned char *buf = malloc(bs);
    if (!buf) {
        fprintf(stderr, "mb_dd: out of memory\n");
        close(fin); close(fout);
        return 1;
    }

    sha256_ctx sha;
    sha256_init(&sha);
    long long total = 0;
    struct timespec last, now;
    clock_gettime(CLOCK_MONOTONIC, &last);

    for (;;) {
        ssize_t r = read(fin, buf, bs);
        if (r < 0) {
            fprintf(stderr, "mb_dd: read error: %s\n", strerror(errno));
            free(buf); close(fin); close(fout);
            return 1;
        }
        if (r == 0) break;
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(fout, buf + off, (size_t)(r - off));
            if (w < 0) {
                fprintf(stderr, "mb_dd: write error: %s\n", strerror(errno));
                free(buf); close(fin); close(fout);
                return 1;
            }
            off += w;
        }
        sha256_update(&sha, buf, (size_t)r);
        total += r;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (now.tv_sec - last.tv_sec >= 1) {
            write_progress(progf, total);
            last = now;
        }
    }
    write_progress(progf, total);
    fsync(fout);
    close(fout);
    close(fin);
    free(buf);

    unsigned char digest[32];
    sha256_final(&sha, digest);
    if (hashf) {
        int fh = open(hashf, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fh >= 0) {
            char hex[65];
            for (int i = 0; i < 32; i++) sprintf(hex + i * 2, "%02x", digest[i]);
            hex[64] = '\n';
            ssize_t w = write(fh, hex, 65);
            (void)w;
            close(fh);
        }
    }
    return 0;
}
