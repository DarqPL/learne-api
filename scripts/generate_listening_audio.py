import argparse
import asyncio
import json
import os
from pathlib import Path


REQUIRED_CLOUDINARY_ENV = (
    "CLOUDINARY_CLOUD_NAME",
    "CLOUDINARY_API_KEY",
    "CLOUDINARY_API_SECRET",
)
DEFAULT_MANIFEST = Path("database/realistic-content/listening-manifest.json")
DEFAULT_OUTPUT_DIR = Path("database/realistic-content/generated-audio")


def load_env_file(path):
    env = {}
    if not path:
        return env

    env_path = Path(path)
    if not env_path.exists():
        return env

    for line in env_path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def merged_env(env_file):
    env = dict(os.environ)
    env.update(load_env_file(env_file))
    return env


def load_manifest(path):
    manifest_path = Path(path)
    data = json.loads(manifest_path.read_text(encoding="utf-8"))

    items = data.get("items")
    if not isinstance(items, list):
        raise ValueError("manifest items must be a list")

    for index, item in enumerate(items):
        for key in ("id", "fileName", "voice", "transcript"):
            if not item.get(key):
                item_id = item.get("id", f"item at index {index}")
                raise ValueError(f"{item_id} missing {key}")
    return data


def write_manifest(path, data):
    manifest_path = Path(path)
    manifest_path.write_text(
        json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def cloudinary_public_id(folder, item, reuse_existing=True):
    if reuse_existing and item.get("audioPublicId"):
        return item["audioPublicId"]
    return f"{folder.rstrip('/')}/{Path(item['fileName']).stem}"


async def synthesize_item(item, output_dir):
    import edge_tts

    output_path = Path(output_dir) / item["fileName"]
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.exists():
        return output_path

    communicate = edge_tts.Communicate(item["transcript"], item["voice"])
    await communicate.save(str(output_path))
    return output_path


def configure_cloudinary(env):
    import cloudinary

    missing = [key for key in REQUIRED_CLOUDINARY_ENV if not env.get(key)]
    if missing:
        raise ValueError(f"Missing required Cloudinary env keys: {', '.join(missing)}")

    cloudinary.config(
        cloud_name=env["CLOUDINARY_CLOUD_NAME"],
        api_key=env["CLOUDINARY_API_KEY"],
        api_secret=env["CLOUDINARY_API_SECRET"],
        secure=True,
    )


def upload_audio(path, public_id):
    import cloudinary.uploader

    return cloudinary.uploader.upload(
        str(path), resource_type="video", public_id=public_id, overwrite=True
    )


async def process_manifest(args):
    data = load_manifest(args.manifest)
    items = data["items"]
    explicit_folder = args.folder is not None
    folder = args.folder or data.get("cloudinaryFolder") or "learne/listening-audio"

    if args.dry_run:
        print(f"Validated {len(items)} listening manifest items")
        return data

    env = merged_env(args.env_file)
    if not explicit_folder:
        folder = env.get("CLOUDINARY_LISTENING_FOLDER") or folder
    configure_cloudinary(env)

    output_dir = Path(args.output_dir)
    for item in items:
        audio_path = await synthesize_item(item, output_dir)
        public_id = cloudinary_public_id(folder, item, reuse_existing=not explicit_folder)
        upload_result = upload_audio(audio_path, public_id)
        item["audioPublicId"] = upload_result.get("public_id", public_id)
        item["audioUrl"] = upload_result.get("secure_url") or upload_result.get("url", "")

    data["cloudinaryFolder"] = folder
    write_manifest(args.manifest, data)
    print(f"Uploaded {len(items)} listening audio files")
    return data


def parse_args():
    parser = argparse.ArgumentParser(description="Generate and upload listening audio")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--folder")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main():
    asyncio.run(process_manifest(parse_args()))


if __name__ == "__main__":
    main()
