/*
 * mb_verify —— 单文件 SHA-256 校验助手
 * 用法：mb_verify <file> [expected_sha256]
 * 输出：SHA256=<hex>  （有期望值时追加 MATCH/NO_MATCH）
 * 退出码：0 匹配/无期望；2 不匹配；1 读取失败
 * 镜像备份 提取辅助模块 v2.1 (C) Kazaf
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

typedef struct { uint32_t h[8]; uint64_t len; unsigned char buf[64]; size_t bufLen; } sha256_ctx;

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
#define ROTR(x,n) (((x) >> (n)) | ((x) << (32-(n))))

static void s_init(sha256_ctx *c) {
    c->h[0]=0x6a09e667; c->h[1]=0xbb67ae85; c->h[2]=0x3c6ef372; c->h[3]=0xa54ff53a;
    c->h[4]=0x510e527f; c->h[5]=0x9b05688c; c->h[6]=0x1f83d9ab; c->h[7]=0x5be0cd19;
    c->len=0; c->bufLen=0;
}
static void s_block(sha256_ctx *c, const unsigned char *p) {
    uint32_t w[64];
    for (int i=0;i<16;i++) w[i]=((uint32_t)p[i*4]<<24)|((uint32_t)p[i*4+1]<<16)|((uint32_t)p[i*4+2]<<8)|p[i*4+3];
    for (int i=16;i<64;i++) {
        uint32_t s0=ROTR(w[i-15],7)^ROTR(w[i-15],18)^(w[i-15]>>3);
        uint32_t s1=ROTR(w[i-2],17)^ROTR(w[i-2],19)^(w[i-2]>>10);
        w[i]=w[i-16]+s0+w[i-7]+s1;
    }
    uint32_t a=c->h[0],b=c->h[1],cc=c->h[2],d=c->h[3],e=c->h[4],f=c->h[5],g=c->h[6],h=c->h[7];
    for (int i=0;i<64;i++) {
        uint32_t S1=ROTR(e,6)^ROTR(e,11)^ROTR(e,25), ch=(e&f)^(~e&g);
        uint32_t t1=h+S1+ch+K[i]+w[i];
        uint32_t S0=ROTR(a,2)^ROTR(a,13)^ROTR(a,22), mj=(a&b)^(a&cc)^(b&cc);
        uint32_t t2=S0+mj;
        h=g;g=f;f=e;e=d+t1;d=cc;cc=b;b=a;a=t1+t2;
    }
    c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;c->h[5]+=f;c->h[6]+=g;c->h[7]+=h;
}
static void s_update(sha256_ctx *c, const unsigned char *d, size_t n) {
    c->len += n;
    while (n > 0) {
        size_t take = 64 - c->bufLen;
        if (take > n) take = n;
        memcpy(c->buf + c->bufLen, d, take);
        c->bufLen += take; d += take; n -= take;
        if (c->bufLen == 64) { s_block(c, c->buf); c->bufLen = 0; }
    }
}
static void s_final(sha256_ctx *c, unsigned char out[32]) {
    uint64_t bits = c->len * 8;
    unsigned char p = 0x80, z = 0;
    s_update(c, &p, 1);
    while (c->bufLen != 56) s_update(c, &z, 1);
    unsigned char lb[8];
    for (int i=0;i<8;i++) lb[i]=(unsigned char)(bits>>(56-i*8));
    s_update(c, lb, 8);
    for (int i=0;i<8;i++){ out[i*4]=(unsigned char)(c->h[i]>>24); out[i*4+1]=(unsigned char)(c->h[i]>>16); out[i*4+2]=(unsigned char)(c->h[i]>>8); out[i*4+3]=(unsigned char)c->h[i]; }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: mb_verify <file> [expected_sha256]\n");
        return 1;
    }
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) { perror("mb_verify"); return 1; }
    unsigned char *buf = malloc(1 << 20);
    if (!buf) return 1;
    sha256_ctx sha; s_init(&sha);
    for (;;) {
        ssize_t r = read(fd, buf, 1 << 20);
        if (r < 0) { fprintf(stderr, "read error: %s\n", strerror(errno)); close(fd); free(buf); return 1; }
        if (r == 0) break;
        s_update(&sha, buf, (size_t)r);
    }
    close(fd); free(buf);
    unsigned char d[32]; s_final(&sha, d);
    char hex[65];
    for (int i=0;i<32;i++) sprintf(hex + i*2, "%02x", d[i]);
    hex[64] = 0;
    printf("SHA256=%s\n", hex);
    if (argc >= 3) {
        int match = strcasecmp(hex, argv[2]) == 0;
        printf("%s\n", match ? "MATCH" : "NO_MATCH");
        return match ? 0 : 2;
    }
    return 0;
}
