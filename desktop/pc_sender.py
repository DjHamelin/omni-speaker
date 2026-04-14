#!/usr/bin/env python3
"""
Stream microphone audio to Mesh Audio (Android) in *speaker* mode.

Runs a TCP server compatible with the app's AudioStreamClient: same MAU1
header, 44.1 kHz mono s16le PCM. On each phone, choose Speaker and connect
to this computer's IP and port (or rely on mDNS if --mdns is used).

Examples:
  python pc_sender.py
  python pc_sender.py --port 7878 --device 2
  python pc_sender.py --list-devices
  python pc_sender.py --mdns
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import threading
import time
from typing import List, Optional

MAGIC = b"MAU1"
SAMPLE_RATE = 44100
CHANNELS = 1
# ~20 ms @ 44.1 kHz (matches typical mobile chunk sizes)
BLOCK_FRAMES = 882


def make_header(seq: int, payload_len: int) -> bytes:
    return MAGIC + struct.pack(">ii", seq, payload_len)


def guess_primary_ipv4() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def register_mdns(port: int, name: str) -> Optional[object]:
    try:
        from zeroconf import ServiceInfo, Zeroconf
    except ImportError:
        print(
            "mDNS requires: pip install zeroconf",
            file=sys.stderr,
        )
        return None

    ip = guess_primary_ipv4()
    try:
        zc = Zeroconf()
        info = ServiceInfo(
            "_meshaudio._tcp.local.",
            f"{name}._meshaudio._tcp.local.",
            addresses=[socket.inet_aton(ip)],
            port=port,
            properties={},
            server="mesh-desktop.local.",
        )
        zc.register_service(info)
        print(f"Registered mDNS _meshaudio._tcp at {ip}:{port}", file=sys.stderr)

        class _Holder:
            def close(self) -> None:
                try:
                    zc.unregister_service(info)
                    zc.close()
                except Exception:
                    pass

        return _Holder()
    except Exception as e:
        print(f"mDNS registration failed: {e}", file=sys.stderr)
        return None


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Desktop audio sender for Mesh Audio (TCP server, MAU1 PCM).",
    )
    ap.add_argument(
        "--port",
        "-p",
        type=int,
        default=7878,
        help="Listen port (default 7878, same as the Android app)",
    )
    ap.add_argument(
        "--bind",
        default="0.0.0.0",
        help="Bind address (default all interfaces)",
    )
    ap.add_argument(
        "--device",
        "-d",
        type=int,
        default=None,
        help="sounddevice input device index (see --list-devices)",
    )
    ap.add_argument(
        "--list-devices",
        action="store_true",
        help="List input devices and exit",
    )
    ap.add_argument(
        "--mdns",
        action="store_true",
        help="Advertise as _meshaudio._tcp (requires zeroconf package)",
    )
    ap.add_argument(
        "--mdns-name",
        default="MeshDesktop",
        help="mDNS service instance name (default MeshDesktop)",
    )
    args = ap.parse_args()

    try:
        import numpy as np
        import sounddevice as sd
    except ImportError:
        print("Install dependencies: pip install -r requirements.txt", file=sys.stderr)
        return 1

    if args.list_devices:
        print(sd.query_devices())
        return 0

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind((args.bind, args.port))
    except OSError as e:
        if args.port != 0:
            print(
                f"Port {args.port} busy ({e}); trying ephemeral port…",
                file=sys.stderr,
            )
            try:
                server.bind((args.bind, 0))
            except OSError as e2:
                print(f"Bind failed: {e2}", file=sys.stderr)
                return 1
        else:
            print(f"Bind failed ({args.bind}:{args.port}): {e}", file=sys.stderr)
            return 1
    server.listen(8)
    server.settimeout(0.5)
    actual_port = server.getsockname()[1]

    clients: List[socket.socket] = []
    clients_lock = threading.Lock()
    running = threading.Event()
    running.set()

    def accept_loop() -> None:
        while running.is_set():
            try:
                conn, addr = server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass

            def drain(c: socket.socket, peer: str) -> None:
                try:
                    while running.is_set():
                        try:
                            chunk = c.recv(4096)
                            if not chunk:
                                break
                        except OSError:
                            break
                finally:
                    try:
                        c.close()
                    except OSError:
                        pass
                    with clients_lock:
                        if c in clients:
                            clients.remove(c)
                    print(f"Speaker disconnected {peer}", file=sys.stderr)

            with clients_lock:
                clients.append(conn)
            print(f"Speaker connected {addr}", file=sys.stderr)
            threading.Thread(
                target=drain,
                args=(conn, str(addr)),
                daemon=True,
            ).start()

    accept_thread = threading.Thread(target=accept_loop, daemon=True)
    accept_thread.start()

    mdns_holder = None
    if args.mdns:
        mdns_holder = register_mdns(actual_port, args.mdns_name)

    local_ip = guess_primary_ipv4()
    print(
        f"Listening on {args.bind}:{actual_port} — on Android (Speaker), "
        f"connect to {local_ip}:{actual_port}",
        file=sys.stderr,
    )
    print("Ctrl+C to stop.", file=sys.stderr)

    seq = 0
    try:
        while running.is_set():
            chunk = sd.rec(
                BLOCK_FRAMES,
                samplerate=SAMPLE_RATE,
                channels=CHANNELS,
                dtype="int16",
                device=args.device,
            )
            sd.wait()
            payload = np.ascontiguousarray(chunk).tobytes()
            if len(payload) != BLOCK_FRAMES * 2 * CHANNELS:
                continue
            header = make_header(seq, len(payload))
            seq = (seq + 1) & 0x7FFFFFFF
            with clients_lock:
                dead: List[socket.socket] = []
                for c in clients:
                    try:
                        c.sendall(header + payload)
                    except OSError:
                        dead.append(c)
                for c in dead:
                    try:
                        c.close()
                    except OSError:
                        pass
                    clients.remove(c)
    except KeyboardInterrupt:
        print("\nStopping…", file=sys.stderr)
    finally:
        running.clear()
        try:
            server.close()
        except OSError:
            pass
        with clients_lock:
            for c in clients:
                try:
                    c.close()
                except OSError:
                    pass
            clients.clear()
        if mdns_holder is not None:
            mdns_holder.close()
        time.sleep(0.2)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
