"""
Bestiary models: takes the 3D model and the textures of every monster out of your own
High Five client, for the profile pages of the bestiary.

What it needs (see README.txt):
  - the client folder (the one with Animations, StaticMeshes, Textures, System)
  - UE Viewer (umodel), which reads the client's encrypted .ukx/.usx/.utx packages
  - Npcgrp.dat saved as text with L2ClientDat (Npcgrp.txt): it names the mesh and
    the textures of every npc id

  python extract_models.py --client "C:/Lineage II" --umodel "C:/tools/umodel" --check
  python extract_models.py --client "C:/Lineage II" --umodel "C:/tools/umodel"

--umodel is umodel.exe or the folder UE Viewer was unpacked to. --npcgrp defaults to the
npcgrp*.txt in the client's System folder. --check reads everything and lists one package
with umodel, without exporting anything, to see that it all works first.

What it writes, into dist/game/data/html/custom/bestiary (the server serves it from there):
  models/<mesh>.glb       one per mesh, shared by every monster that uses it
  textures/<texture>.png  the skins (many monsters share a mesh with another skin)
  models/manifest.json    npc id -> mesh and textures, and how each texture's alpha shows
  extract_report.txt      what was found, and what was not

Only the monsters of the server (data/stats/npcs, the types of --types) are taken;
--all takes every npc of Npcgrp.txt. A npc with displayId uses that client npc's look.
Run it again after a client update; packages already exported are skipped (--force
exports them again).

A texture's alpha is only see-through in the game when the texture is masked (bMasked) or an
alpha texture (bAlphaTexture), or when the shader or final blend it is in says so; otherwise
it holds something else (often where the skin shines) and the skin is opaque. umodel's export
leaves these out, so each texture package is also read with umodel -dump.
"""

import argparse
import base64
import json
import os
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO = SCRIPT_DIR.parent.parent
DEFAULT_NPCS = REPO / "dist" / "game" / "data" / "stats" / "npcs"
DEFAULT_OUT = REPO / "dist" / "game" / "data" / "html" / "custom" / "bestiary"
DEFAULT_TYPES = "Monster,RaidBoss,GrandBoss,FeedableBeast,Chest,RiftInvader,FestivalMonster,SepulcherMonster"

PACKAGE_EXTENSIONS = (".ukx", ".usx", ".utx", ".unr", ".u")
UMODEL_NAMES = ("umodel_64.exe", "umodel.exe", "umodel")
# umodel -dump: "ClassName: Texture ObjectName: necro_t00", then the object's properties, one per line
# ("    bMasked = true"), or all on one line (" { a=b, c=d }") when they are few.
DUMP_HEADER = re.compile(r"ClassName:\s*(\S+)\s+ObjectName:\s*(\S+)")
DUMP_PROPERTY = re.compile(r"^    (\w+) = (.*)$")
DUMP_INLINE = re.compile(r"(\w+)=([^,}]*)")
# Shader and FinalBlend blendings that let what is behind show through.
BLENDINGS = ("OB_MODULATE", "OB_TRANSLUCENT", "OB_BRIGHTEN", "OB_DARKEN", "FB_MODULATE", "FB_ALPHABLEND", "FB_ALPHAMODULATE", "FB_TRANSLUCENT", "FB_DARKEN", "FB_BRIGHTEN", "FB_ADD")
# One field of a Npcgrp.txt row: key=[...], key={...} or key=value. L2ClientDat separates them with tabs; a copy
# with spaces reads the same.
NPCGRP_FIELD = re.compile(r"(\w+)=(\{[^}]*\}|\[[^\]]*\]|[^\t ]*)")
GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


# ---------------------------------------------------------------------------------------------
# Reading Npcgrp.txt and the server's npcs
# ---------------------------------------------------------------------------------------------

def read_text(path):
	raw = Path(path).read_bytes()
	if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
		return raw.decode("utf-16")
	if raw[:3] == b"\xef\xbb\xbf":
		return raw.decode("utf-8-sig")
	try:
		return raw.decode("utf-8")
	except UnicodeDecodeError:
		return raw.decode("cp1252", errors="replace")


def text_value(value):
	"""[LineageMonsters.gremlin_m00] -> LineageMonsters.gremlin_m00"""
	value = value.strip()
	if value.startswith("[") and value.endswith("]"):
		return value[1:-1]
	return value


def list_value(value):
	"""{[a];[b]} -> ["a", "b"], {} -> []"""
	value = value.strip()
	if value.startswith("{") and value.endswith("}"):
		return [item for item in re.findall(r"\[([^\]]*)\]", value) if item]
	single = text_value(value)
	return [single] if single else []


def parse_npcgrp(path):
	"""npc id -> {"mesh": "Package.Mesh", "textures": [...], "textures2": [...]}, from the L2ClientDat text export:
	npc_begin	npc_id=24464	class_name=[...]	mesh_name=[LineageNPCs3.illusion_sebion_m00]	texture_name={[LineageMonstersTex6.illusion_sebion.illusion_sebion_t00];[...]}	...	npc_end"""
	rows = {}
	for line in read_text(path).splitlines():
		line = line.strip()
		if not line.startswith("npc_begin"):
			continue
		fields = dict(NPCGRP_FIELD.findall(line))
		try:
			npc_id = int(fields.get("npc_id", ""))
		except ValueError:
			continue
		textures = fields.get("texture_name", "")
		# Some exports write the count, then the list under param_texture_name.
		if re.fullmatch(r"\s*\d+\s*", textures) and "param_texture_name" in fields:
			textures = fields["param_texture_name"]
		textures2 = fields.get("texture_name_second", "")
		if re.fullmatch(r"\s*\d+\s*", textures2) and "param_texture_name_second" in fields:
			textures2 = fields["param_texture_name_second"]
		rows[npc_id] = {
			"mesh": text_value(fields.get("mesh_name", "")),
			"textures": list_value(textures),
			"textures2": list_value(textures2),
		}
	return rows


def parse_server_npcs(folder, types):
	"""server npc id -> {"display": client npc id, "name", "type"}, for the npc types wanted (all if types is None)."""
	npcs = {}
	for file in sorted(Path(folder).rglob("*.xml")):
		for tag in re.finditer(r"<npc\s([^>]*)>", read_text(file)):
			attrs = dict(re.findall(r'(\w+)="([^"]*)"', tag.group(1)))
			if "id" not in attrs or not attrs["id"].isdigit():
				continue
			if (types is not None) and (attrs.get("type") not in types):
				continue
			npc_id = int(attrs["id"])
			display = attrs.get("displayId", "")
			npcs[npc_id] = {
				"display": int(display) if display.isdigit() else npc_id,
				"name": attrs.get("name", ""),
				"type": attrs.get("type", ""),
			}
	return npcs


# ---------------------------------------------------------------------------------------------
# Names
# ---------------------------------------------------------------------------------------------

def split_name(full):
	"""LineageMonsters.gremlin_m00 -> ("lineagemonsters", "gremlin_m00"); a group in the middle is dropped."""
	parts = [p for p in full.replace("'", "").split(".") if p]
	if len(parts) < 2:
		return None, (parts[0].lower() if parts else "")
	return parts[0].lower(), parts[-1].lower()


def key_of(full):
	package, name = split_name(full)
	key = (package + "." + name) if package else name
	return re.sub(r"[^a-z0-9_.-]", "_", key)


# ---------------------------------------------------------------------------------------------
# UE Viewer
# ---------------------------------------------------------------------------------------------

def umodel_command(value):
	"""The umodel executable (or the folder UE Viewer was unpacked to), or a command line for a wrapper such as 'wine umodel.exe'."""
	path = Path(value)
	if path.is_dir():
		for name in UMODEL_NAMES:
			if (path / name).is_file():
				return [str(path / name)]
		sys.exit("No umodel in %s: it should hold umodel.exe (or umodel_64.exe) from the UE Viewer download." % path)
	if path.exists():
		return [str(path)]
	# A path (no spaces, or starting like one) that is not there; anything else is a command such as "wine umodel.exe".
	if (" " not in value.strip()) or re.match(r"^([A-Za-z]:|\.{0,2})[\\/]", value.strip()):
		sys.exit("No umodel at %s: give umodel.exe, or the folder it is in." % value)
	return shlex.split(value)


def find_npcgrp(client):
	"""The npcgrp*.txt of the client's System folder (where L2ClientDat saves it next to Npcgrp.dat)."""
	for folder in [client] + [p for p in client.iterdir() if p.is_dir() and p.name.lower() == "system"]:
		for file in sorted(folder.iterdir()):
			if file.is_file() and file.name.lower().startswith("npcgrp") and file.suffix.lower() == ".txt":
				return file
	sys.exit("No npcgrp*.txt in %s or its System folder: save Npcgrp.dat as text with L2ClientDat, or give it with --npcgrp." % client)


def check_umodel(umodel, client, package_file, names):
	"""Lists one package with umodel (nothing is exported): it shows that umodel starts, reads the client's
	encrypted packages, and finds there the meshes Npcgrp names."""
	command = umodel + ["-list", "-path=" + str(client), package_file.stem]
	print("Listing %s with umodel (%.0f MB)..." % (package_file.name, package_file.stat().st_size / 1048576.0))
	try:
		result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, errors="replace", timeout=600)
	except (OSError, subprocess.TimeoutExpired) as error:
		print("  umodel could not list it: %s" % error)
		return False
	listing = result.stdout.lower()
	found = [name for name in names if name in listing]
	print("  umodel exited with %d; %d of the %d meshes Npcgrp names in %s are in it." % (result.returncode, len(found), len(names), package_file.name))
	if not found:
		print("  umodel said:")
		for line in [l for l in result.stdout.splitlines() if l.strip()][-15:]:
			print("    " + line)
		print("  If it does not recognize the package, try --umodel-args \"-game=l2\" (umodel -taglist lists the tags).")
	return bool(found)


def find_packages(client):
	"""package name (lowercase) -> file, for every package file of the client."""
	packages = {}
	for root, _, files in os.walk(client):
		for file in files:
			stem, ext = os.path.splitext(file)
			if ext.lower() in PACKAGE_EXTENSIONS:
				packages.setdefault(stem.lower(), Path(root) / file)
	return packages


def export_package(umodel, client, work, package, options, log, force):
	"""Exports every object of a package with umodel into work/<package>. Skipped when done before."""
	done = work / (".done_" + package.lower() + "_" + "_".join(o.strip("-") for o in options))
	if done.exists() and not force:
		return True
	command = umodel + ["-export"] + options + ["-path=" + str(client), "-out=" + str(work), package]
	log.write("\n$ " + " ".join(command) + "\n")
	log.flush()
	started = time.time()
	try:
		result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, errors="replace", timeout=1800)
	except (OSError, subprocess.TimeoutExpired) as error:
		log.write("failed: %s\n" % error)
		print("    umodel failed on %s: %s" % (package, error))
		return False
	log.write(result.stdout)
	log.write("exit %d after %.1f s\n" % (result.returncode, time.time() - started))
	if result.returncode != 0:
		print("    umodel exited with %d on %s (see umodel.log)" % (result.returncode, package))
		return False
	done.write_text("ok")
	return True


def dump_package(umodel, client, work, package, options, log, force):
	"""Every object of a package with its properties (umodel -dump), which the export leaves out: a texture's
	bMasked, a shader's OutputBlending. Kept in work/<package>.dump.txt."""
	file = work / (package.lower() + ".dump.txt")
	if file.exists() and not force:
		return read_text(file)
	command = umodel + ["-dump"] + options + ["-path=" + str(client), package]
	log.write("\n$ " + " ".join(command) + "\n")
	log.flush()
	try:
		result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True, errors="replace", timeout=1800)
	except (OSError, subprocess.TimeoutExpired) as error:
		log.write("failed: %s\n" % error)
		print("    umodel could not read the textures' flags of %s: %s" % (package, error))
		return ""
	log.write("exit %d, %d lines of dump\n" % (result.returncode, result.stdout.count("\n")))
	if result.returncode != 0 or "ObjectName:" not in result.stdout:
		print("    umodel could not read the textures' flags of %s (see umodel.log)" % package)
		log.write(result.stdout[-4000:])
		return ""
	file.write_text(result.stdout, encoding="utf-8")
	return result.stdout


def parse_dump(text, package, objects):
	"""Adds the objects of a package's dump: (package, name) and (None, name) -> (class, {property: value}), lowercase."""
	current = None
	for line in text.splitlines():
		header = DUMP_HEADER.search(line)
		if header:
			current = {}
			found = (header.group(1).lower(), current)
			name = header.group(2).lower()
			objects.setdefault((package, name), found)
			objects.setdefault((None, name), found)
			continue
		if current is None:
			continue
		prop = DUMP_PROPERTY.match(line)
		if prop:
			current.setdefault(prop.group(1).lower(), prop.group(2).strip())
		elif line.startswith(" { "):
			for key, value in DUMP_INLINE.findall(line):
				current.setdefault(key.lower(), value.strip())


def object_ref(value):
	"""Texture'LineageMonstersTex6.illusion_sebion.illusion_sebion_t00' -> LineageMonstersTex6.illusion_sebion.illusion_sebion_t00; None -> None"""
	match = re.search(r"'([^']+)'", value or "")
	return match.group(1) if match else None


def material_look(objects, full, depth=0):
	"""What a material shows: (the name of its picture, the name of the picture its opacity comes from when it is
	another one, how its alpha shows), following shaders, final blends and modifiers down to the texture. The alpha
	is "opaque", "blend", the cut-off of a mask (a number), or None when umodel's dump does not have the material."""
	package, name = split_name(full)
	found = (objects.get((package, name)) or objects.get((None, name))) if depth < 8 else None
	if not found:
		return full, None, None
	kind, props = found

	def flag(key):
		return props.get(key, "").lower() == "true"

	def ref(key):
		return object_ref(props.get(key))

	def cutoff():
		# AlphaRef (0-255) when the material tests its alpha itself, else the engine's masked texture cut-off.
		match = re.match(r"\d+", props.get("alpharef", ""))
		value = int(match.group(0)) if match else 0
		return round(max(1, value) / 255.0, 3) if flag("alphatest") and value else 0.5

	if kind == "texture":
		if flag("bmasked"):
			return full, None, 0.5
		return full, None, "blend" if flag("balphatexture") else "opaque"
	if kind == "shader":
		diffuse = ref("diffuse")
		picture = material_look(objects, diffuse, depth + 1)[0] if diffuse else full
		opacity = ref("opacity")
		opacity_picture = material_look(objects, opacity, depth + 1)[0] if opacity else None
		if opacity_picture and key_of(opacity_picture) == key_of(picture):
			opacity_picture = None
		blending = props.get("outputblending", "").upper()
		if flag("alphatest") or blending.startswith("OB_MASKED"):
			return picture, opacity_picture, cutoff()
		if blending.startswith(BLENDINGS) or opacity:
			return picture, opacity_picture, "blend"
		return picture, None, "opaque"
	inner = ref("material") or ref("material1")
	if not inner:
		return full, None, None
	picture, opacity_picture, alpha = material_look(objects, inner, depth + 1)
	if kind == "finalblend":
		if flag("alphatest"):
			return picture, opacity_picture, cutoff()
		if props.get("framebufferblending", "").upper().startswith(BLENDINGS):
			return picture, opacity_picture, "blend"
		return picture, None, "opaque"
	# TexPanner, TexOscillator, Combiner...: as the material they change.
	return picture, opacity_picture, alpha


def guess_alpha(source, pillow):
	"""For a texture umodel's dump does not have: a mask when its alpha is almost all fully clear or fully solid, with
	some of it clear (a torn cape); otherwise its alpha is taken as not see-through."""
	with pillow.open(source) as image:
		if "A" not in image.getbands():
			return "opaque"
		histogram = image.getchannel("A").histogram()
	total = float(sum(histogram)) or 1
	clear = sum(histogram[:16])
	solid = sum(histogram[240:])
	return 0.5 if (clear >= total * 0.02) and (clear + solid >= total * 0.95) else "opaque"


def index_exports(work, packages):
	"""(package, object) -> file and object -> [files], for everything umodel wrote. umodel puts a package's
	objects under <out>/<Package>/<Class>/<Object>.<ext>; the package is found by name, whatever the case."""
	by_package = {}
	by_name = {}
	for root, _, files in os.walk(work):
		relative = Path(root).relative_to(work)
		package = None
		for part in relative.parts:
			if part.lower() in packages:
				package = part.lower()
				break
		for file in files:
			stem, ext = os.path.splitext(file)
			ext = ext.lower()
			if ext not in (".gltf", ".glb", ".png", ".tga", ".mat"):
				continue
			path = Path(root) / file
			name = stem.lower()
			if package:
				by_package.setdefault((package, name, ext), path)
			by_name.setdefault((name, ext), []).append(path)
	return by_package, by_name


def lookup(index, full, exts):
	by_package, by_name = index
	package, name = split_name(full)
	for ext in exts:
		if package and (package, name, ext) in by_package:
			return by_package[(package, name, ext)]
	for ext in exts:
		found = by_name.get((name, ext))
		if found:
			return found[0]
	return None


# ---------------------------------------------------------------------------------------------
# glTF -> glb
# ---------------------------------------------------------------------------------------------

def read_buffer(gltf_path, buffer):
	uri = buffer.get("uri", "")
	if uri.startswith("data:"):
		return base64.b64decode(uri.split(",", 1)[1])
	return (gltf_path.parent / uri.replace("%20", " ")).read_bytes()


def pad(data, filler):
	return data + filler * ((4 - len(data) % 4) % 4)


def gltf_to_glb(source, target):
	"""Packs a .gltf (+.bin) into one .glb without its pictures (the bestiary loads the skins itself, by
	material slot). Returns, per material slot, (the picture file the mesh had there or None, the material's name):
	umodel names the slot's material but gives it no picture."""
	if source.suffix.lower() == ".glb":
		data = source.read_bytes()
		magic, _, length = struct.unpack_from("<III", data, 0)
		if magic != GLB_MAGIC:
			raise ValueError("not a glb file")
		json_length, _ = struct.unpack_from("<II", data, 12)
		doc = json.loads(data[20:20 + json_length].decode("utf-8"))
		blob = b""
		offset = 20 + json_length
		if offset + 8 <= length:
			bin_length, _ = struct.unpack_from("<II", data, offset)
			blob = data[offset + 8:offset + 8 + bin_length]
		buffers = [blob]
		base = source.parent
	else:
		doc = json.loads(read_text(source))
		buffers = [read_buffer(source, buffer) for buffer in doc.get("buffers", [])]
		base = source.parent

	# One buffer: the others go after the first, each 4-byte aligned.
	blob = b""
	offsets = []
	for data in buffers:
		blob = pad(blob, b"\x00")
		offsets.append(len(blob))
		blob += data
	for view in doc.get("bufferViews", []):
		view["byteOffset"] = view.get("byteOffset", 0) + offsets[view.get("buffer", 0)]
		view["buffer"] = 0

	# The picture of each material slot, then no pictures in the model.
	images = doc.get("images", [])
	textures = doc.get("textures", [])
	defaults = []
	for material in doc.get("materials", []):
		picture = None
		pbr = material.get("pbrMetallicRoughness", {})
		ref = pbr.get("baseColorTexture") or material.get("emissiveTexture")
		if ref and (ref.get("index", -1) < len(textures)):
			image = textures[ref["index"]].get("source", -1)
			if 0 <= image < len(images) and images[image].get("uri") and not images[image]["uri"].startswith("data:"):
				picture = base / images[image]["uri"].replace("%20", " ")
		defaults.append((picture if (picture and picture.exists()) else None, material.get("name", "")))
		for field in ("normalTexture", "occlusionTexture", "emissiveTexture"):
			material.pop(field, None)
		pbr.pop("baseColorTexture", None)
		pbr.pop("metallicRoughnessTexture", None)
	for field in ("images", "textures", "samplers"):
		doc.pop(field, None)

	blob = pad(blob, b"\x00")
	doc["buffers"] = [{"byteLength": len(blob)}]
	text = pad(json.dumps(doc, separators=(",", ":")).encode("utf-8"), b" ")
	length = 12 + 8 + len(text) + (8 + len(blob) if blob else 0)
	with open(target, "wb") as out:
		out.write(struct.pack("<III", GLB_MAGIC, 2, length))
		out.write(struct.pack("<II", len(text), CHUNK_JSON))
		out.write(text)
		if blob:
			out.write(struct.pack("<II", len(blob), CHUNK_BIN))
			out.write(blob)
	return defaults


# ---------------------------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------------------------

def material_picture(mat_file, index):
	"""umodel writes a material (Shader, FinalBlend...) as a .mat text file naming its textures: the diffuse one."""
	values = {}
	for line in read_text(mat_file).splitlines():
		if "=" in line:
			key, value = line.split("=", 1)
			values[key.strip().lower()] = value.strip().strip("'\"")
	for key in ("diffuse", "diffusetexture", "basetexture", "texture", "material"):
		if values.get(key):
			name = re.split(r"[.'\"]", values[key].replace("Texture'", "").strip("'"))[-1] or values[key]
			return lookup(index, name, (".png", ".tga"))
	return None


def copy_texture(source, target, max_size, pillow, opacity=None):
	"""Copies a picture as PNG, made smaller than max_size when Pillow is there. With opacity (another picture, which a
	shader takes its opacity from) and Pillow, the copy gets that picture's alpha, or its grey when its alpha is all
	solid (or it has none): an opacity texture without alpha keeps it in its colour."""
	if opacity and pillow:
		with pillow.open(source) as image, pillow.open(opacity) as mask:
			alpha = mask.getchannel("A") if "A" in mask.getbands() else None
			if alpha is None or alpha.getextrema()[0] == 255:
				alpha = mask.convert("L")
			image = image.convert("RGBA")
			image.putalpha(alpha.resize(image.size))
			if max_size and max(image.size) > max_size:
				image.thumbnail((max_size, max_size), getattr(pillow, "LANCZOS", 1))
			image.save(target, "PNG", optimize=True)
		return True
	if source.suffix.lower() == ".png" and not (pillow and max_size):
		shutil.copyfile(source, target)
		return True
	if not pillow:
		if source.suffix.lower() == ".png":
			shutil.copyfile(source, target)
			return True
		return False
	with pillow.open(source) as image:
		if max_size and max(image.size) > max_size:
			image.thumbnail((max_size, max_size), getattr(pillow, "LANCZOS", 1))
		image.save(target, "PNG", optimize=True)
	return True


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

def main():
	parser = argparse.ArgumentParser(description="Extract monster models and textures from a High Five client for the bestiary.")
	parser.add_argument("--client", required=True, help="the client folder (Animations, StaticMeshes, Textures, System)")
	parser.add_argument("--umodel", required=True, help="umodel executable (UE Viewer), or a command such as \"wine umodel.exe\"")
	parser.add_argument("--npcgrp", default="", help="Npcgrp.dat saved as text with L2ClientDat (default: npcgrp*.txt in the client's System folder)")
	parser.add_argument("--out", default=str(DEFAULT_OUT), help="where the bestiary art goes (default: the server's data/html/custom/bestiary)")
	parser.add_argument("--npcs", default=str(DEFAULT_NPCS), help="the server's npc XML folder, to know which npcs are monsters")
	parser.add_argument("--types", default=DEFAULT_TYPES, help="npc types to take (default: the monster types)")
	parser.add_argument("--all", action="store_true", help="take every npc of Npcgrp.txt, not only monsters")
	parser.add_argument("--only", default="", help="comma-separated npc ids, to try a few first")
	parser.add_argument("--work", default=str(SCRIPT_DIR / "work"), help="folder for umodel's raw export (can be deleted afterwards)")
	parser.add_argument("--max-texture", type=int, default=1024, help="largest texture side in pixels when Pillow is installed (0 = keep)")
	parser.add_argument("--umodel-args", default="", help="extra umodel options, e.g. \"-game=l2\"")
	parser.add_argument("--force", action="store_true", help="export packages again even when done before")
	parser.add_argument("--check", action="store_true", help="only check the setup: read Npcgrp, find the packages, list one with umodel; nothing is exported")
	args = parser.parse_args()

	client = Path(args.client)
	out = Path(args.out)
	work = Path(args.work)
	if not client.is_dir():
		sys.exit("No client folder at %s" % client)
	umodel = umodel_command(args.umodel)
	extra = shlex.split(args.umodel_args)
	try:
		import PIL.Image as pillow
	except ImportError:
		pillow = None
		print("Pillow is not installed: textures are copied as they are (pip install pillow to make them smaller).")

	npcgrp_file = Path(args.npcgrp) if args.npcgrp else find_npcgrp(client)
	print("Reading %s" % npcgrp_file)
	npcgrp = parse_npcgrp(npcgrp_file)
	print("  %d npcs in Npcgrp" % len(npcgrp))
	if not npcgrp:
		sys.exit("No npc_begin rows: save Npcgrp.dat as text with L2ClientDat first (see README.txt).")

	types = None if args.all else set(t.strip() for t in args.types.split(",") if t.strip())
	if args.all:
		server = {npc_id: {"display": npc_id, "name": "", "type": ""} for npc_id in npcgrp}
		server.update(parse_server_npcs(args.npcs, None) if Path(args.npcs).is_dir() else {})
	else:
		server = parse_server_npcs(args.npcs, types)
	if args.only:
		wanted = set(int(x) for x in args.only.split(",") if x.strip().isdigit())
		server = {k: v for k, v in server.items() if k in wanted}
	print("  %d npcs to take from %s" % (len(server), "Npcgrp" if args.all else args.npcs))

	# What each npc looks like, through its display id.
	looks = {}
	no_look = []
	for npc_id, npc in sorted(server.items()):
		row = npcgrp.get(npc["display"])
		if not row or not row["mesh"]:
			no_look.append(npc_id)
			continue
		looks[npc_id] = row

	packages = find_packages(client)
	print("  %d packages in the client" % len(packages))
	mesh_packages = sorted(set(split_name(r["mesh"])[0] for r in looks.values() if split_name(r["mesh"])[0]))
	texture_packages = sorted(set(split_name(t)[0] for r in looks.values() for t in r["textures"] + r["textures2"] if split_name(t)[0]))
	missing_packages = [p for p in mesh_packages + texture_packages if p not in packages]

	if args.check:
		print("  %d of them have a look in Npcgrp, with %d meshes in %d packages and textures in %d packages" % (len(looks), len(set(key_of(r["mesh"]) for r in looks.values())), len(mesh_packages), len(texture_packages)))
		if missing_packages:
			print("  Packages Npcgrp names that are not in the client: %s" % ", ".join(missing_packages))
		present = [p for p in mesh_packages if p in packages]
		if not present:
			sys.exit("None of the mesh packages Npcgrp names is in %s: is it the client folder?" % client)
		smallest = min(present, key=lambda p: packages[p].stat().st_size)
		names = sorted(set(split_name(r["mesh"])[1] for r in looks.values() if split_name(r["mesh"])[0] == smallest))
		ok = check_umodel(umodel, client, packages[smallest], names)
		print("Ready: run it again without --check to export (it writes into %s)." % out if ok else "Not ready: see above.")
		return

	work.mkdir(parents=True, exist_ok=True)
	(out / "models").mkdir(parents=True, exist_ok=True)
	(out / "textures").mkdir(parents=True, exist_ok=True)
	with open(work / "umodel.log", "a", encoding="utf-8") as log:
		todo = [p for p in mesh_packages if p in packages]
		for i, package in enumerate(todo, 1):
			print("[%d/%d] meshes of %s" % (i, len(todo), packages[package].name))
			export_package(umodel, client, work, packages[package].stem, ["-gltf", "-png", "-noanim"] + extra, log, args.force)
		todo = [p for p in texture_packages if p in packages and p not in mesh_packages]
		for i, package in enumerate(todo, 1):
			print("[%d/%d] textures of %s" % (i, len(todo), packages[package].name))
			export_package(umodel, client, work, packages[package].stem, ["-png"] + extra, log, args.force)
		# How each texture's alpha shows: the flags the export leaves out.
		objects = {}
		todo = [p for p in texture_packages if p in packages]
		for i, package in enumerate(todo, 1):
			print("[%d/%d] textures' flags of %s" % (i, len(todo), packages[package].name))
			parse_dump(dump_package(umodel, client, work, packages[package].stem, extra, log, args.force), package, objects)

	print("Collecting the exported files")
	index = index_exports(work, packages)

	manifest = {"version": 2, "generated": time.strftime("%Y-%m-%d %H:%M:%S"), "meshes": {}, "textures": {}, "alpha": {}, "npcs": {}}
	manifest_file = out / "models" / "manifest.json"
	if args.only and manifest_file.exists():
		# A few npcs again: the others of the last run stay.
		try:
			previous = json.loads(manifest_file.read_text(encoding="utf-8"))
			for part in ("meshes", "textures", "alpha", "npcs"):
				manifest[part].update(previous.get(part, {}))
			for npc_id in server:
				manifest["npcs"].pop(str(npc_id), None)
		except (OSError, ValueError) as error:
			print("    could not read the previous manifest (%s): it is made again" % error)
	missing_meshes = {}
	missing_textures = {}
	failed_meshes = {}
	guessed_alpha = []

	def take_texture(full_or_path, key):
		"""Copies a texture (by its client name, or a file) into textures/ once, with how its alpha shows; returns its key or None."""
		if key in manifest["textures"]:
			return key
		opacity = None
		alpha = None
		if isinstance(full_or_path, Path):
			source = full_or_path
		else:
			picture, opacity_name, alpha = material_look(objects, full_or_path)
			source = lookup(index, picture, (".png", ".tga"))
			if source is None:
				mat = lookup(index, full_or_path, (".mat",))
				source = material_picture(mat, index) if mat else None
			opacity = lookup(index, opacity_name, (".png", ".tga")) if opacity_name else None
		if source is None:
			return None
		if alpha is None and pillow:
			alpha = guess_alpha(source, pillow)
			guessed_alpha.append(key)
		file = key + ".png"
		try:
			if copy_texture(source, out / "textures" / file, args.max_texture, pillow, opacity):
				manifest["textures"][key] = file
				if alpha is not None:
					manifest["alpha"][key] = alpha
				return key
		except OSError as error:
			print("    could not copy %s: %s" % (source, error))
		return None

	for npc_id, row in sorted(looks.items()):
		mesh_key = key_of(row["mesh"])
		if mesh_key not in manifest["meshes"] and mesh_key not in failed_meshes:
			source = lookup(index, row["mesh"], (".gltf", ".glb"))
			if source is None:
				failed_meshes[mesh_key] = "not exported (package %s %s)" % (split_name(row["mesh"])[0], "missing" if split_name(row["mesh"])[0] not in packages else "has no such mesh")
			else:
				try:
					defaults = gltf_to_glb(source, out / "models" / (mesh_key + ".glb"))
					default_keys = []
					for slot, (picture, material) in enumerate(defaults):
						# The slot's own picture, or the texture its material is named after (umodel gives none).
						if picture:
							default_keys.append(take_texture(picture, mesh_key + "__" + str(slot)))
						elif material and not material.startswith("dummy_material_"):
							default_keys.append(take_texture(material, key_of(material)))
						else:
							default_keys.append(None)
					manifest["meshes"][mesh_key] = {"file": mesh_key + ".glb", "defaults": default_keys}
				except (OSError, ValueError, KeyError, struct.error) as error:
					failed_meshes[mesh_key] = "could not convert %s: %s" % (source, error)
		if mesh_key not in manifest["meshes"]:
			missing_meshes.setdefault(mesh_key, []).append(npc_id)
			continue

		textures = []
		for name in row["textures"]:
			texture_key = key_of(name)
			taken = take_texture(name, texture_key)
			if taken is None:
				missing_textures.setdefault(texture_key, []).append(npc_id)
			textures.append(taken)
		entry = {"mesh": mesh_key, "textures": textures}
		if npc_id != server[npc_id]["display"]:
			entry["display"] = server[npc_id]["display"]
		manifest["npcs"][str(npc_id)] = entry

	if not manifest["alpha"]:
		# Neither umodel's dump nor Pillow told anything: the page cuts every alpha, as it did before it knew.
		del manifest["alpha"]
	manifest_file.write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")

	report = [
		"Bestiary model extraction, %s" % manifest["generated"],
		"",
		"npcs taken:              %d" % len(server),
		"with a look in Npcgrp:   %d" % len(looks),
		"with a model:            %d%s" % (len(manifest["npcs"]), " (with the earlier runs)" if args.only else ""),
		"meshes:                  %d" % len(manifest["meshes"]),
		"textures:                %d" % len(manifest["textures"]),
		"  masked / blended:      %d / %d" % (sum(1 for a in manifest.get("alpha", {}).values() if not isinstance(a, str)), sum(1 for a in manifest.get("alpha", {}).values() if a == "blend")),
		"",
	]
	if no_look:
		report.append("No Npcgrp row (or no mesh) for %d npcs: %s" % (len(no_look), ", ".join(map(str, no_look[:200]))))
	if missing_packages:
		report.append("Packages not found in the client: %s" % ", ".join(missing_packages))
	if failed_meshes:
		report.append("")
		report.append("Meshes without a model (%d):" % len(failed_meshes))
		for mesh, reason in sorted(failed_meshes.items()):
			report.append("  %s: %s (npcs %s)" % (mesh, reason, ", ".join(map(str, missing_meshes.get(mesh, [])[:20]))))
	if guessed_alpha:
		report.append("")
		report.append("Textures not in umodel's dump (%d), their alpha guessed from the picture: %s" % (len(guessed_alpha), ", ".join(sorted(guessed_alpha)[:200])))
	if missing_textures:
		report.append("")
		report.append("Textures not found (%d), those monsters show their mesh's own skin:" % len(missing_textures))
		for texture, npcs in sorted(missing_textures.items()):
			report.append("  %s (npcs %s)" % (texture, ", ".join(map(str, npcs[:20]))))
	(out / "extract_report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
	print("\n".join(report[:9]))
	print("Report: %s" % (out / "extract_report.txt"))
	print("Done. Open the bestiary bake page (//bestiary in game) to make the portraits of the collection.")


if __name__ == "__main__":
	main()
