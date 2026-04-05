#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define SCHEMA_VERSION 1
#define DEFAULT_WAIT_TIMEOUT_MS 60000L
#define DEFAULT_REQUEST_TIMEOUT_MS 30000L
#define DEFAULT_MAX_RESULTS 500
#define DEFAULT_MAX_CONCURRENT 4

typedef struct {
    char *data;
    size_t len;
    size_t cap;
} StringBuilder;

typedef struct {
    char *descriptor_path;
    char *descriptor_json;
    char *workspace_root;
    char *backend_name;
    char *backend_version;
    char *socket_path;
    long pid;
    int pid_alive;
    int reachable;
    int ready;
    char *runtime_status_json;
    char *capabilities_json;
    char *error_code;
    char *error_message;
    char *error_details_json;
} Candidate;

typedef struct {
    Candidate *items;
    size_t count;
    size_t cap;
} CandidateList;

typedef struct {
    char **positionals;
    size_t positional_count;
    char **option_keys;
    char **option_values;
    size_t option_count;
} ParsedArgs;

static void die(const char *message) {
    fprintf(stderr, "%s\n", message);
    exit(1);
}

static void *xmalloc(size_t size) {
    void *ptr = malloc(size);
    if (ptr == NULL) {
        die("Out of memory");
    }
    return ptr;
}

static char *xstrdup(const char *value) {
    if (value == NULL) {
        return NULL;
    }
    size_t len = strlen(value);
    char *copy = xmalloc(len + 1);
    memcpy(copy, value, len + 1);
    return copy;
}

static char *xstrprintf(const char *format, ...) {
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    int needed = vsnprintf(NULL, 0, format, copy);
    va_end(copy);
    if (needed < 0) {
        va_end(args);
        die("Failed to format string");
    }
    char *buffer = xmalloc((size_t)needed + 1);
    vsnprintf(buffer, (size_t)needed + 1, format, args);
    va_end(args);
    return buffer;
}

static void sb_init(StringBuilder *builder) {
    builder->cap = 1024;
    builder->len = 0;
    builder->data = xmalloc(builder->cap);
    builder->data[0] = '\0';
}

static void sb_reserve(StringBuilder *builder, size_t extra) {
    size_t needed = builder->len + extra + 1;
    if (needed <= builder->cap) {
        return;
    }
    while (builder->cap < needed) {
        builder->cap *= 2;
    }
    builder->data = realloc(builder->data, builder->cap);
    if (builder->data == NULL) {
        die("Out of memory");
    }
}

static void sb_append(StringBuilder *builder, const char *text) {
    size_t len = strlen(text);
    sb_reserve(builder, len);
    memcpy(builder->data + builder->len, text, len + 1);
    builder->len += len;
}

static void sb_append_char(StringBuilder *builder, char ch) {
    sb_reserve(builder, 1);
    builder->data[builder->len++] = ch;
    builder->data[builder->len] = '\0';
}

static void sb_appendf(StringBuilder *builder, const char *format, ...) {
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    int needed = vsnprintf(NULL, 0, format, copy);
    va_end(copy);
    if (needed < 0) {
        va_end(args);
        die("Failed to format string");
    }
    sb_reserve(builder, (size_t)needed);
    vsnprintf(builder->data + builder->len, builder->cap - builder->len, format, args);
    builder->len += (size_t)needed;
    va_end(args);
}

static char *sb_take(StringBuilder *builder) {
    char *result = builder->data;
    builder->data = NULL;
    builder->len = 0;
    builder->cap = 0;
    return result;
}

static void append_json_string(StringBuilder *builder, const char *text) {
    sb_append_char(builder, '"');
    if (text != NULL) {
        for (const unsigned char *cursor = (const unsigned char *)text; *cursor != '\0'; ++cursor) {
            switch (*cursor) {
                case '\\':
                    sb_append(builder, "\\\\");
                    break;
                case '"':
                    sb_append(builder, "\\\"");
                    break;
                case '\n':
                    sb_append(builder, "\\n");
                    break;
                case '\r':
                    sb_append(builder, "\\r");
                    break;
                case '\t':
                    sb_append(builder, "\\t");
                    break;
                default:
                    if (*cursor < 0x20) {
                        sb_appendf(builder, "\\u%04x", *cursor);
                    } else {
                        sb_append_char(builder, (char)*cursor);
                    }
            }
        }
    }
    sb_append_char(builder, '"');
}

static char *dirname_dup(const char *path) {
    char *copy = xstrdup(path);
    char *slash = strrchr(copy, '/');
    if (slash == NULL) {
        strcpy(copy, ".");
        return copy;
    }
    if (slash == copy) {
        slash[1] = '\0';
        return copy;
    }
    *slash = '\0';
    return copy;
}

static char *path_join(const char *left, const char *right) {
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, left);
    if (builder.len > 0 && builder.data[builder.len - 1] != '/') {
        sb_append_char(&builder, '/');
    }
    sb_append(&builder, right);
    return sb_take(&builder);
}

static char *current_working_directory(void) {
    size_t size = 256;
    while (true) {
        char *buffer = xmalloc(size);
        if (getcwd(buffer, size) != NULL) {
            return buffer;
        }
        free(buffer);
        if (errno != ERANGE) {
            die("Failed to get current working directory");
        }
        if (size > SIZE_MAX / 2) {
            die("Current working directory path is too long");
        }
        size *= 2;
    }
}

static char *normalize_absolute_path(const char *absolute) {
    size_t len = strlen(absolute);
    char *normalized = xmalloc(len + 2);
    size_t out = 0;
    normalized[out++] = '/';
    normalized[out] = '\0';

    size_t cursor = 0;
    while (cursor < len) {
        while (cursor < len && absolute[cursor] == '/') {
            ++cursor;
        }
        size_t start = cursor;
        while (cursor < len && absolute[cursor] != '/') {
            ++cursor;
        }
        size_t segment_len = cursor - start;
        if (segment_len == 0 ||
            (segment_len == 1 && absolute[start] == '.')) {
            continue;
        }
        if (segment_len == 2 && absolute[start] == '.' && absolute[start + 1] == '.') {
            if (out > 1) {
                --out;
                while (out > 0 && normalized[out - 1] != '/') {
                    --out;
                }
                normalized[out] = '\0';
            }
            continue;
        }
        if (out > 1 && normalized[out - 1] != '/') {
            normalized[out++] = '/';
        }
        memcpy(normalized + out, absolute + start, segment_len);
        out += segment_len;
        normalized[out] = '\0';
    }
    if (out > 1 && normalized[out - 1] == '/') {
        normalized[out - 1] = '\0';
    }
    return normalized;
}

static char *absolute_path(const char *path) {
    if (path[0] == '/') {
        return normalize_absolute_path(path);
    }
    char *cwd = current_working_directory();
    char *joined = path_join(cwd, path);
    free(cwd);
    char *normalized = normalize_absolute_path(joined);
    free(joined);
    return normalized;
}

static void sleep_ms(long millis) {
    if (millis <= 0) {
        return;
    }
    struct timespec remaining = {
        .tv_sec = millis / 1000,
        .tv_nsec = (millis % 1000) * 1000000L,
    };
    while (nanosleep(&remaining, &remaining) != 0) {
        if (errno != EINTR) {
            die("Failed to sleep");
        }
    }
}

static bool is_regular_file(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

static bool is_directory(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

static int mkdir_p(const char *path) {
    char *copy = xstrdup(path);
    for (char *cursor = copy + 1; *cursor != '\0'; ++cursor) {
        if (*cursor == '/') {
            *cursor = '\0';
            mkdir(copy, 0775);
            *cursor = '/';
        }
    }
    int result = mkdir(copy, 0775);
    if (result != 0 && errno != EEXIST) {
        free(copy);
        return -1;
    }
    free(copy);
    return 0;
}

static char *read_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    long size = ftell(file);
    if (size < 0) {
        fclose(file);
        return NULL;
    }
    rewind(file);
    char *buffer = xmalloc((size_t)size + 1);
    size_t read = fread(buffer, 1, (size_t)size, file);
    fclose(file);
    buffer[read] = '\0';
    return buffer;
}

static const char *skip_ws(const char *cursor) {
    while (*cursor != '\0' && isspace((unsigned char)*cursor)) {
        ++cursor;
    }
    return cursor;
}

static const char *scan_json_string_end(const char *cursor) {
    ++cursor;
    while (*cursor != '\0') {
        if (*cursor == '\\') {
            cursor += cursor[1] == '\0' ? 1 : 2;
            continue;
        }
        if (*cursor == '"') {
            return cursor + 1;
        }
        ++cursor;
    }
    return NULL;
}

static const char *scan_json_block_end(const char *cursor, char open, char close) {
    int depth = 0;
    while (*cursor != '\0') {
        if (*cursor == '"') {
            cursor = scan_json_string_end(cursor);
            if (cursor == NULL) {
                return NULL;
            }
            continue;
        }
        if (*cursor == open) {
            ++depth;
        } else if (*cursor == close) {
            --depth;
            if (depth == 0) {
                return cursor + 1;
            }
        }
        ++cursor;
    }
    return NULL;
}

static const char *scan_json_value_end(const char *cursor) {
    cursor = skip_ws(cursor);
    if (*cursor == '"') {
        return scan_json_string_end(cursor);
    }
    if (*cursor == '{') {
        return scan_json_block_end(cursor, '{', '}');
    }
    if (*cursor == '[') {
        return scan_json_block_end(cursor, '[', ']');
    }
    while (*cursor != '\0' && *cursor != ',' && *cursor != '}' && *cursor != ']' &&
           *cursor != '\n' && *cursor != '\r') {
        ++cursor;
    }
    return cursor;
}

static char *substr(const char *start, size_t len) {
    char *out = xmalloc(len + 1);
    memcpy(out, start, len);
    out[len] = '\0';
    return out;
}

static char *json_unquote(const char *raw);

static char *json_extract_value(const char *json, const char *key) {
    const char *cursor = skip_ws(json);
    if (*cursor != '{') {
        return NULL;
    }

    cursor = skip_ws(cursor + 1);
    while (*cursor != '\0' && *cursor != '}') {
        if (*cursor != '"') {
            return NULL;
        }

        const char *encoded_key_end = scan_json_string_end(cursor);
        if (encoded_key_end == NULL) {
            return NULL;
        }

        char *encoded_key = substr(cursor, (size_t)(encoded_key_end - cursor));
        char *decoded_key = json_unquote(encoded_key);
        free(encoded_key);

        const char *after_key = skip_ws(encoded_key_end);
        if (*after_key != ':') {
            free(decoded_key);
            return NULL;
        }

        const char *value_start = skip_ws(after_key + 1);
        const char *value_end = scan_json_value_end(value_start);
        if (value_end == NULL) {
            free(decoded_key);
            return NULL;
        }

        if (strcmp(decoded_key, key) == 0) {
            free(decoded_key);
            return substr(value_start, (size_t)(value_end - value_start));
        }
        free(decoded_key);

        cursor = skip_ws(value_end);
        if (*cursor == ',') {
            cursor = skip_ws(cursor + 1);
            continue;
        }
        if (*cursor == '}') {
            break;
        }
        return NULL;
    }

    return NULL;
}

static char *json_unquote(const char *raw) {
    if (raw == NULL) {
        return NULL;
    }
    size_t len = strlen(raw);
    if (len < 2 || raw[0] != '"' || raw[len - 1] != '"') {
        return xstrdup(raw);
    }
    char *out = xmalloc(len + 1);
    size_t out_len = 0;
    for (size_t i = 1; i + 1 < len; ++i) {
        if (raw[i] == '\\' && i + 1 < len - 1) {
            ++i;
            switch (raw[i]) {
                case '"':
                case '\\':
                case '/':
                    out[out_len++] = raw[i];
                    break;
                case 'n':
                    out[out_len++] = '\n';
                    break;
                case 'r':
                    out[out_len++] = '\r';
                    break;
                case 't':
                    out[out_len++] = '\t';
                    break;
                default:
                    out[out_len++] = raw[i];
                    break;
            }
            continue;
        }
        out[out_len++] = raw[i];
    }
    out[out_len] = '\0';
    return out;
}

static char *json_minify(const char *input) {
    StringBuilder builder;
    sb_init(&builder);
    bool in_string = false;
    bool escaped = false;
    for (const unsigned char *cursor = (const unsigned char *)input; *cursor != '\0'; ++cursor) {
        if (in_string) {
            sb_append_char(&builder, (char)*cursor);
            if (escaped) {
                escaped = false;
            } else if (*cursor == '\\') {
                escaped = true;
            } else if (*cursor == '"') {
                in_string = false;
            }
            continue;
        }
        if (isspace(*cursor)) {
            continue;
        }
        sb_append_char(&builder, (char)*cursor);
        if (*cursor == '"') {
            in_string = true;
        }
    }
    return sb_take(&builder);
}

static void free_candidate(Candidate *candidate) {
    free(candidate->descriptor_path);
    free(candidate->descriptor_json);
    free(candidate->workspace_root);
    free(candidate->backend_name);
    free(candidate->backend_version);
    free(candidate->socket_path);
    free(candidate->runtime_status_json);
    free(candidate->capabilities_json);
    free(candidate->error_code);
    free(candidate->error_message);
    free(candidate->error_details_json);
}

static void candidate_list_push(CandidateList *list, Candidate candidate) {
    if (list->count == list->cap) {
        list->cap = list->cap == 0 ? 4 : list->cap * 2;
        list->items = realloc(list->items, list->cap * sizeof(Candidate));
        if (list->items == NULL) {
            die("Out of memory");
        }
    }
    list->items[list->count++] = candidate;
}

static void free_candidate_list(CandidateList *list) {
    for (size_t i = 0; i < list->count; ++i) {
        free_candidate(&list->items[i]);
    }
    free(list->items);
    list->items = NULL;
    list->count = 0;
    list->cap = 0;
}

static bool pid_alive(long pid) {
    if (pid <= 0) {
        return false;
    }
    if (kill((pid_t)pid, 0) == 0) {
        return true;
    }
    return errno == EPERM;
}

static char *descriptor_directory(const char *workspace_root) {
    const char *override = getenv("KAST_INSTANCE_DIR");
    if (override != NULL && override[0] != '\0') {
        return absolute_path(override);
    }
    char *metadata = path_join(workspace_root, ".kast");
    char *result = path_join(metadata, "instances");
    free(metadata);
    return result;
}

static char *default_log_file(const char *workspace_root) {
    char *metadata = path_join(workspace_root, ".kast");
    char *logs = path_join(metadata, "logs");
    char *result = path_join(logs, "standalone-daemon.log");
    free(metadata);
    free(logs);
    return result;
}

static char *resolve_helper_dir(const char *argv0) {
    char *absolute = absolute_path(argv0);
    char *dir = dirname_dup(absolute);
    free(absolute);
    return dir;
}

static char *find_runtime_libs_dir(const char *helper_dir) {
    char *candidate = path_join(helper_dir, "../runtime-libs");
    char *absolute = absolute_path(candidate);
    free(candidate);
    char *classpath = path_join(absolute, "classpath.txt");
    if (is_regular_file(classpath)) {
        free(classpath);
        return absolute;
    }
    free(classpath);
    free(absolute);

    candidate = path_join(helper_dir, "runtime-libs");
    absolute = absolute_path(candidate);
    free(candidate);
    classpath = path_join(absolute, "classpath.txt");
    if (is_regular_file(classpath)) {
        free(classpath);
        return absolute;
    }
    free(classpath);
    free(absolute);
    return NULL;
}

static char *build_runtime_classpath(const char *runtime_libs_dir) {
    char *classpath_file = path_join(runtime_libs_dir, "classpath.txt");
    char *contents = read_file(classpath_file);
    free(classpath_file);
    if (contents == NULL) {
        return NULL;
    }

    StringBuilder builder;
    sb_init(&builder);
    char *line = contents;
    while (*line != '\0') {
        char *next = strchr(line, '\n');
        if (next != NULL) {
            *next = '\0';
        }
        while (*line != '\0' && (*line == '\r' || *line == '\n' || isspace((unsigned char)*line))) {
            ++line;
        }
        size_t len = strlen(line);
        while (len > 0 && isspace((unsigned char)line[len - 1])) {
            line[--len] = '\0';
        }
        if (len > 0) {
            char *jar = path_join(runtime_libs_dir, line);
            if (builder.len > 0) {
                sb_append_char(&builder, ':');
            }
            sb_append(&builder, jar);
            free(jar);
        }
        if (next == NULL) {
            break;
        }
        line = next + 1;
    }
    free(contents);
    return sb_take(&builder);
}

static char *java_executable(void) {
    const char *java_home = getenv("JAVA_HOME");
    if (java_home != NULL && java_home[0] != '\0') {
        char *bin = path_join(java_home, "bin");
        char *java = path_join(bin, "java");
        free(bin);
        if (is_regular_file(java)) {
            return java;
        }
        free(java);
    }
    return xstrdup("java");
}

static void exec_java_main(const char *helper_dir, const char *main_class, int argc, char **argv) {
    char *runtime_libs_dir = find_runtime_libs_dir(helper_dir);
    if (runtime_libs_dir == NULL) {
        die("Could not locate runtime-libs for kast helper");
    }
    char *classpath = build_runtime_classpath(runtime_libs_dir);
    free(runtime_libs_dir);
    if (classpath == NULL || classpath[0] == '\0') {
        die("Could not build helper runtime classpath");
    }
    char *java = java_executable();

    char **exec_argv = xmalloc((size_t)argc + 5 * sizeof(char *));
    int index = 0;
    exec_argv[index++] = java;
    exec_argv[index++] = "-cp";
    exec_argv[index++] = classpath;
    exec_argv[index++] = (char *)main_class;
    for (int i = 1; i < argc; ++i) {
        exec_argv[index++] = argv[i];
    }
    exec_argv[index] = NULL;

    execvp(java, exec_argv);
    perror("execvp");
    exit(1);
}

static void print_cli_error(const char *code, const char *message, const char *details_json) {
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"code\":");
    append_json_string(&builder, code);
    sb_append(&builder, ",\"message\":");
    append_json_string(&builder, message);
    sb_append(&builder, ",\"details\":");
    if (details_json != NULL) {
        sb_append(&builder, details_json);
    } else {
        sb_append(&builder, "{}");
    }
    sb_appendf(&builder, ",\"schemaVersion\":%d}", SCHEMA_VERSION);
    fputs(builder.data, stderr);
    fputc('\n', stderr);
    free(builder.data);
}

static void append_rpc_debug_dump(const char *method, const char *response) {
    const char *path = getenv("KAST_DEBUG_RPC_FILE");
    if (path == NULL || path[0] == '\0' || method == NULL || response == NULL) {
        return;
    }

    FILE *file = fopen(path, "a");
    if (file == NULL) {
        return;
    }
    fprintf(file, "method=%s\n", method);
    fprintf(file, "response=%s\n", response);
    fprintf(file, "---\n");
    fclose(file);
}

static int unix_socket_call(const char *socket_path, const char *request_json, char **response_out) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(socket_path) >= sizeof(address.sun_path)) {
        close(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    strcpy(address.sun_path, socket_path);

    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(fd);
        return -1;
    }

    size_t request_len = strlen(request_json);
    if (write(fd, request_json, request_len) != (ssize_t)request_len ||
        write(fd, "\n", 1) != 1) {
        close(fd);
        return -1;
    }

    StringBuilder builder;
    sb_init(&builder);
    char buffer[4096];
    ssize_t read_count;
    while ((read_count = read(fd, buffer, sizeof(buffer))) > 0) {
        sb_reserve(&builder, (size_t)read_count);
        memcpy(builder.data + builder.len, buffer, (size_t)read_count);
        builder.len += (size_t)read_count;
        builder.data[builder.len] = '\0';
        if (strchr(builder.data, '\n') != NULL) {
            break;
        }
    }
    close(fd);
    char *newline = strchr(builder.data, '\n');
    if (newline != NULL) {
        *newline = '\0';
    }
    *response_out = sb_take(&builder);
    return 0;
}

static bool runtime_status_ready(const char *runtime_status_json) {
    return strstr(runtime_status_json, "\"state\":\"READY\"") != NULL &&
           strstr(runtime_status_json, "\"healthy\":true") != NULL &&
           strstr(runtime_status_json, "\"active\":true") != NULL &&
           strstr(runtime_status_json, "\"indexing\":false") != NULL;
}

static int rpc_call(
    const char *socket_path,
    const char *method,
    const char *params_json,
    char **result_json,
    char **error_code,
    char **error_message,
    char **error_details_json
) {
    *result_json = NULL;
    *error_code = NULL;
    *error_message = NULL;
    *error_details_json = NULL;

    StringBuilder request;
    sb_init(&request);
    sb_append(&request, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":");
    append_json_string(&request, method);
    sb_append(&request, ",\"params\":");
    sb_append(&request, params_json == NULL ? "{}" : params_json);
    sb_append_char(&request, '}');

    char *response = NULL;
    if (unix_socket_call(socket_path, request.data, &response) != 0) {
        free(request.data);
        *error_code = xstrdup("DAEMON_UNREACHABLE");
        *error_message = xstrdup(strerror(errno));
        return -1;
    }
    free(request.data);
    append_rpc_debug_dump(method, response);

    char *result = json_extract_value(response, "result");
    if (result != NULL) {
        *result_json = result;
        free(response);
        return 0;
    }

    char *error = json_extract_value(response, "error");
    free(response);
    if (error == NULL) {
        *error_code = xstrdup("RPC_RESPONSE_INVALID");
        *error_message = xstrdup("Missing JSON-RPC result");
        return -1;
    }
    char *data = json_extract_value(error, "data");
    if (data != NULL) {
        char *code = json_extract_value(data, "code");
        char *message = json_extract_value(data, "message");
        char *details = json_extract_value(data, "details");
        *error_code = json_unquote(code);
        *error_message = json_unquote(message);
        *error_details_json = details;
        free(code);
        free(message);
        free(data);
    } else {
        char *message = json_extract_value(error, "message");
        *error_code = xstrdup("RPC_ERROR");
        *error_message = json_unquote(message);
        free(message);
    }
    free(error);
    return -1;
}

static char *build_candidate_json(const Candidate *candidate) {
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"descriptorPath\":");
    append_json_string(&builder, candidate->descriptor_path);
    sb_append(&builder, ",\"descriptor\":");
    sb_append(&builder, candidate->descriptor_json);
    sb_appendf(&builder, ",\"pidAlive\":%s", candidate->pid_alive ? "true" : "false");
    sb_appendf(&builder, ",\"reachable\":%s", candidate->reachable ? "true" : "false");
    sb_appendf(&builder, ",\"ready\":%s", candidate->ready ? "true" : "false");
    sb_append(&builder, ",\"runtimeStatus\":");
    sb_append(&builder, candidate->runtime_status_json != NULL ? candidate->runtime_status_json : "null");
    sb_append(&builder, ",\"capabilities\":");
    sb_append(&builder, candidate->capabilities_json != NULL ? candidate->capabilities_json : "null");
    sb_append(&builder, ",\"errorMessage\":");
    if (candidate->error_message != NULL) {
        append_json_string(&builder, candidate->error_message);
    } else {
        sb_append(&builder, "null");
    }
    sb_appendf(&builder, ",\"schemaVersion\":%d}", SCHEMA_VERSION);
    return sb_take(&builder);
}

static Candidate inspect_descriptor(const char *descriptor_path, bool prune_stale) {
    Candidate candidate;
    memset(&candidate, 0, sizeof(candidate));
    candidate.descriptor_path = xstrdup(descriptor_path);
    candidate.descriptor_json = read_file(descriptor_path);
    if (candidate.descriptor_json == NULL) {
        candidate.error_code = xstrdup("DESCRIPTOR_READ_FAILED");
        candidate.error_message = xstrdup("Failed to read descriptor");
        return candidate;
    }

    char *workspace_root = json_extract_value(candidate.descriptor_json, "workspaceRoot");
    char *backend_name = json_extract_value(candidate.descriptor_json, "backendName");
    char *backend_version = json_extract_value(candidate.descriptor_json, "backendVersion");
    char *socket_path = json_extract_value(candidate.descriptor_json, "socketPath");
    char *pid_raw = json_extract_value(candidate.descriptor_json, "pid");

    candidate.workspace_root = json_unquote(workspace_root);
    candidate.backend_name = json_unquote(backend_name);
    candidate.backend_version = json_unquote(backend_version);
    candidate.socket_path = json_unquote(socket_path);
    candidate.pid = pid_raw != NULL ? strtol(pid_raw, NULL, 10) : 0;
    candidate.pid_alive = pid_alive(candidate.pid);
    free(workspace_root);
    free(backend_name);
    free(backend_version);
    free(socket_path);
    free(pid_raw);

    if (!candidate.pid_alive) {
        if (prune_stale) {
            unlink(descriptor_path);
        }
        StringBuilder message;
        sb_init(&message);
        sb_appendf(&message, "Process %ld is not alive", candidate.pid);
        candidate.error_message = sb_take(&message);
        return candidate;
    }

    char *result = NULL;
    if (rpc_call(candidate.socket_path, "runtime/status", "{}", &result,
                 &candidate.error_code, &candidate.error_message, &candidate.error_details_json) == 0) {
        candidate.runtime_status_json = result;
        candidate.reachable = 1;
        candidate.ready = runtime_status_ready(result);
        if (rpc_call(candidate.socket_path, "capabilities", "{}", &result,
                     &candidate.error_code, &candidate.error_message, &candidate.error_details_json) == 0) {
            candidate.capabilities_json = result;
        }
    }
    return candidate;
}

static Candidate *select_candidate(CandidateList *list) {
    for (size_t i = 0; i < list->count; ++i) {
        if (list->items[i].ready) {
            return &list->items[i];
        }
    }
    if (list->count == 0) {
        return NULL;
    }
    return &list->items[0];
}

static CandidateList inspect_workspace(const char *workspace_root, bool prune_stale) {
    CandidateList list = {0};
    char *directory = descriptor_directory(workspace_root);
    if (!is_directory(directory)) {
        free(directory);
        return list;
    }

    DIR *dir = opendir(directory);
    if (dir == NULL) {
        free(directory);
        return list;
    }

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        const char *name = entry->d_name;
        size_t len = strlen(name);
        if (len < 6 || strcmp(name + len - 5, ".json") != 0) {
            continue;
        }
        char *path = path_join(directory, name);
        Candidate candidate = inspect_descriptor(path, prune_stale);
        if (candidate.workspace_root != NULL &&
            strcmp(candidate.workspace_root, workspace_root) == 0 &&
            candidate.backend_name != NULL &&
            strcmp(candidate.backend_name, "standalone") == 0) {
            candidate_list_push(&list, candidate);
        } else {
            free_candidate(&candidate);
        }
        free(path);
    }
    closedir(dir);
    free(directory);
    return list;
}

static void kill_candidate(Candidate *candidate) {
    if (candidate->pid_alive) {
        kill((pid_t)candidate->pid, SIGTERM);
        for (int i = 0; i < 20; ++i) {
            if (!pid_alive(candidate->pid)) {
                break;
            }
            sleep_ms(250);
        }
        if (pid_alive(candidate->pid)) {
            kill((pid_t)candidate->pid, SIGKILL);
        }
    }
    unlink(candidate->descriptor_path);
}

static char *option_value(const ParsedArgs *parsed, const char *key) {
    for (size_t i = 0; i < parsed->option_count; ++i) {
        if (strcmp(parsed->option_keys[i], key) == 0) {
            return parsed->option_values[i];
        }
    }
    return NULL;
}

static void parse_args(int argc, char **argv, ParsedArgs *parsed) {
    memset(parsed, 0, sizeof(*parsed));
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--", 2) == 0) {
            char *equals = strchr(argv[i], '=');
            if (equals == NULL) {
                parsed->positionals = realloc(parsed->positionals, (parsed->positional_count + 1) * sizeof(char *));
                parsed->positionals[parsed->positional_count++] = argv[i];
                continue;
            }
            parsed->option_keys = realloc(parsed->option_keys, (parsed->option_count + 1) * sizeof(char *));
            parsed->option_values = realloc(parsed->option_values, (parsed->option_count + 1) * sizeof(char *));
            parsed->option_keys[parsed->option_count] = substr(argv[i] + 2, (size_t)(equals - argv[i] - 2));
            parsed->option_values[parsed->option_count] = xstrdup(equals + 1);
            ++parsed->option_count;
            continue;
        }
        parsed->positionals = realloc(parsed->positionals, (parsed->positional_count + 1) * sizeof(char *));
        parsed->positionals[parsed->positional_count++] = argv[i];
    }
}

static void free_parsed_args(ParsedArgs *parsed) {
    for (size_t i = 0; i < parsed->option_count; ++i) {
        free(parsed->option_keys[i]);
        free(parsed->option_values[i]);
    }
    free(parsed->option_keys);
    free(parsed->option_values);
    free(parsed->positionals);
}

static bool is_help_or_version(const ParsedArgs *parsed) {
    if (parsed->positional_count == 0) {
        return true;
    }
    const char *first = parsed->positionals[0];
    return strcmp(first, "help") == 0 ||
           strcmp(first, "--help") == 0 ||
           strcmp(first, "-h") == 0 ||
           strcmp(first, "--version") == 0 ||
           strcmp(first, "-V") == 0 ||
           strcmp(first, "version") == 0 ||
           strcmp(first, "internal") == 0;
}

static char *normalize_workspace_root(const ParsedArgs *parsed) {
    char *workspace = option_value(parsed, "workspace-root");
    if (workspace == NULL) {
        return NULL;
    }
    return absolute_path(workspace);
}

static long parse_timeout_ms(const ParsedArgs *parsed) {
    char *value = option_value(parsed, "wait-timeout-ms");
    if (value == NULL || value[0] == '\0') {
        return DEFAULT_WAIT_TIMEOUT_MS;
    }
    return strtol(value, NULL, 10);
}

static long parse_request_timeout_ms(const ParsedArgs *parsed) {
    char *value = option_value(parsed, "request-timeout-ms");
    if (value == NULL || value[0] == '\0') {
        return DEFAULT_REQUEST_TIMEOUT_MS;
    }
    return strtol(value, NULL, 10);
}

static int parse_max_results(const ParsedArgs *parsed) {
    char *value = option_value(parsed, "max-results");
    if (value == NULL || value[0] == '\0') {
        return DEFAULT_MAX_RESULTS;
    }
    return (int)strtol(value, NULL, 10);
}

static int parse_max_concurrent(const ParsedArgs *parsed) {
    char *value = option_value(parsed, "max-concurrent-requests");
    if (value == NULL || value[0] == '\0') {
        return DEFAULT_MAX_CONCURRENT;
    }
    return (int)strtol(value, NULL, 10);
}

static char *build_symbol_query(const ParsedArgs *parsed) {
    char *request_file = option_value(parsed, "request-file");
    if (request_file != NULL) {
        char *contents = read_file(request_file);
        if (contents == NULL) {
            return NULL;
        }
        char *minified = json_minify(contents);
        free(contents);
        return minified;
    }
    char *file_path = option_value(parsed, "file-path");
    char *offset = option_value(parsed, "offset");
    if (file_path == NULL || offset == NULL) {
        return NULL;
    }
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"position\":{\"filePath\":");
    append_json_string(&builder, file_path);
    sb_appendf(&builder, ",\"offset\":%s}}", offset);
    return sb_take(&builder);
}

static char *build_references_query(const ParsedArgs *parsed) {
    char *request_file = option_value(parsed, "request-file");
    if (request_file != NULL) {
        char *contents = read_file(request_file);
        if (contents == NULL) {
            return NULL;
        }
        char *minified = json_minify(contents);
        free(contents);
        return minified;
    }
    char *file_path = option_value(parsed, "file-path");
    char *offset = option_value(parsed, "offset");
    const char *include_declaration = option_value(parsed, "include-declaration");
    if (file_path == NULL || offset == NULL) {
        return NULL;
    }
    if (include_declaration == NULL) {
        include_declaration = "false";
    }
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"position\":{\"filePath\":");
    append_json_string(&builder, file_path);
    sb_appendf(&builder, ",\"offset\":%s},\"includeDeclaration\":%s}", offset, include_declaration);
    return sb_take(&builder);
}

static char *build_diagnostics_query(const ParsedArgs *parsed) {
    char *request_file = option_value(parsed, "request-file");
    if (request_file != NULL) {
        char *contents = read_file(request_file);
        if (contents == NULL) {
            return NULL;
        }
        char *minified = json_minify(contents);
        free(contents);
        return minified;
    }
    char *file_paths = option_value(parsed, "file-paths");
    if (file_paths == NULL) {
        return NULL;
    }
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"filePaths\":[");
    char *copy = xstrdup(file_paths);
    char *token = strtok(copy, ",");
    bool first = true;
    while (token != NULL) {
        while (*token != '\0' && isspace((unsigned char)*token)) {
            ++token;
        }
        size_t len = strlen(token);
        while (len > 0 && isspace((unsigned char)token[len - 1])) {
            token[--len] = '\0';
        }
        if (!first) {
            sb_append_char(&builder, ',');
        }
        append_json_string(&builder, token);
        first = false;
        token = strtok(NULL, ",");
    }
    free(copy);
    sb_append(&builder, "]}");
    return sb_take(&builder);
}

static char *build_rename_query(const ParsedArgs *parsed) {
    char *request_file = option_value(parsed, "request-file");
    if (request_file != NULL) {
        char *contents = read_file(request_file);
        if (contents == NULL) {
            return NULL;
        }
        char *minified = json_minify(contents);
        free(contents);
        return minified;
    }
    char *file_path = option_value(parsed, "file-path");
    char *offset = option_value(parsed, "offset");
    char *new_name = option_value(parsed, "new-name");
    const char *dry_run = option_value(parsed, "dry-run");
    if (file_path == NULL || offset == NULL || new_name == NULL) {
        return NULL;
    }
    if (dry_run == NULL) {
        dry_run = "true";
    }
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"position\":{\"filePath\":");
    append_json_string(&builder, file_path);
    sb_appendf(&builder, ",\"offset\":%s},\"newName\":", offset);
    append_json_string(&builder, new_name);
    sb_appendf(&builder, ",\"dryRun\":%s}", dry_run);
    return sb_take(&builder);
}

static char *build_request_file_query(const ParsedArgs *parsed) {
    char *request_file = option_value(parsed, "request-file");
    if (request_file == NULL) {
        return NULL;
    }
    char *contents = read_file(request_file);
    if (contents == NULL) {
        return NULL;
    }
    char *minified = json_minify(contents);
    free(contents);
    return minified;
}

static void print_daemon_note(const char *action, const Candidate *candidate, const char *log_file) {
    fprintf(stderr, "daemon: %s standalone daemon pid=%ld ready at %s", action, candidate->pid, candidate->socket_path);
    if (log_file != NULL) {
        fprintf(stderr, " (log: %s)", log_file);
    }
    fputc('\n', stderr);
}

static char *workspace_status_json(const char *workspace_root, const char *descriptor_dir, CandidateList *list) {
    Candidate *selected = select_candidate(list);
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"workspaceRoot\":");
    append_json_string(&builder, workspace_root);
    sb_append(&builder, ",\"descriptorDirectory\":");
    append_json_string(&builder, descriptor_dir);
    sb_append(&builder, ",\"selected\":");
    if (selected != NULL) {
        char *selected_json = build_candidate_json(selected);
        sb_append(&builder, selected_json);
        free(selected_json);
    } else {
        sb_append(&builder, "null");
    }
    sb_append(&builder, ",\"candidates\":[");
    for (size_t i = 0; i < list->count; ++i) {
        if (i > 0) {
            sb_append_char(&builder, ',');
        }
        char *candidate_json = build_candidate_json(&list->items[i]);
        sb_append(&builder, candidate_json);
        free(candidate_json);
    }
    sb_appendf(&builder, "],\"schemaVersion\":%d}", SCHEMA_VERSION);
    return sb_take(&builder);
}

static char *workspace_ensure_json(const char *workspace_root, bool started, const char *log_file, Candidate *selected) {
    char *selected_json = build_candidate_json(selected);
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"workspaceRoot\":");
    append_json_string(&builder, workspace_root);
    sb_appendf(&builder, ",\"started\":%s", started ? "true" : "false");
    sb_append(&builder, ",\"logFile\":");
    if (log_file != NULL) {
        append_json_string(&builder, log_file);
    } else {
        sb_append(&builder, "null");
    }
    sb_append(&builder, ",\"selected\":");
    sb_append(&builder, selected_json);
    sb_appendf(&builder, ",\"schemaVersion\":%d}", SCHEMA_VERSION);
    free(selected_json);
    return sb_take(&builder);
}

static char *daemon_stop_json(const char *workspace_root, bool stopped, const char *descriptor_path, long pid, bool forced) {
    StringBuilder builder;
    sb_init(&builder);
    sb_append(&builder, "{\"workspaceRoot\":");
    append_json_string(&builder, workspace_root);
    sb_appendf(&builder, ",\"stopped\":%s", stopped ? "true" : "false");
    sb_append(&builder, ",\"descriptorPath\":");
    if (descriptor_path != NULL) {
        append_json_string(&builder, descriptor_path);
    } else {
        sb_append(&builder, "null");
    }
    if (pid > 0) {
        sb_appendf(&builder, ",\"pid\":%ld", pid);
    } else {
        sb_append(&builder, ",\"pid\":null");
    }
    sb_appendf(&builder, ",\"forced\":%s,\"schemaVersion\":%d}", forced ? "true" : "false", SCHEMA_VERSION);
    return sb_take(&builder);
}

static pid_t start_daemon_process(
    const char *helper_dir,
    const char *workspace_root,
    long request_timeout_ms,
    int max_results,
    int max_concurrent,
    const char *log_file
) {
    char *runtime_libs_dir = find_runtime_libs_dir(helper_dir);
    if (runtime_libs_dir == NULL) {
        return -1;
    }
    char *classpath = build_runtime_classpath(runtime_libs_dir);
    free(runtime_libs_dir);
    if (classpath == NULL) {
        return -1;
    }
    char *java = java_executable();
    char *log_dir = dirname_dup(log_file);
    mkdir_p(log_dir);
    free(log_dir);

    pid_t pid = fork();
    if (pid != 0) {
        free(classpath);
        free(java);
        return pid;
    }

    setsid();
    int fd = open(log_file, O_CREAT | O_WRONLY | O_TRUNC, 0664);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }
    int dev_null = open("/dev/null", O_RDONLY);
    if (dev_null >= 0) {
        dup2(dev_null, STDIN_FILENO);
        close(dev_null);
    }
    if (chdir(workspace_root) != 0) {
        _exit(1);
    }

    char *workspace_arg = xstrprintf("--workspace-root=%s", workspace_root);
    char *timeout_arg = xstrprintf("--request-timeout-ms=%ld", request_timeout_ms);
    char *results_arg = xstrprintf("--max-results=%d", max_results);
    char *concurrent_arg = xstrprintf("--max-concurrent-requests=%d", max_concurrent);

    char *const exec_argv[] = {
        java,
        "-cp",
        classpath,
        "io.github.amichne.kast.standalone.StandaloneMainKt",
        workspace_arg,
        timeout_arg,
        results_arg,
        concurrent_arg,
        NULL,
    };
    execvp(java, exec_argv);
    _exit(1);
}

static Candidate *wait_for_ready_candidate(const char *workspace_root, long timeout_ms) {
    long elapsed = 0;
    while (elapsed < timeout_ms) {
        CandidateList list = inspect_workspace(workspace_root, true);
        Candidate *selected = select_candidate(&list);
        if (selected != NULL && selected->ready) {
            Candidate *result = xmalloc(sizeof(Candidate));
            *result = *selected;
            list.items[selected - list.items].descriptor_path = NULL;
            list.items[selected - list.items].descriptor_json = NULL;
            list.items[selected - list.items].workspace_root = NULL;
            list.items[selected - list.items].backend_name = NULL;
            list.items[selected - list.items].backend_version = NULL;
            list.items[selected - list.items].socket_path = NULL;
            list.items[selected - list.items].runtime_status_json = NULL;
            list.items[selected - list.items].capabilities_json = NULL;
            list.items[selected - list.items].error_code = NULL;
            list.items[selected - list.items].error_message = NULL;
            list.items[selected - list.items].error_details_json = NULL;
            free_candidate_list(&list);
            return result;
        }
        free_candidate_list(&list);
        sleep_ms(250);
        elapsed += 250;
    }
    return NULL;
}

static Candidate *ensure_runtime(
    const char *helper_dir,
    const char *workspace_root,
    long timeout_ms,
    long request_timeout_ms,
    int max_results,
    int max_concurrent,
    bool *started,
    char **log_file_out
) {
    *started = false;
    *log_file_out = NULL;

    CandidateList list = inspect_workspace(workspace_root, true);
    Candidate *selected = select_candidate(&list);
    if (selected != NULL && selected->ready) {
        Candidate *result = xmalloc(sizeof(Candidate));
        *result = *selected;
        list.items[selected - list.items].descriptor_path = NULL;
        list.items[selected - list.items].descriptor_json = NULL;
        list.items[selected - list.items].workspace_root = NULL;
        list.items[selected - list.items].backend_name = NULL;
        list.items[selected - list.items].backend_version = NULL;
        list.items[selected - list.items].socket_path = NULL;
        list.items[selected - list.items].runtime_status_json = NULL;
        list.items[selected - list.items].capabilities_json = NULL;
        list.items[selected - list.items].error_code = NULL;
        list.items[selected - list.items].error_message = NULL;
        list.items[selected - list.items].error_details_json = NULL;
        free_candidate_list(&list);
        return result;
    }

    if (selected != NULL && selected->pid_alive && selected->reachable &&
        strstr(selected->runtime_status_json, "\"state\":\"DEGRADED\"") == NULL) {
        free_candidate_list(&list);
        Candidate *ready = wait_for_ready_candidate(workspace_root, timeout_ms);
        if (ready != NULL) {
            return ready;
        }
    } else if (selected != NULL) {
        kill_candidate(selected);
        free_candidate_list(&list);
    } else {
        free_candidate_list(&list);
    }

    char *log_file = default_log_file(workspace_root);
    pid_t pid = start_daemon_process(
        helper_dir,
        workspace_root,
        request_timeout_ms,
        max_results,
        max_concurrent,
        log_file
    );
    if (pid < 0) {
        free(log_file);
        return NULL;
    }
    *started = true;
    *log_file_out = log_file;
    return wait_for_ready_candidate(workspace_root, timeout_ms);
}

static int handle_workspace_status(const char *workspace_root) {
    char *descriptor_dir = descriptor_directory(workspace_root);
    CandidateList list = inspect_workspace(workspace_root, false);
    char *json = workspace_status_json(workspace_root, descriptor_dir, &list);
    fputs(json, stdout);
    fputc('\n', stdout);
    Candidate *selected = select_candidate(&list);
    if (selected != NULL) {
        fprintf(stderr, "daemon: selected standalone daemon pid=%ld ready at %s\n",
                selected->pid, selected->socket_path);
    }
    free(json);
    free(descriptor_dir);
    free_candidate_list(&list);
    return 0;
}

static int handle_workspace_ensure(
    const char *helper_dir,
    const char *workspace_root,
    long timeout_ms,
    long request_timeout_ms,
    int max_results,
    int max_concurrent
) {
    bool started = false;
    char *log_file = NULL;
    Candidate *candidate = ensure_runtime(
        helper_dir,
        workspace_root,
        timeout_ms,
        request_timeout_ms,
        max_results,
        max_concurrent,
        &started,
        &log_file
    );
    if (candidate == NULL) {
        print_cli_error("RUNTIME_TIMEOUT", "Timed out waiting for standalone runtime to become ready", NULL);
        free(log_file);
        return 1;
    }
    char *json = workspace_ensure_json(workspace_root, started, log_file, candidate);
    fputs(json, stdout);
    fputc('\n', stdout);
    print_daemon_note(started ? "started" : "using", candidate, log_file);
    free(json);
    free(log_file);
    free_candidate(candidate);
    free(candidate);
    return 0;
}

static int handle_daemon_stop(const char *workspace_root) {
    CandidateList list = inspect_workspace(workspace_root, true);
    Candidate *selected = select_candidate(&list);
    if (selected == NULL) {
        char *json = daemon_stop_json(workspace_root, false, NULL, 0, false);
        fputs(json, stdout);
        fputc('\n', stdout);
        free(json);
        free_candidate_list(&list);
        return 0;
    }
    long pid = selected->pid;
    char *descriptor_path = xstrdup(selected->descriptor_path);
    kill_candidate(selected);
    char *json = daemon_stop_json(workspace_root, true, descriptor_path, pid, false);
    fputs(json, stdout);
    fputc('\n', stdout);
    fprintf(stderr, "daemon: stopped standalone daemon pid=%ld\n", pid);
    free(json);
    free(descriptor_path);
    free_candidate_list(&list);
    return 0;
}

static int call_analysis_method(
    const char *helper_dir,
    const char *workspace_root,
    long timeout_ms,
    long request_timeout_ms,
    int max_results,
    int max_concurrent,
    const char *method,
    const char *params_json
) {
    bool started = false;
    char *log_file = NULL;
    Candidate *candidate = ensure_runtime(
        helper_dir,
        workspace_root,
        timeout_ms,
        request_timeout_ms,
        max_results,
        max_concurrent,
        &started,
        &log_file
    );
    if (candidate == NULL) {
        print_cli_error("RUNTIME_TIMEOUT", "Timed out waiting for standalone runtime to become ready", NULL);
        free(log_file);
        return 1;
    }

    char *result = NULL;
    char *error_code = NULL;
    char *error_message = NULL;
    char *error_details = NULL;
    int rc = rpc_call(candidate->socket_path, method, params_json, &result, &error_code, &error_message, &error_details);
    if (rc != 0) {
        print_cli_error(error_code != NULL ? error_code : "RPC_ERROR",
                        error_message != NULL ? error_message : "RPC request failed",
                        error_details);
        free(result);
        free(error_code);
        free(error_message);
        free(error_details);
        free(log_file);
        free_candidate(candidate);
        free(candidate);
        return 1;
    }

    fputs(result, stdout);
    fputc('\n', stdout);
    print_daemon_note(started ? "started" : "using", candidate, log_file);

    free(result);
    free(log_file);
    free_candidate(candidate);
    free(candidate);
    return 0;
}

int main(int argc, char **argv) {
    ParsedArgs parsed;
    parse_args(argc, argv, &parsed);
    char *helper_dir = resolve_helper_dir(argv[0]);

    if (is_help_or_version(&parsed)) {
        exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
    }

    char *workspace_root = normalize_workspace_root(&parsed);
    if (workspace_root == NULL) {
        exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
    }
    long timeout_ms = parse_timeout_ms(&parsed);
    long request_timeout_ms = parse_request_timeout_ms(&parsed);
    int max_results = parse_max_results(&parsed);
    int max_concurrent = parse_max_concurrent(&parsed);

    int exit_code = 0;
    if (parsed.positional_count == 2 &&
        strcmp(parsed.positionals[0], "workspace") == 0 &&
        strcmp(parsed.positionals[1], "status") == 0) {
        exit_code = handle_workspace_status(workspace_root);
    } else if (parsed.positional_count == 2 &&
               strcmp(parsed.positionals[0], "workspace") == 0 &&
               strcmp(parsed.positionals[1], "ensure") == 0) {
        exit_code = handle_workspace_ensure(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent
        );
    } else if (parsed.positional_count == 2 &&
               strcmp(parsed.positionals[0], "daemon") == 0 &&
               strcmp(parsed.positionals[1], "start") == 0) {
        exit_code = handle_workspace_ensure(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent
        );
    } else if (parsed.positional_count == 2 &&
               strcmp(parsed.positionals[0], "daemon") == 0 &&
               strcmp(parsed.positionals[1], "stop") == 0) {
        exit_code = handle_daemon_stop(workspace_root);
    } else if (parsed.positional_count == 1 &&
               strcmp(parsed.positionals[0], "capabilities") == 0) {
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "capabilities",
            "{}"
        );
    } else if (parsed.positional_count == 2 &&
               strcmp(parsed.positionals[0], "symbol") == 0 &&
               strcmp(parsed.positionals[1], "resolve") == 0) {
        char *params = build_symbol_query(&parsed);
        if (params == NULL) {
            exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
        }
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "symbol/resolve",
            params
        );
        free(params);
    } else if (parsed.positional_count == 1 &&
               strcmp(parsed.positionals[0], "references") == 0) {
        char *params = build_references_query(&parsed);
        if (params == NULL) {
            exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
        }
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "references",
            params
        );
        free(params);
    } else if (parsed.positional_count == 1 &&
               strcmp(parsed.positionals[0], "diagnostics") == 0) {
        char *params = build_diagnostics_query(&parsed);
        if (params == NULL) {
            exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
        }
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "diagnostics",
            params
        );
        free(params);
    } else if (parsed.positional_count == 1 &&
               strcmp(parsed.positionals[0], "rename") == 0) {
        char *params = build_rename_query(&parsed);
        if (params == NULL) {
            exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
        }
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "rename",
            params
        );
        free(params);
    } else if (parsed.positional_count == 2 &&
               strcmp(parsed.positionals[0], "edits") == 0 &&
               strcmp(parsed.positionals[1], "apply") == 0) {
        char *params = build_request_file_query(&parsed);
        if (params == NULL) {
            exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
        }
        exit_code = call_analysis_method(
            helper_dir,
            workspace_root,
            timeout_ms,
            request_timeout_ms,
            max_results,
            max_concurrent,
            "edits/apply",
            params
        );
        free(params);
    } else {
        exec_java_main(helper_dir, "io.github.amichne.kast.cli.CliMainKt", argc, argv);
    }

    free(workspace_root);
    free(helper_dir);
    free_parsed_args(&parsed);
    return exit_code;
}
