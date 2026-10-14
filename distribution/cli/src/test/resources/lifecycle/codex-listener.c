#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/select.h>
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
    fd_set clients;
    FD_ZERO(&clients);
    FD_SET(listener, &clients);
    int maximum = listener;
    for (;;) {
        fd_set ready = clients;
        if (select(maximum + 1, &ready, NULL, NULL, NULL) < 0) return 7;
        if (FD_ISSET(listener, &ready)) {
            int client = accept(listener, NULL, NULL);
            if (client < 0 || client >= FD_SETSIZE) return 8;
            FD_SET(client, &clients);
            if (client > maximum) maximum = client;
            puts("connected");
            fflush(stdout);
        }
        for (int client = 0; client <= maximum; client++) {
            if (client == listener || !FD_ISSET(client, &ready)) continue;
            char byte;
            if (read(client, &byte, 1) <= 0) {
                close(client);
                FD_CLR(client, &clients);
            }
        }
    }
}
