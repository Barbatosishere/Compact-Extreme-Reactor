import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from rcon import Rcon

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build/structure-gui-20261011"
OUT.mkdir(exist_ok=True)
JAVA = {
    "1.20.1": Path(os.environ.get("CER_JAVA17", str(Path.home() / ".gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin/java.exe"))),
    "1.21.1": Path(os.environ.get("CER_JAVA21", r"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\java.exe")),
}


class Server:
    def __init__(self, version, attempt):
        self.version = version
        self.output = OUT / (version + "-" + attempt)
        self.output.mkdir(exist_ok=False)
        self.directory = Path(tempfile.mkdtemp(prefix="cer-structure-" + version + "-"))
        (self.directory / "logs").mkdir()
        self.process = self.connection = self.log = None
        for name in ("eula.txt", "server.properties"):
            template = ROOT / "versions" / version / "build/global-stress-optimized-20261009-full" / name
            if template.exists():
                shutil.copyfile(template, self.directory / name)
            elif name == "eula.txt":
                (self.directory / name).write_text("eula=true\n")
            else:
                (self.directory / name).write_text(
                    "server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\nenable-rcon=true\n"
                    "rcon.port=25590\nrcon.password=cer-stress-isolated\nview-distance=2\nsimulation-distance=5\n"
                    "spawn-protection=0\nallow-flight=false\nenforce-secure-profile=false\ngamemode=survival\n")
        self.result = {"version": version, "runDirectory": str(self.directory)}

    def launch(self, label):
        moddev = ROOT / "versions" / self.version / "build/moddev"
        folders = ";".join(mod + "%%" + str(ROOT / "versions" / self.version / "build" / base / source)
                           for mod, source in (("compactextremereactor", "main"), ("cerstructure", "structureProbe"))
                           for base in ("classes/java", "resources"))
        environment = os.environ.copy()
        if self.version == "1.20.1":
            environment["MOD_CLASSES"] = folders
        for kind in ("Vm", "Program"):
            shutil.copyfile(moddev / ("serverRun" + kind + "Args.txt"), self.directory / (kind + ".txt"))
        cp = moddev / ("clientRunClasspath.txt" if self.version == "1.20.1" else "serverRunClasspath.txt")
        self.log = (self.output / (label + ".log")).open("w", encoding="utf-8")
        self.process = subprocess.Popen([str(JAVA[self.version]), "@" + str(cp), "@" + str(self.directory / "Vm.txt"),
                                         "-Xmx2G", "-Dfml.modFolders=" + folders, "net.neoforged.devlaunch.Main",
                                         "@" + str(self.directory / "Program.txt")], cwd=self.directory, env=environment,
                                        stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Server exited, see " + str(self.output / (label + ".log")))
            try:
                connection = Rcon()
                if "/cerstructure" in connection.command("help"):
                    self.connection = connection
                    break
                connection.close()
            except (OSError, EOFError):
                pass
            time.sleep(1)
        else:
            raise TimeoutError("Server startup")
        print(self.version + " server ready", flush=True)

    def command(self, command):
        result = self.connection.command(command)
        with (self.output / "commands.log").open("a", encoding="utf-8") as log:
            log.write(command + "\n" + result + "\n")
        return result

    def stop(self):
        if self.connection:
            try:
                self.connection.command("stop")
            except (EOFError, OSError):
                pass
            self.connection.close()
            self.connection = None
        if self.process:
            if self.process.poll() is None:
                self.process.stdin.write(b"stop\n")
                self.process.stdin.flush()
            self.process.wait(timeout=60)
            self.process = None
        if self.log:
            self.log.close()
            self.log = None

    def run(self):
        try:
            self.launch("server")
            self.command("forceload add 10000 10000")
            response = self.command("cerstructure")
            assert response.startswith("CERSTRUCTURE "), response
            self.result.update(json.loads(response.removeprefix("CERSTRUCTURE ")))
            self.command("cerstructure setup")
            time.sleep(1)
            before = json.loads(self.command("cerstructure snapshot").removeprefix("CERSTRUCTURE "))
            self.command("setworldspawn 0 80 0")
            self.command("forceload remove all")
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                runtime = json.loads(self.command("cerstructure runtime").removeprefix("CERSTRUCTURE "))
                if not any(runtime.values()):
                    break
                time.sleep(1)
            else:
                raise TimeoutError("Structure machine chunks did not unload")
            self.command("forceload add 10000 10000")
            time.sleep(4)
            self.result["checks"]["actual chunk reload keeps components and exact controller"] = json.loads(self.command("cerstructure snapshot").removeprefix("CERSTRUCTURE ")) == before
            self.command("save-all flush")
            self.stop()
            self.launch("restart")
            self.command("forceload add 10000 10000")
            time.sleep(4)
            self.result["checks"]["actual server restart keeps components and exact controller"] = json.loads(self.command("cerstructure snapshot").removeprefix("CERSTRUCTURE ")) == before
            self.result["passed"] = self.result["passed"] and all(self.result["checks"].values())
            self.result["sha256"] = {
                str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in (ROOT / "versions" / self.version / "build/libs").glob("compactextremereactor-*.jar")
            }
            print(self.version + " checks " + str(sum(self.result["checks"].values())) + "/" + str(len(self.result["checks"])),
                  self.result.get("error", ""), flush=True)
            return self.result["passed"]
        finally:
            self.stop()
            (self.output / "result.json").write_text(json.dumps(self.result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", choices=("1.20.1", "1.21.1", "all"), default="all")
    parser.add_argument("--attempt", default="run1")
    args = parser.parse_args()
    versions = ("1.20.1", "1.21.1") if args.version == "all" else (args.version,)
    passed = True
    for version in versions:
        passed = Server(version, args.attempt).run() and passed
    raise SystemExit(not passed)
