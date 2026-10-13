"""Run the existing full stress suites using freshly prepared runtime arguments."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from run_server import ROOT, JAVA

spec = importlib.util.spec_from_file_location("global_suite", ROOT / "build/global-stress-20261011/run_global.py")
global_suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(global_suite)
stress = global_suite.stress


class CurrentSuite(global_suite.GlobalSuite):
    def __init__(self, version, kind, attempt, output_root, world=None):
        self.kind = kind
        self.output = output_root / attempt / (version + "-" + kind)
        self.output.mkdir(parents=True, exist_ok=False)
        stress.OUT = self.output
        stress.Suite.__init__(self, version)
        self.run_dir = world or Path(tempfile.mkdtemp(prefix="cer-current-" + version + "-"))
        (self.run_dir / "logs").mkdir(exist_ok=True)
        if world is None:
            for name in ("eula.txt", "server.properties"):
                shutil.copyfile(ROOT / "versions" / version / "build/comprehensive-stress-20261009" / name,
                                self.run_dir / name)
        files = list((ROOT / "versions" / version / "build/libs").glob("compactextremereactor-*.jar"))
        files += list((ROOT / "src/main").rglob("*.java"))
        files += list((ROOT / "versions" / version / "src/main").rglob("*.java"))
        files += list((ROOT / "versions" / version / "build/classes/java/main").rglob("*.class"))
        files += list((ROOT / "versions" / version / "build/moddev").glob("server*.txt"))
        self.result["sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
        self.result["runDirectory"] = str(self.run_dir)
        self.result["measurement"] = "Original full suite durations and assertions; direct Java launch; fixed game ticks; no real clients."
        self.save()

    def save(self):
        target = self.output / (self.version + "-result.json")
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(json.dumps(self.result, indent=2), encoding="utf-8")
        temporary.replace(target)

    def run(self):
        try:
            if self.kind in ("restart", "entities", "drops"):
                (self.restart if self.kind == "restart" else self.entities)()
                self.result["result"] = "PASS"
                self.save()
            else:
                super().run()
        except BaseException as error:
            self.result["result"] = "FAIL"
            self.result["error"] = str(error)
            self.save()
            raise
        finally:
            self.stop()
            self.result["end"] = time.strftime("%Y-%m-%d %H:%M:%S")
            self.save()

    def command(self, command):
        self.connection.socket.settimeout(120 if command == "save-all flush" else 15)
        return super().command(command)

    def stop(self):
        if self.connection:
            self.connection.close()
            self.connection = None
        if self.server:
            if self.server.poll() is None:
                self.server.stdin.write(b"stop\n")
                self.server.stdin.flush()
            self.server.wait(timeout=180)
            code = self.server.returncode
            self.server = None
            if code:
                raise RuntimeError("Server exit code " + str(code))
        if self.log:
            self.log.close()
            self.log = None
        source = self.run_dir / "logs/safepoint.log"
        if source.exists():
            shutil.copyfile(source, self.output / (self.launch_label + "-safepoint.log"))

    def restart(self):
        self.launch("restart-recovery")
        self.probe("adopt 1000")
        self.probe("force true")
        time.sleep(6)
        ready = self.probe("verify")
        self.check("3000 machine restart initialization", ready, ready["missing"] == 0 and ready["failures"] == 0)
        restored = self.probe("compare")
        self.check("3000 machine restart exact resources", restored, restored["differences"] == 0 and restored["compared"] == 3000)
        self.probe("automation true")
        time.sleep(10)
        self.sample("production after recovered restart", 60)
        self.probe("automation false")
        self.probe("clear")
        self.wait_setup()
        self.command("forceload remove all")
        time.sleep(3)
        registry = self.probe("registry")
        self.check("registry after all machines removed", registry, registry["ticking"] == 0 and registry["pending"] == 0)
        self.result["finalStatus"] = self.probe("status")

    def entities(self):
        self.result["measurement"] = ("3000 active machines and ten replacement cycles with normal dropped items retained."
                                      if self.kind == "drops" else
                                      "Same 3000 active machines before and after removing dropped item entities in an isolated world.")
        self.launch("entities")
        self.probe("setup 1000")
        self.wait_setup()
        self.probe("automation true")
        time.sleep(15)
        self.sample("3000 machines before replacements", 30)
        for cycle in range(10):
            self.probe("churn")
            time.sleep(2)
            ready = self.probe("verify")
            self.check("entity comparison replacement cycle " + str(cycle + 1), ready,
                       ready["missing"] == 0 and ready["failures"] == 0)
        self.result["itemsBefore"] = self.command("execute if entity @e[type=minecraft:item]")
        self.sample("3000 machines after replacements with dropped items", 30)
        self.result["itemsAfterSample"] = self.command("execute if entity @e[type=minecraft:item]")
        if self.kind == "entities":
            self.result["itemsRemoved"] = self.command("kill @e[type=minecraft:item]")
            after = self.command("execute if entity @e[type=minecraft:item]")
            self.check("dropped item entities cleared for comparison", {"response": after}, "Test failed" in after)
            self.sample("same 3000 machines after dropped item cleanup", 30)
        self.result["finalStatus"] = self.probe("status")

    def launch(self, label):
        self.launch_label = label
        moddev = ROOT / "versions" / self.version / "build/moddev"
        folders = ";".join(mod + "%%" + str(ROOT / "versions" / self.version / "build" / base / source)
                           for mod, source in (("compactextremereactor", "main"), ("cerstress", "stressProbe"))
                           for base in ("classes/java", "resources"))
        environment = os.environ.copy()
        if self.version == "1.20.1":
            environment["MOD_CLASSES"] = folders
        for kind in ("Vm", "Program"):
            shutil.copyfile(moddev / ("serverRun" + kind + "Args.txt"), self.run_dir / (kind + ".txt"))
        cp = moddev / ("clientRunClasspath.txt" if self.version == "1.20.1" else "serverRunClasspath.txt")
        self.log = (self.output / (label + "-server.log")).open("w", encoding="utf-8")
        self.server = subprocess.Popen([str(JAVA[self.version]), "@" + str(cp), "@" + str(self.run_dir / "Vm.txt"),
                                        "-Xmx2G", "-Dfml.modFolders=" + folders, "net.neoforged.devlaunch.Main",
                                        "@" + str(self.run_dir / "Program.txt")], cwd=self.run_dir, env=environment,
                                       stdout=self.log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
        self.message("Starting current " + self.kind + " server: " + label)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                raise RuntimeError("Server exited; see " + str(self.output / (label + "-server.log")))
            try:
                connection = stress.Rcon()
                if "CERSTRESS " in connection.command("cerstress status"):
                    self.connection = connection
                    break
                connection.close()
            except (OSError, EOFError):
                pass
            time.sleep(1)
        else:
            raise TimeoutError("Server startup")
        for rule in ("doMobSpawning", "doDaylightCycle", "logAdminCommands", "doWeatherCycle"):
            self.command("gamerule " + rule + " false")
        self.result.setdefault("launches", []).append({"label": label, "status": self.probe("status")})
        self.save()
        self.message("Server ready")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", choices=("1.20.1", "1.21.1", "all"), default="all")
    parser.add_argument("--kind", choices=("full", "soak", "restart", "entities", "drops"), default="full")
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--output-root", type=Path, default=ROOT / "build")
    parser.add_argument("--world", type=Path)
    args = parser.parse_args()
    if args.kind == "restart" and (args.world is None or args.version == "all"):
        parser.error("restart requires --world and one --version")
    for version in (("1.20.1", "1.21.1") if args.version == "all" else (args.version,)):
        CurrentSuite(version, args.kind, args.attempt, args.output_root, args.world).run()
