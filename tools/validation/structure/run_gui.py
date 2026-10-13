import argparse
import json
import os
from pathlib import Path
import subprocess
import time
from zipfile import ZipFile

from run_server import ROOT, JAVA, Server


class Gui(Server):
    def __init__(self, version, attempt, offline_address_check=False):
        super().__init__(version, attempt)
        self.offline_address_check = offline_address_check
        self.result["offlineAddressCheck"] = offline_address_check
        properties = (self.directory / "server.properties").read_text()
        properties = properties.replace("server-port=0", "server-port=25591").replace("allow-flight=false", "allow-flight=true")
        properties = properties.replace("enforce-secure-profile=true", "enforce-secure-profile=false")
        (self.directory / "server.properties").write_text(properties)
        self.client = self.client_log = None
        self.client_id = 0
        self.client_out = self.output / "client"
        self.client_out.mkdir()

    def status(self):
        try:
            return json.loads((self.client_out / "status.json").read_text())
        except (FileNotFoundError, json.JSONDecodeError, PermissionError):
            return {}

    def wait(self, predicate, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.client.poll() is not None:
                raise RuntimeError("Graphics client exited")
            status = self.status()
            if predicate(status):
                return status
            time.sleep(.1)
        raise TimeoutError(str(self.status()))

    def action(self, action, **args):
        self.client_id += 1
        temporary = self.client_out / "command.tmp"
        temporary.write_text(json.dumps({"id": self.client_id, "action": action, **args}))
        temporary.replace(self.client_out / "command.json")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                reply = json.loads((self.client_out / "reply.json").read_text())
            except (FileNotFoundError, json.JSONDecodeError, PermissionError):
                reply = {}
            if reply.get("id") == self.client_id:
                assert reply["success"], reply
                return reply
            time.sleep(.1)
        raise TimeoutError(action)

    def launch_client(self):
        directory = self.directory / "client"
        directory.mkdir()
        (directory / "options.txt").write_text("enableVsync:false\nmaxFps:120\nguiScale:2\nlang:zh_cn\n"
                                               "renderDistance:4\nsimulationDistance:5\nmasterVolume:0.0\n"
                                               "pauseOnLostFocus:false\ntutorialStep:none\nonboardAccessibility:false\n")
        moddev = ROOT / "versions" / self.version / "build/moddev"
        folders = ";".join(mod + "%%" + str(ROOT / "versions" / self.version / "build" / base / source)
                           for mod, source in (("compactextremereactor", "main"), ("cerstructure", "structureProbe"))
                           for base in ("classes/java", "resources"))
        environment = os.environ.copy()
        if self.version == "1.20.1":
            environment["MOD_CLASSES"] = folders
        (directory / "vm.txt").write_text((moddev / "clientRunVmArgs.txt").read_text())
        (directory / "program.txt").write_text((moddev / "clientRunProgramArgs.txt").read_text() +
                                              "\n--username\nCERStructure\n--width\n1280\n--height\n720\n"
                                              "--quickPlayMultiplayer\n127.0.0.1:25591\n")
        self.client_log = (self.output / "client.log").open("w", encoding="utf-8")
        classpath = moddev / "clientRunClasspath.txt"
        if self.offline_address_check:
            arguments = classpath.read_text().splitlines()
            assert arguments[0] == "-classpath"
            entries = json.loads(arguments[1]).split(";")
            original = next(entry for entry in entries if Path(entry).name.startswith("patchy-"))
            local = self.output / Path(original).name
            service = "META-INF/services/com.mojang.blocklist.BlockListSupplier"
            with ZipFile(original) as source, ZipFile(local, "w") as target:
                assert service in source.namelist()
                for entry in source.infolist():
                    if entry.filename != service:
                        target.writestr(entry, source.read(entry.filename))
            entries[entries.index(original)] = str(local)
            classpath = directory / "offline-classpath.txt"
            classpath.write_text("-classpath\n" + json.dumps(";".join(entries)) + "\n")
        self.client = subprocess.Popen([str(JAVA[self.version]), "@" + str(classpath),
                                       "@" + str(directory / "vm.txt"), "-Xmx2G", "-Dfml.modFolders=" + folders,
                                       "-Dcer.structure.output=" + str(self.client_out),
                                       "-Dsun.net.client.defaultConnectTimeout=10000",
                                       "-Dsun.net.client.defaultReadTimeout=10000", "net.neoforged.devlaunch.Main",
                                       "@" + str(directory / "program.txt")], cwd=directory, env=environment,
                                      stdout=self.client_log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        self.wait(lambda s: s.get("connected"), 600)
        print(self.version + " graphics client connected", flush=True)

    def run(self):
        self.result["checks"] = {}
        try:
            self.launch("server")
            self.command("gamerule doMobSpawning false")
            self.command("forceload add 10000 10000")
            self.command("fill 9996 99 9996 10008 99 10008 minecraft:stone")
            self.command("setworldspawn 10001 100 9998")
            self.command("cerstructure setup")
            self.launch_client()
            for kind, x, shell in (("reactor", 10000, "basic_reactorcasing"),
                                   ("turbine", 10001, "basic_turbinecasing"),
                                   ("fluidizer", 10002, "fluidizercasing")):
                self.command("clear CERStructure")
                self.command("gamemode survival CERStructure")
                self.command("tp CERStructure " + str(x + .5) + " 100 9998.5")
                time.sleep(1)
                self.action("open", x=x)
                self.wait(lambda s: s.get("screen") == "Compact" + kind.title() + "Screen")
                self.action("page", button=100)
                self.wait(lambda s: s.get("screen") == "CompactStructureScreen" and s.get("kind") == kind and s.get("scale") == 200000)
                self.action("resize", width=1280, height=720)
                time.sleep(1)
                self.action("clear_toasts")
                time.sleep(.3)
                self.action("screenshot", name=kind + "-desktop.png")
                self.action("resize", width=854, height=480)
                time.sleep(1)
                self.action("clear_toasts")
                time.sleep(.3)
                self.action("screenshot", name=kind + "-small.png")
                self.result["checks"][kind + " desktop and small layout"] = self.status()["width"] >= 342 and self.status()["height"] >= 230
                counts = self.status()["counts"]
                self.command("give CERStructure bigreactors:" + shell + " 64")
                time.sleep(.5)
                self.action("click", slot=43, button=0, type="QUICK_MOVE")
                self.wait(lambda s: s.get("counts", [0])[0] == counts[0] + 64)
                self.action("click", slot=0, button=0, type="QUICK_MOVE")
                self.wait(lambda s: s.get("counts", [0])[0] == counts[0])
                self.result["checks"][kind + " real network shift insertion and extraction"] = True
                self.action("page", button=0)
                self.wait(lambda s: s.get("screen") == "Compact" + kind.title() + "Screen")
                self.result["checks"][kind + " navigation returns to correct machine"] = True
                self.action("page", button=100)
                self.wait(lambda s: s.get("screen") == "CompactStructureScreen")
                self.command("tp CERStructure 0 100 0")
                self.wait(lambda s: s.get("screen") != "CompactStructureScreen")
                self.result["checks"][kind + " distant player menu closes"] = True
                self.action("close")
            self.result["passed"] = all(self.result["checks"].values())
            print(self.version + " GUI checks " + str(len(self.result["checks"])) + " PASS", flush=True)
            return self.result["passed"]
        except BaseException as error:
            self.result["passed"] = False
            self.result["error"] = str(error)
            raise
        finally:
            if self.client:
                if self.client.poll() is None:
                    self.action("stop")
                self.client.wait(timeout=60)
            if self.client_log:
                self.client_log.close()
            self.stop()
            (self.output / "result.json").write_text(json.dumps(self.result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", choices=("1.20.1", "1.21.1", "all"), default="all")
    parser.add_argument("--attempt", default="gui1")
    parser.add_argument("--offline-address-check", action="store_true",
                        help="Use a test-only Patchy copy without its remote blocklist provider for localhost validation.")
    args = parser.parse_args()
    versions = ("1.20.1", "1.21.1") if args.version == "all" else (args.version,)
    passed = True
    for version in versions:
        passed = Gui(version, args.attempt, args.offline_address_check).run() and passed
    raise SystemExit(not passed)
