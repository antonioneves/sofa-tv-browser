// Changes one byte in another process's memory: poke <pid> <hex address> <expected byte> <new byte>.
// Used by emu.sh to make WebView's adler32 skip its SSSE3 path on the software-emulated CPU (see README.md).
// It refuses when the byte there is neither the expected one nor already the new one.
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ptrace.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
    if (argc < 5) { fprintf(stderr, "poke pid hexaddr oldbyte newbyte\n"); return 2; }
    pid_t pid = atoi(argv[1]);
    unsigned long addr = strtoul(argv[2], 0, 16);
    unsigned old = strtoul(argv[3], 0, 16), new = strtoul(argv[4], 0, 16);
    if (ptrace(PTRACE_ATTACH, pid, 0, 0)) { perror("attach"); return 1; }
    waitpid(pid, 0, __WALL);
    errno = 0;
    long word = ptrace(PTRACE_PEEKTEXT, pid, (void *) addr, 0);
    if (errno) { perror("peek"); ptrace(PTRACE_DETACH, pid, 0, 0); return 1; }
    unsigned cur = word & 0xff;
    int rc = 0;
    if (cur == new) printf("already patched\n");
    else if (cur != old) { printf("unexpected byte %02x, not patched\n", cur); rc = 3; }
    else {
        word = (word & ~0xffL) | new;
        if (ptrace(PTRACE_POKETEXT, pid, (void *) addr, (void *) word)) { perror("poke"); rc = 1; }
        else printf("patched %02x->%02x at %lx\n", old, new, addr);
    }
    ptrace(PTRACE_DETACH, pid, 0, 0);
    return rc;
}
