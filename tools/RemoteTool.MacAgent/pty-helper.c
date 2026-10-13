#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <util.h>

static int master_fd;
static pid_t child_pid;

static int read_exact(int fd, void *buffer, size_t size) {
    unsigned char *bytes = buffer;
    while (size) {
        ssize_t count = read(fd, bytes, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        bytes += count;
        size -= (size_t)count;
    }
    return 1;
}

static int write_all(int fd, const void *buffer, size_t size) {
    const unsigned char *bytes = buffer;
    while (size) {
        ssize_t count = write(fd, bytes, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return 0;
        bytes += count;
        size -= (size_t)count;
    }
    return 1;
}

// stdin protocol: opcode 1 = raw input, opcode 2 = cols/rows (two little-endian uint16s).
// Each frame has a four-byte little-endian payload length.
static void *read_input(void *unused) {
    (void)unused;
    unsigned char header[5];
    while (read_exact(STDIN_FILENO, header, sizeof header)) {
        uint32_t length = (uint32_t)header[1] | ((uint32_t)header[2] << 8) |
                          ((uint32_t)header[3] << 16) | ((uint32_t)header[4] << 24);
        if (length > 65536 || (header[0] == 2 && length != 4)) break;
        unsigned char *data = malloc(length ? length : 1);
        if (!data || !read_exact(STDIN_FILENO, data, length)) { free(data); break; }
        if (header[0] == 1) {
            if (!write_all(master_fd, data, length)) { free(data); break; }
        } else if (header[0] == 2) {
            struct winsize size = {0};
            size.ws_col = (uint16_t)data[0] | ((uint16_t)data[1] << 8);
            size.ws_row = (uint16_t)data[2] | ((uint16_t)data[3] << 8);
            if (size.ws_col && size.ws_row) {
                ioctl(master_fd, TIOCSWINSZ, &size);
                kill(child_pid, SIGWINCH);
            }
        }
        free(data);
    }
    kill(-child_pid, SIGHUP);
    return NULL;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    struct winsize size = {.ws_row = 36, .ws_col = 120};
    child_pid = forkpty(&master_fd, NULL, NULL, &size);
    if (child_pid < 0) { perror("forkpty"); return 1; }
    if (child_pid == 0) {
        setenv("TERM", "xterm-256color", 1);
        const char *home = getenv("HOME");
        if (home) chdir(home);
        execl("/bin/zsh", "zsh", "-i", (char *)NULL);
        _exit(127);
    }
    pthread_t input_thread;
    if (pthread_create(&input_thread, NULL, read_input, NULL) != 0) {
        kill(-child_pid, SIGHUP);
        return 1;
    }
    pthread_detach(input_thread);
    unsigned char buffer[8192];
    for (;;) {
        ssize_t count = read(master_fd, buffer, sizeof buffer);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || !write_all(STDOUT_FILENO, buffer, (size_t)count)) break;
    }
    close(master_fd);
    kill(-child_pid, SIGHUP);
    waitpid(child_pid, NULL, 0);
    return 0;
}
