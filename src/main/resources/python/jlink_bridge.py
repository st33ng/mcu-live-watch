#!/usr/bin/env python3
"""J-Link memory bridge for the MCU Live Watch CLion plugin.

Speaks the line protocol the plugin expects over TCP:

    put;NAME;SIZE;0xADDR;HZ;TYPE      register a variable
    deleteVariable;NAME               unregister a variable
    setVariableReadFrq;NAME;HZ        change a variable's sampling rate
    setSpeed;KHZ                      change the J-Link interface speed
    getMinMaxJLinkSpeed               report the current speed
    start                             start streaming
    stop                              stop streaming

    -> NAME;Data:VALUE              one sample
    -> OK: ... / ERROR: ...         command replies

Differences from the original sockets_var.py:

  * One scheduler thread instead of a threading.Timer per variable per sample. The old code created
    a fresh OS thread for every reading of every variable -- at 1 kHz with 8 variables that is 8000
    thread creations per second, which is why the plugin pegged a core.
  * Adjacent variables are read in one memory_read() call, so the J-Link round trip is amortised.
  * sendall() is serialised with a lock: samples are produced on the scheduler thread while replies
    are produced on the client thread, and interleaved writes used to corrupt lines.
  * Connects once and stays connected while streaming; the original opened/connected/closed on
    every single getData command.
  * Unknown DWARF type names fall back to a size-based interpretation instead of raising, so a
    variable whose type is "N/A" or a struct name still produces samples.
  * Device name, interface, speed, host and port come from arguments instead of being hardcoded.

Requires: pip install pylink-square
"""

from __future__ import annotations

import argparse
import atexit
import socket
import struct
import sys
import threading
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

try:
    import pylink
except ImportError:  # pragma: no cover - reported at startup
    sys.stderr.write(
        "pylink is not installed for this interpreter. Run: pip install pylink-square\n"
    )
    raise


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 12345

# Batching limits for memory_read(): merge variables whose ranges are close together.
BATCH_MAX_GAP = 8
BATCH_MAX_SPAN = 64

SIGNED_FORMATS = {1: "<b", 2: "<h", 4: "<i", 8: "<q"}
UNSIGNED_FORMATS = {1: "<B", 2: "<H", 4: "<I", 8: "<Q"}

FLOAT_TYPES = {"float", "float32", "float32_t"}
DOUBLE_TYPES = {"double", "float64", "float64_t", "long double"}
BOOL_TYPES = {"bool", "_Bool", "boolean"}
SIGNED_HINTS = ("int8_t", "int16_t", "int32_t", "int64_t", "int", "ssize_t", "ptrdiff_t")


def unpack_value(raw: bytes, size: int, value_type: str = "") -> object:
    """Converts raw little-endian bytes to a Python value.

    The DWARF type name is only a hint: the parser emits names such as ``N/A``, ``TIM_TypeDef`` or
    ``struct Foo`` that carry no numeric meaning. Those fall back to a size-based interpretation so
    the variable still reports a value instead of failing on every sample.
    """
    data = bytes(raw)
    name = (value_type or "").strip().lower()

    if size == 4 and name in FLOAT_TYPES:
        return struct.unpack("<f", data)[0]
    if size == 8 and name in DOUBLE_TYPES:
        return struct.unpack("<d", data)[0]
    if name in BOOL_TYPES:
        return bool(struct.unpack(UNSIGNED_FORMATS[min(size, 8)], data[: min(size, 8)])[0])

    fmt_table = UNSIGNED_FORMATS
    if any(hint in name for hint in SIGNED_HINTS) and not name.startswith("u"):
        fmt_table = SIGNED_FORMATS

    fmt = fmt_table.get(size)
    if fmt is None:
        # Arrays and structs: report the first 8 bytes as an unsigned integer.
        size = 8 if size >= 8 else (4 if size >= 4 else (2 if size >= 2 else 1))
        fmt = UNSIGNED_FORMATS[size]

    return struct.unpack(fmt, data[:size])[0]


@dataclass
class Variable:
    name: str
    address: int
    size: int
    hz: int
    type: str
    next_due: float = 0.0

    @property
    def period(self) -> float:
        return 1.0 / self.hz if self.hz > 0 else 1.0

    @property
    def end(self) -> int:
        return self.address + self.size


@dataclass
class Batch:
    start: int
    end: int
    members: List[Variable] = field(default_factory=list)


def build_batches(variables: List[Variable]) -> List[Batch]:
    """Groups variables into memory ranges that can be read with one memory_read() call."""
    if not variables:
        return []

    ordered = sorted(variables, key=lambda v: v.address)
    batches: List[Batch] = []
    current = Batch(start=ordered[0].address, end=ordered[0].end, members=[ordered[0]])

    for var in ordered[1:]:
        gap = var.address - current.end
        span = max(current.end, var.end) - current.start
        if gap <= BATCH_MAX_GAP and span <= BATCH_MAX_SPAN:
            current.end = max(current.end, var.end)
            current.members.append(var)
        else:
            batches.append(current)
            current = Batch(start=var.address, end=var.end, members=[var])

    batches.append(current)
    return batches


class BridgeServer:
    def __init__(self, device: str, host: str, port: int, speed_khz: Optional[int]) -> None:
        self.device = device
        self.speed_khz = speed_khz

        self._variables: Dict[str, Variable] = {}
        self._variables_lock = threading.Lock()

        self._send_lock = threading.Lock()
        self._connection: Optional[socket.socket] = None

        self._reading_active = False
        self._shutdown = threading.Event()
        self._jlink = pylink.JLink()
        self._jlink_lock = threading.Lock()

        self._server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server.bind((host, port))
        self._server.listen(1)
        print(f"J-Link bridge on {host}:{port}, device {device}, waiting for connection...", flush=True)

        self._scheduler = threading.Thread(target=self._scheduler_loop, name="jlink-scheduler", daemon=True)
        self._scheduler.start()

        atexit.register(self.cleanup)

    # ---------------------------------------------------------------- jlink

    def _ensure_connected(self) -> bool:
        with self._jlink_lock:
            try:
                if not self._jlink.opened():
                    self._jlink.open()

                # `connected()` reports that a *probe* is attached over USB — it is already True
                # after `open()` and says nothing about the MCU link. Gating on it skipped the
                # `connect()` call entirely, so every later `memory_read()` raised
                # "Target is not connected" and not a single sample was ever produced.
                # `target_connected()` is the real condition.
                if not self._jlink.target_connected():
                    # The interface must be selected *before* connecting: pylink's `connect()`
                    # issues `Device = <name>` and an implicit JLINKARM_Connect, so a `set_tif`
                    # afterwards would be too late.
                    try:
                        self._jlink.set_tif(pylink.enums.JLinkInterfaces.SWD)
                    except Exception:
                        pass
                    self._jlink.connect(self.device)
                    if self.speed_khz:
                        self._jlink.set_speed(int(self.speed_khz))
                    else:
                        self._jlink.set_max_speed()

                return self._jlink.target_connected()
            except Exception as exc:
                print(f"Cannot connect J-Link: {exc}", file=sys.stderr, flush=True)
                return False

    def _read_region(self, start: int, length: int) -> Optional[bytes]:
        with self._jlink_lock:
            try:
                if not self._jlink.target_connected():
                    return None
                raw = self._jlink.memory_read(start, length)
                return bytes(bytearray(raw))
            except Exception as exc:
                print(f"memory_read({hex(start)}, {length}) failed: {exc}", file=sys.stderr, flush=True)
                return None

    # ------------------------------------------------------------- scheduler

    def _scheduler_loop(self) -> None:
        while not self._shutdown.is_set():
            if not self._reading_active:
                self._shutdown.wait(0.05)
                continue

            with self._variables_lock:
                variables = list(self._variables.values())

            if not variables:
                self._shutdown.wait(0.05)
                continue

            now = _monotonic()
            due = [v for v in variables if v.next_due <= now]
            if not due:
                wait = min(v.next_due for v in variables) - now
                self._shutdown.wait(max(0.0005, min(wait, 0.05)))
                continue

            self._sample(due)

            now = _monotonic()
            with self._variables_lock:
                for var in due:
                    live = self._variables.get(var.name)
                    if live is not None:
                        # Catch up without drifting, but never schedule in the past.
                        live.next_due = max(now, live.next_due) + live.period

    def _sample(self, due: List[Variable]) -> None:
        for batch in build_batches(due):
            data = self._read_region(batch.start, batch.end - batch.start)
            if data is None:
                continue
            for var in batch.members:
                offset = var.address - batch.start
                chunk = data[offset:offset + var.size]
                if len(chunk) < var.size:
                    continue
                try:
                    value = unpack_value(chunk, var.size, var.type)
                except Exception as exc:
                    print(f"Decode failed for {var.name} ({var.type}, {var.size}B): {exc}",
                          file=sys.stderr, flush=True)
                    continue
                self.send(f"{var.name};Data:{value}\n")

    # -------------------------------------------------------------- commands

    def put(self, parts: List[str]) -> None:
        if len(parts) < 5:
            self.send("ERROR: put needs name;size;address;hz[;type]\n")
            return
        try:
            name = parts[1]
            size = int(parts[2])
            address = int(parts[3], 16)
            hz = int(parts[4])
        except ValueError:
            self.send("ERROR: invalid put argument\n")
            return
        value_type = parts[5] if len(parts) > 5 else ""

        var = Variable(name=name, address=address, size=size, hz=max(hz, 1), type=value_type)
        with self._variables_lock:
            self._variables[name] = var

        print(f"Registered {name} at {hex(address)} size {size} type {value_type} hz {hz}", flush=True)
        self.send(f"OK: {name} at {hex(address)} size {size} type {value_type}\n")

    def delete_variable(self, parts: List[str]) -> None:
        if len(parts) != 2:
            self.send("ERROR: deleteVariable needs a name\n")
            return
        with self._variables_lock:
            removed = self._variables.pop(parts[1], None)
        if removed is None:
            self.send(f"ERROR: Variable {parts[1]} not found\n")
        else:
            self.send(f"OK: Variable {parts[1]} deleted\n")

    def set_variable_rate(self, parts: List[str]) -> None:
        if len(parts) != 3:
            self.send("ERROR: setVariableReadFrq needs name;hz\n")
            return
        try:
            hz = int(parts[2])
        except ValueError:
            self.send("ERROR: invalid frequency\n")
            return
        with self._variables_lock:
            var = self._variables.get(parts[1])
            if var is not None:
                var.hz = max(hz, 1)
        if var is None:
            self.send(f"ERROR: Unknown variable {parts[1]}\n")
            return
        self.send(f"OK: {parts[1]} frq:{hz}\n")

    def set_speed(self, parts: List[str]) -> None:
        if len(parts) < 2:
            self.send("ERROR: setSpeed needs kHz\n")
            return
        try:
            khz = int(parts[1])
        except ValueError:
            self.send("ERROR: invalid speed\n")
            return
        with self._jlink_lock:
            try:
                if not self._jlink.opened():
                    self._jlink.open()
                self._jlink.set_speed(khz)
                self.speed_khz = khz
                self.send(f"OK: speed {khz} kHz\n")
            except Exception as exc:
                self.send(f"ERROR: {exc}\n")

    def report_speed(self) -> None:
        with self._jlink_lock:
            try:
                if not self._jlink.opened():
                    self._jlink.open()
                info = str(self._jlink.speed_info)
                start = info.find("=") + 1
                end = info.find("Hz")
                self.send(info[start:end].strip() + "\n")
            except Exception as exc:
                self.send(f"ERROR: {exc}\n")

    def start_read(self) -> None:
        if not self._ensure_connected():
            self.send("ERROR: Failed to start reading (J-Link not connected)\n")
            return
        now = _monotonic()
        with self._variables_lock:
            for var in self._variables.values():
                var.next_due = now
        self._reading_active = True
        print("Start reading variables", flush=True)
        self.send("OK: Start reading\n")

    def stop_read(self) -> None:
        self._reading_active = False
        print("Stop reading variables", flush=True)
        self.send("OK: Stop reading\n")
    def execute(self, parts: List[str]) -> None:
        command = parts[0]
        if command == "put":
            self.put(parts)
        elif command == "deleteVariable":
            self.delete_variable(parts)
        elif command == "setVariableReadFrq":
            self.set_variable_rate(parts)
        elif command == "setSpeed":
            self.set_speed(parts)
        elif command == "getMinMaxJLinkSpeed":
            self.report_speed()
        elif command == "getData":
            self.get_data(parts)
        elif command == "start":
            self.start_read()
        elif command == "stop":
            self.stop_read()
        else:
            self.send(f"ERROR: Unknown command {command}\n")

    def get_data(self, parts: List[str]) -> None:
        if len(parts) != 2:
            self.send("ERROR: getData needs a name\n")
            return
        with self._variables_lock:
            var = self._variables.get(parts[1])
        if var is None:
            self.send(f"ERROR: Variable {parts[1]} not found\n")
            return
        if not self._ensure_connected():
            self.send("ERROR: J-Link not connected\n")
            return
        data = self._read_region(var.address, var.size)
        if data is None:
            self.send(f"ERROR: Failed to read {parts[1]}\n")
            return
        try:
            self.send(f"{var.name};Data:{unpack_value(data, var.size, var.type)}\n")
        except Exception as exc:
            self.send(f"ERROR: {exc}\n")

    # ---------------------------------------------------------------- socket

    def send(self, message: str) -> None:
        connection = self._connection
        if connection is None:
            return
        with self._send_lock:
            try:
                connection.sendall(message.encode("utf-8"))
            except (BrokenPipeError, ConnectionResetError, OSError):
                print("Client disconnected during send", file=sys.stderr, flush=True)

    def serve_forever(self) -> None:
        try:
            connection, address = self._server.accept()
        except OSError:
            return

        self._connection = connection
        print(f"Connected by {address}", flush=True)

        buffer = ""
        try:
            while not self._shutdown.is_set():
                chunk = connection.recv(4096)
                if not chunk:
                    print("Client disconnected.", flush=True)
                    break
                buffer += chunk.decode("utf-8", errors="replace")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    text = line.strip()
                    if not text:
                        continue
                    try:
                        self.execute(text.split(";"))
                    except Exception as exc:
                        self.send(f"ERROR: {exc}\n")
        finally:
            self.cleanup()

    def cleanup(self) -> None:
        if self._shutdown.is_set():
            return
        self._shutdown.set()
        self._reading_active = False

        with self._send_lock:
            if self._connection is not None:
                try:
                    self._connection.close()
                except OSError:
                    pass
                self._connection = None

        try:
            self._server.close()
        except OSError:
            pass

        with self._jlink_lock:
            if self._jlink.opened():
                try:
                    self._jlink.close()
                except Exception:
                    pass
        print("Bridge stopped.", flush=True)


def _monotonic() -> float:
    import time

    return time.monotonic()


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="J-Link memory bridge for MCU Live Watch")
    parser.add_argument("--device", required=True, help="J-Link device name (never guessed)")
    parser.add_argument("--host", default=DEFAULT_HOST, help="address to bind")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port to listen on")
    parser.add_argument("--speed", type=int, default=None, metavar="KHZ", help="J-Link speed in kHz")
    args = parser.parse_args(argv)

    server = BridgeServer(args.device, args.host, args.port, args.speed)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nInterrupted by user", flush=True)
    except Exception as exc:
        print(f"Fatal error: {exc}", file=sys.stderr, flush=True)
        return 1
    finally:
        server.cleanup()
    return 0


if __name__ == "__main__":
    sys.exit(main())
