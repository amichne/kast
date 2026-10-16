#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    if (strlen(argv[1]) >= sizeof(address.sun_path)) return 3;
    strcpy(address.sun_path, argv[1]);
    int listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0) return 4;
    if (listen(listener, 1) != 0) return 5;
    puts("ready");
    fflush(stdout);
    if (getchar() == EOF) return 6;
    int client = accept(listener, NULL, NULL);
    if (client < 0) return 7;
    puts("accepted");
    fflush(stdout);
    getchar();
    close(client);
    close(listener);
    return 0;
}
