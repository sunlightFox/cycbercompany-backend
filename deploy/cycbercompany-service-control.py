#!/usr/bin/env python3
import os
import grp
import socket
import subprocess
import sys
import base64
import re

SOCKET_DIR = "/run/cycbercompany"
SOCKET_PATH = SOCKET_DIR + "/service-control.sock"
ALLOWED = {"agent-studio-node.service", "spring-agent-studio.service"}
PACKAGE_NAME = re.compile(r"[a-z0-9][a-z0-9+.-]{0,127}\Z")
MAX_OUTPUT_BYTES = 32 * 1024
INSTALL_TIMEOUT_SECONDS = 1_200


def schedule_restart(service_name: str) -> None:
    unit = "cycbercompany-restart-" + service_name.removesuffix(".service")
    subprocess.run([
        "/usr/bin/systemd-run", "--quiet", "--collect", "--unit=" + unit,
        "--on-active=2s", "/usr/bin/systemctl", "restart", service_name,
    ], check=True)


def bounded(value: bytes) -> tuple[bytes, bool]:
    return value[:MAX_OUTPUT_BYTES], len(value) > MAX_OUTPUT_BYTES


def send_install_result(connection: socket.socket, exit_code: int, timed_out: bool,
                        stdout: bytes, stderr: bytes) -> None:
    stdout, stdout_truncated = bounded(stdout)
    stderr, stderr_truncated = bounded(stderr)
    header = "RESULT {} {} {} {}\n".format(
        exit_code, int(timed_out), int(stdout_truncated), int(stderr_truncated))
    connection.sendall(header.encode("ascii"))
    connection.sendall(b"OUT " + base64.b64encode(stdout) + b"\n")
    connection.sendall(b"ERR " + base64.b64encode(stderr) + b"\n")


def install_package(package_name: str, allow_upgrade: bool) -> tuple[int, bool, bytes, bytes]:
    command = ["/usr/bin/apt-get", "install", "-y", "--no-install-recommends"]
    if not allow_upgrade:
        command.append("--no-upgrade")
    command.append(package_name)
    environment = {"DEBIAN_FRONTEND": "noninteractive", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin"}
    try:
        completed = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, env=environment,
                                 timeout=INSTALL_TIMEOUT_SECONDS, check=False)
        return completed.returncode, False, completed.stdout, completed.stderr
    except subprocess.TimeoutExpired as ex:
        return 124, True, ex.stdout or b"", ex.stderr or b""


def handle(connection: socket.socket) -> None:
    raw = connection.recv(256).decode("utf-8", "strict").strip().split()
    if len(raw) == 2 and raw[0] == "restart" and raw[1] in ALLOWED:
        try:
            schedule_restart(raw[1])
            connection.sendall(("OK restart scheduled for " + raw[1] + "\n").encode())
        except subprocess.CalledProcessError:
            connection.sendall(b"ERROR systemd scheduling failed\n")
        return
    if len(raw) == 3 and raw[0] == "install" and PACKAGE_NAME.fullmatch(raw[1]) and raw[2] in {"0", "1"}:
        send_install_result(connection, *install_package(raw[1], raw[2] == "1"))
        return
    connection.sendall(b"ERROR unsupported service-control request\n")


def main() -> int:
    os.makedirs(SOCKET_DIR, mode=0o750, exist_ok=True)
    os.chown(SOCKET_DIR, 0, grp.getgrnam("ubuntu").gr_gid)
    try:
        os.unlink(SOCKET_PATH)
    except FileNotFoundError:
        pass
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
        listener.bind(SOCKET_PATH)
        os.chown(SOCKET_PATH, 0, grp.getgrnam("ubuntu").gr_gid)
        os.chmod(SOCKET_PATH, 0o660)
        listener.listen(16)
        while True:
            connection, _ = listener.accept()
            with connection:
                handle(connection)


if __name__ == "__main__":
    sys.exit(main())
