import socket
import struct


class Rcon:
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", 25590), timeout=5)
        self.socket.settimeout(120)
        self.request = 1
        self.send(1, 3, "cer-stress-isolated")
        request, kind, _ = self.read()
        if request != 1 or kind != 2:
            raise RuntimeError("RCON authentication failed")

    def send(self, request, kind, text):
        payload = struct.pack("<ii", request, kind) + text.encode("utf-8") + b"\0\0"
        self.socket.sendall(struct.pack("<i", len(payload)) + payload)

    def exact(self, count):
        data = bytearray()
        while len(data) < count:
            chunk = self.socket.recv(count - len(data))
            if not chunk:
                raise EOFError("RCON disconnected")
            data.extend(chunk)
        return bytes(data)

    def read(self):
        size = struct.unpack("<i", self.exact(4))[0]
        if not 10 <= size <= 65536:
            raise RuntimeError("Invalid RCON packet length")
        data = self.exact(size)
        return (*struct.unpack("<ii", data[:8]), data[8:-2].decode("utf-8"))

    def command(self, text):
        self.request += 1
        command_id = self.request
        self.send(command_id, 2, text)
        request, kind, body = self.read()
        if request != command_id or kind != 0:
            raise RuntimeError("Unexpected RCON response")
        result = [body]
        # Wait for the first reply before sending the delimiter; legacy Minecraft reads one request per socket read.
        self.request += 1
        delimiter = self.request
        self.send(delimiter, 0, "")
        while True:
            request, kind, body = self.read()
            if request == delimiter:
                if body != "Unknown request 0":
                    raise RuntimeError("Invalid RCON delimiter")
                return "".join(result).strip()
            if request != command_id or kind != 0:
                raise RuntimeError("Unexpected RCON response")
            result.append(body)

    def close(self):
        self.socket.close()
