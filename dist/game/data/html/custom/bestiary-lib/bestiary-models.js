/*
 * The monsters' 3D models for the bestiary: the profile page's viewer and the bake page's portraits.
 *
 * The models come from the client (client/Bestiary/extract_models.py): one .glb per mesh, without
 * skins, and a manifest naming, for each npc, its mesh and the skin of each material slot (the
 * client gives the same mesh other skins for other monsters), with how each skin's alpha shows:
 * in the client a texture's alpha is only see-through when the texture or its shader says so
 * (masked or blended); otherwise it holds something else, such as a shine mask, and the skin is
 * opaque. view.json says how far the models are turned to face the camera, as set on the bake page.
 */
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import * as SkeletonUtils from "three/addons/utils/SkeletonUtils.js";

const BASE = "/bestiary/";
const DEFAULT_VIEW = { yaw: 0, pitch: 12 };
const FOV = 30;

let manifestPromise = null;
let viewPromise = null;
const meshes = new Map(); // mesh key -> Promise<gltf>
const textures = new Map(); // texture key -> Promise<Texture>
const loader = new GLTFLoader();
const textureLoader = new THREE.TextureLoader();

/** @return the manifest, or null when there are no models */
export function loadManifest() {
	if (!manifestPromise) {
		manifestPromise = fetch(BASE + "models/manifest.json").then(r => r.ok ? r.json() : null).catch(() => null);
	}
	return manifestPromise;
}

/** @return how the models are turned: {yaw, pitch} in degrees */
export function loadView(reload) {
	if (!viewPromise || reload) {
		viewPromise = fetch(BASE + "models/view.json").then(r => r.ok ? r.json() : DEFAULT_VIEW).then(v => Object.assign({}, DEFAULT_VIEW, v)).catch(() => DEFAULT_VIEW);
	}
	return viewPromise;
}

/** @return true when WebGL works in this browser */
export function webglAvailable() {
	try {
		const canvas = document.createElement("canvas");
		return !!(window.WebGLRenderingContext && (canvas.getContext("webgl2") || canvas.getContext("webgl")));
	} catch (e) {
		return false;
	}
}

function loadMesh(key, file) {
	if (!meshes.has(key)) {
		meshes.set(key, loader.loadAsync(BASE + "models/" + file));
	}
	return meshes.get(key);
}

function loadTexture(key, file) {
	if (!textures.has(key)) {
		textures.set(key, textureLoader.loadAsync(BASE + "textures/" + file).then(texture => {
			// glTF texture coordinates: no flip.
			texture.flipY = false;
			texture.colorSpace = THREE.SRGBColorSpace;
			texture.anisotropy = 4;
			texture.wrapS = texture.wrapT = THREE.RepeatWrapping;
			return texture;
		}).catch(() => null));
	}
	return textures.get(key);
}

/**
 * How a skin's alpha shows: a number is the cut-off of a masked skin, "blend" a skin blended by its
 * alpha, "opaque" a skin whose alpha is not see-through.
 */
function alphaOf(manifest, key) {
	if (!manifest.alpha) {
		// Extracted before the extractor read the skins' flags: every alpha cut, as then.
		return 0.45;
	}
	const mode = key ? manifest.alpha[key] : undefined;
	return mode === undefined ? "opaque" : mode;
}

/** Forgets the loaded meshes and skins (the bake page goes through thousands). */
export function clearCache() {
	for (const promise of meshes.values()) {
		promise.then(gltf => gltf.scene.traverse(o => { if (o.geometry) o.geometry.dispose(); })).catch(() => {});
	}
	for (const promise of textures.values()) {
		promise.then(t => t && t.dispose()).catch(() => {});
	}
	meshes.clear();
	textures.clear();
}

/**
 * Builds a monster: its mesh with its skins, standing on y = 0, centred, turned by the view's yaw.
 * @param {number} npcId the monster
 * @param {{silhouette?: boolean}} options silhouette: all black (a monster not found yet)
 * @return {Promise<THREE.Group|null>} null when the monster has no model
 */
export async function buildMonster(npcId, options) {
	const silhouette = !!(options && options.silhouette);
	const [manifest, view] = await Promise.all([loadManifest(), loadView()]);
	const entry = manifest && manifest.npcs ? manifest.npcs[String(npcId)] : null;
	const mesh = entry ? manifest.meshes[entry.mesh] : null;
	if (!mesh) {
		return null;
	}
	const gltf = await loadMesh(entry.mesh, mesh.file);
	const object = SkeletonUtils.clone(gltf.scene);

	// The skin of each material slot: the npc's own, else the mesh's.
	const skinOf = slot => {
		const own = entry.textures || [];
		const defaults = mesh.defaults || [];
		return own[slot] || defaults[slot] || own[0] || defaults[0] || null;
	};
	const jobs = [];
	object.traverse(node => {
		if (!node.isMesh) {
			return;
		}
		node.frustumCulled = false;
		const originals = Array.isArray(node.material) ? node.material : [node.material];
		const replaced = originals.map(original => {
			const association = gltf.parser.associations.get(original);
			const slot = association && (association.materials !== undefined) ? association.materials : 0;
			const key = skinOf(slot);
			const file = key ? (manifest.textures || {})[key] : null;
			const alpha = alphaOf(manifest, key);
			let material;
			if (silhouette) {
				// All black, but with the holes of a masked skin (a torn cape, feathers), so it has the monster's real outline.
				material = new THREE.MeshBasicMaterial({ color: 0x050507, side: THREE.DoubleSide });
				if (typeof alpha !== "number") {
					return material;
				}
				material.alphaTest = alpha;
			} else {
				material = new THREE.MeshStandardMaterial({ color: 0xffffff, side: THREE.DoubleSide, roughness: 0.85, metalness: 0 });
				if (typeof alpha === "number") {
					material.alphaTest = alpha;
				} else if (alpha === "blend") {
					// Blended by its alpha; what is fully clear is left out, so it doesn't hide what is behind it.
					material.transparent = true;
					material.alphaTest = 0.02;
				}
			}
			if (file) {
				jobs.push(loadTexture(key, file).then(texture => {
					if (texture) {
						material.map = texture;
						material.needsUpdate = true;
					}
				}));
			}
			return material;
		});
		node.material = Array.isArray(node.material) ? replaced : replaced[0];
	});
	await Promise.all(jobs);

	// Bind pose bounds (skinned meshes included), then stand it on the ground.
	object.updateMatrixWorld(true);
	const box = new THREE.Box3().setFromObject(object, true);
	if (box.isEmpty()) {
		return null;
	}
	const center = box.getCenter(new THREE.Vector3());
	object.position.set(-center.x, -box.min.y, -center.z);
	const group = new THREE.Group();
	group.add(object);
	group.rotation.y = THREE.MathUtils.degToRad(view.yaw || 0);
	group.userData.size = box.getSize(new THREE.Vector3());
	return group;
}

function addLights(scene) {
	scene.add(new THREE.HemisphereLight(0xfff1d6, 0x1a1712, 1.4));
	const key = new THREE.DirectionalLight(0xffe2b0, 2.2);
	key.position.set(2.5, 4, 3.5);
	scene.add(key);
	const rim = new THREE.DirectionalLight(0x8fb4ff, 1.4);
	rim.position.set(-3, 2.5, -4);
	scene.add(rim);
	scene.add(new THREE.AmbientLight(0xffffff, 0.35));
}

/** A soft round shadow under the monster. */
function groundShadow(radius) {
	const canvas = document.createElement("canvas");
	canvas.width = canvas.height = 128;
	const g = canvas.getContext("2d");
	const gradient = g.createRadialGradient(64, 64, 4, 64, 64, 64);
	gradient.addColorStop(0, "rgba(0,0,0,0.55)");
	gradient.addColorStop(1, "rgba(0,0,0,0)");
	g.fillStyle = gradient;
	g.fillRect(0, 0, 128, 128);
	const texture = new THREE.CanvasTexture(canvas);
	const disc = new THREE.Mesh(new THREE.PlaneGeometry(radius * 2, radius * 2), new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false }));
	disc.rotation.x = -Math.PI / 2;
	disc.position.y = 0.01;
	return disc;
}

/** Puts the camera where the whole monster fits, looking at its middle from a little above; a portrait (tight) fills its picture. */
function frame(camera, size, pitchDegrees, aspect, tight) {
	const height = Math.max(size.y, 0.001);
	const width = Math.max(size.x, size.z, 0.001);
	const fov = THREE.MathUtils.degToRad(FOV);
	const fitHeight = height / (2 * Math.tan(fov / 2));
	const fitWidth = width / (2 * Math.tan(fov / 2) * Math.max(aspect, 0.2));
	const distance = tight ? Math.max(fitHeight, fitWidth) * 1.02 + width * 0.25 : Math.max(fitHeight, fitWidth) * 1.18 + width * 0.5;
	const pitch = THREE.MathUtils.degToRad(pitchDegrees);
	const target = new THREE.Vector3(0, height * 0.5, 0);
	camera.position.set(0, target.y + Math.sin(pitch) * distance, Math.cos(pitch) * distance);
	camera.near = distance / 100;
	camera.far = distance * 10;
	camera.updateProjectionMatrix();
	camera.lookAt(target);
	return { target, distance };
}

/**
 * A monster in a box of the page, turning slowly; it can be dragged around and zoomed.
 * @param {HTMLElement} container where it goes (its size is the viewer's)
 * @param {number} npcId the monster
 * @param {{silhouette?: boolean}} options
 * @return {Promise<{dispose: function}|null>} null when it has no model or WebGL is off
 */
export async function createViewer(container, npcId, options) {
	if (!webglAvailable()) {
		return null;
	}
	const [monster, view] = await Promise.all([buildMonster(npcId, options), loadView()]);
	if (!monster || !container.isConnected) {
		return null;
	}
	const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
	renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
	renderer.outputColorSpace = THREE.SRGBColorSpace;
	renderer.toneMapping = THREE.ACESFilmicToneMapping;
	renderer.domElement.style.display = "block";
	container.appendChild(renderer.domElement);

	const scene = new THREE.Scene();
	addLights(scene);
	scene.add(monster);
	const size = monster.userData.size;
	scene.add(groundShadow(Math.max(size.x, size.z) * 0.75));

	const camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 1000);
	const controls = new OrbitControls(camera, renderer.domElement);
	controls.enableDamping = true;
	controls.enablePan = false;
	controls.autoRotate = true;
	controls.autoRotateSpeed = 1.6;
	controls.maxPolarAngle = THREE.MathUtils.degToRad(100);
	controls.addEventListener("start", () => { controls.autoRotate = false; });

	let framed = null;
	const resize = () => {
		const w = Math.max(container.clientWidth, 1);
		const h = Math.max(container.clientHeight, 1);
		renderer.setSize(w, h, false);
		renderer.domElement.style.width = "100%";
		renderer.domElement.style.height = "100%";
		camera.aspect = w / h;
		if (!framed) {
			framed = frame(camera, size, view.pitch, camera.aspect);
			controls.target.copy(framed.target);
			controls.minDistance = framed.distance * 0.35;
			controls.maxDistance = framed.distance * 2.5;
		}
		camera.updateProjectionMatrix();
	};
	resize();
	const observer = new ResizeObserver(resize);
	observer.observe(container);

	let running = true;
	const loop = () => {
		if (!running) {
			return;
		}
		controls.update();
		renderer.render(scene, camera);
		requestAnimationFrame(loop);
	};
	loop();

	return {
		dispose() {
			running = false;
			observer.disconnect();
			controls.dispose();
			scene.traverse(o => { if (o.isMesh && o.material) [].concat(o.material).forEach(m => m.dispose()); });
			renderer.dispose();
			renderer.domElement.remove();
		}
	};
}

let bakeRenderer = null;

/**
 * Renders a monster's portrait on a transparent background.
 * @param {number} npcId the monster
 * @param {number} size width and height in pixels
 * @param {{yaw: number, pitch: number}} view how it is turned (the saved view if left out)
 * @return {Promise<Blob|null>} a webp (png where webp is not supported), or null without a model
 */
export async function renderPortrait(npcId, size, view) {
	if (view) {
		viewPromise = Promise.resolve(Object.assign({}, DEFAULT_VIEW, view));
	}
	const monster = await buildMonster(npcId, {});
	if (!monster) {
		return null;
	}
	if (!bakeRenderer) {
		bakeRenderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
		bakeRenderer.outputColorSpace = THREE.SRGBColorSpace;
		bakeRenderer.toneMapping = THREE.ACESFilmicToneMapping;
		bakeRenderer.setClearColor(0x000000, 0);
	}
	bakeRenderer.setPixelRatio(1);
	bakeRenderer.setSize(size, size, false);
	const scene = new THREE.Scene();
	addLights(scene);
	scene.add(monster);
	const camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 1000);
	frame(camera, monster.userData.size, (await loadView()).pitch, 1, true);
	bakeRenderer.render(scene, camera);
	const canvas = bakeRenderer.domElement;
	const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/webp", 0.9));
	scene.traverse(o => { if (o.isMesh && o.material) [].concat(o.material).forEach(m => m.dispose()); });
	if (blob && blob.type === "image/webp") {
		return blob;
	}
	return new Promise(resolve => canvas.toBlob(resolve, "image/png"));
}
