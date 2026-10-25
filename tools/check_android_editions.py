"""Check Android edition boundaries without invoking Gradle, a compiler or a device."""
from collections import defaultdict
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/mobile/src"
ANDROID = "{http://schemas.android.com/apk/res/android}"
EXPECTED_IDS = {"full": "com.chuckiehelper.mobile", "phone": "com.chuckiehelper.phonecontrol"}
DECLARATIONS = re.compile(
    r"(?m)^(?:@\w+(?:\([^\n]*?\))?\s+)*(?P<modifiers>(?:(?:internal|private|public|data|sealed|abstract|open|enum|value|annotation|suspend|inline|tailrec|final)\s+)*)"
    r"(?P<kind>class|object|interface|fun)\s+(?:<[^>]+>\s*)?(?:[\w?<>]+\.)?(?P<name>\w+)"
)
LEXICAL = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')


def read(path):
    return path.read_text(encoding="utf-8-sig")


def sources(source_set):
    directory = SRC / source_set / "java"
    return sorted(path for path in directory.rglob("*") if path.suffix in {".kt", ".java"})


def definitions(paths):
    result = defaultdict(set)
    for path in paths:
        for match in DECLARATIONS.finditer(read(path)):
            result[match["name"]].add(path)
    return result


def check():
    errors = []
    common = sources("main")
    full = sources("full")
    phone = sources("phone")
    full_symbols = definitions(full)
    phone_symbols = definitions(common + phone)
    full_only = full_symbols.keys() - phone_symbols.keys()
    # Names are conservative reference checks, not a replacement for Kotlin type checking.
    for path in common + phone + sources("test") + sources("testPhone"):
        code = LEXICAL.sub(" ", read(path))
        for name in sorted(full_only):
            if re.search(r"\b" + re.escape(name) + r"\b", code):
                errors.append(f"{path.relative_to(ROOT)} references full-only symbol {name}")
        if re.search(r"(?m)^import (?:coil\.|io\.noties\.markwon\.|androidx\.media3\.)", code):
            errors.append(f"{path.relative_to(ROOT)} imports a full-only dependency")
    gradle = read(ROOT / "app/mobile/build.gradle")
    dependencies = read(ROOT / "app/shared-dependencies.gradle")
    settings = read(ROOT / "app/settings.gradle")
    if "include ':mobile'" not in settings or "include ':phonecontrol'" in settings:
        errors.append("Expected a single :mobile application module")
    for directory in (ROOT / "app/phonecontrol", ROOT / "app/phone-shared"):
        if (directory / "build.gradle").exists() or any((directory / "src").rglob("*.kt")):
            errors.append(f"Obsolete duplicated module/source directory: {directory.relative_to(ROOT)}")
    for line in dependencies.splitlines():
        if any(name in line for name in ("coil-compose", "markwon:", "androidx.media3:")) and "fullImplementation" not in line:
            errors.append(f"Full-only dependency not scoped to full: {line.strip()}")
    base = ET.parse(SRC / "main/AndroidManifest.xml").getroot()
    for edition, app_id in EXPECTED_IDS.items():
        if not re.search(r"\b" + edition + r"\s*\{\s*dimension 'edition'\s*applicationId '" + re.escape(app_id) + "'", gradle):
            errors.append(f"Missing stable applicationId for {edition}: {app_id}")
        active = common + sources(edition)
        types = {}
        for path in active:
            code = read(path)
            package = re.search(r"(?m)^package\s+([\w.]+)", code)
            if not package:
                continue
            for match in DECLARATIONS.finditer(code):
                if match["kind"] == "fun" or "private" in match["modifiers"]:
                    continue
                identity = package[1] + "." + match["name"]
                if identity in types:
                    errors.append(f"Duplicate type in {edition}: {identity}")
                types[identity] = path
        manifests = [base]
        extra = SRC / edition / "AndroidManifest.xml"
        if extra.exists():
            manifests.append(ET.parse(extra).getroot())
        for manifest in manifests:
            for element in manifest.iter():
                name = element.get(ANDROID + "name", "")
                if element.tag in {"application", "activity", "service", "receiver", "provider"} and name.startswith("com.chuckiehelper.mobile.") and name not in types:
                    errors.append(f"{edition} manifest component has no source: {name}")
                for value in element.attrib.values():
                    if value.startswith("@xml/"):
                        resource = "res/xml/" + value[5:] + ".xml"
                        if not any((SRC / source_set / resource).is_file() for source_set in ("main", edition)):
                            errors.append(f"{edition} missing manifest resource: {value}")
        if edition == "phone":
            names = {element.get(ANDROID + "name", "") for manifest in manifests for element in manifest.iter()}
            forbidden = {"com.chuckiehelper.mobile.RemoteActivity", "com.chuckiehelper.mobile.nativeui.AgentWatchService", "android.intent.action.SEND", "android.permission.FOREGROUND_SERVICE_DATA_SYNC"}
            for name in sorted(names & forbidden):
                errors.append(f"Phone manifest contains full-only entry: {name}")
            for manifest in manifests:
                for provider in manifest.iter("provider"):
                    if provider.get(ANDROID + "authorities", "").endswith((".hermes.files", ".updates")):
                        errors.append("Phone manifest contains a full-only file provider")
        print(f"{edition}: {len(active)} source files, {len(types)} types; applicationId {app_id}")
    for path in (SRC / "main/res").rglob("*.xml"):
        ET.parse(path)
    for edition in EXPECTED_IDS:
        for path in (SRC / edition / "res").rglob("*.xml"):
            ET.parse(path)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("PASS: source boundaries, variant identities, manifest components, resources and dependency scopes (no compilation)")
    return 0


if __name__ == "__main__":
    sys.exit(check())
