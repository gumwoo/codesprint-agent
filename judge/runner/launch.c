/*
 * 사용자 프로그램을 **작은 프로세스의 fork** 에서 띄운다(ADR-0059).
 *
 *     launch <fd> <program> [args...]
 *
 * 하네스(파이썬)가 사용자 프로그램을 직접 fork 하면, 자식은 하네스의 사본(약 6~10MB, 큰 입력이면 그 사본까지)으로
 * 시작하고 커널은 exec 할 때 그 사본의 최고 RSS 를 프로세스의 maxrss 에 남긴다. 그래서 wait4 의 ru_maxrss 가
 * max(하네스 사본, 사용자 프로그램) 이 됐다(ADR-0056 D). 이 실행기는 수백 KB 라, 여기서 fork 한 자식의 maxrss 는
 * 사용자 프로그램 자신의 최고 RSS 가 된다.
 *
 * 하는 일은 셋뿐이다.
 *   fork 한다. 자식은 <program> 을 exec 한다.
 *   부모는 자식의 pid 를 <fd> 에 "pid <n>" 으로 적고 곧바로 끝난다 - 자식을 기다리지 않는다.
 *   exec 가 실패하면 자식이 <fd> 에 "exec <errno>" 를 적고 127 로 끝난다.
 *
 * **기다리지 않는 것이 핵심이다.** 부모가 끝나면 자식은 PID 1(하네스)에게 넘어오고, 하네스가 그 pid 로 직접
 * wait4 한다. 종료 상태 · rusage · 시간 제한 신호의 대상은 전처럼 사용자 프로그램 하나다 - 이 실행기가 사이에서
 * 신호를 전달하거나 종료 상태를 흉내 내지 않는다.
 *
 * <fd> 는 자식에서 close-on-exec 로 둔다. exec 가 성공하면 닫히므로 하네스는 EOF 로 "exec 까지 끝났다" 를 안다
 * (subprocess.Popen 이 exec 를 기다리는 것과 같다). 사용자 프로그램에는 0 · 1 · 2 만 남는다.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void report(int fd, const char *what, long value) {
    char line[48];
    int n = snprintf(line, sizeof line, "%s %ld\n", what, value);
    if (n > 0) {
        ssize_t written = write(fd, line, (size_t)n);
        (void)written;
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        return 120;
    }
    int fd = atoi(argv[1]);
    pid_t pid = fork();
    if (pid < 0) {
        return 121;
    }
    if (pid == 0) {
        fcntl(fd, F_SETFD, FD_CLOEXEC);
        execvp(argv[2], argv + 2);
        report(fd, "exec", errno);
        _exit(127);
    }
    report(fd, "pid", (long)pid);
    return 0;
}
