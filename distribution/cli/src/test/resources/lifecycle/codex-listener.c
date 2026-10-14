#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 4 || strcmp(argv[argc - 2], "--listen") != 0) return 2;
    const char *transport = argv[argc - 1];
    if (strncmp(transport, "unix://", 7) != 0) return 3;
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    if (strlen(transport + 7) >= sizeof(address.sun_path)) return 4;
    strcpy(address.sun_path, transport + 7);
    int listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0) return 5;
    if (listen(listener, 1) != 0) return 6;
    puts("ready");
    fflush(stdout);
    int client = accept(listener, NULL, NULL);
    if (client < 0) return 7;
    puts("connected");
    fflush(stdout);
    char byte;
    while (read(client, &byte, 1) > 0) {}
    close(client);
    close(listener);
    unlink(address.sun_path);
    return 0;
}
