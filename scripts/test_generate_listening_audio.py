import asyncio
import json
from types import SimpleNamespace

import pytest

import generate_listening_audio
from generate_listening_audio import (
    cloudinary_public_id,
    load_env_file,
    load_manifest,
    write_manifest,
)


def test_load_env_file_reads_cloudinary_keys_and_listening_folder(tmp_path):
    env_file = tmp_path / ".env"
    env_file.write_text(
        "\n".join(
            [
                "CLOUDINARY_CLOUD_NAME=realistic-cloud",
                "CLOUDINARY_API_KEY=realistic-key",
                "CLOUDINARY_API_SECRET=realistic-secret",
                "CLOUDINARY_LISTENING_FOLDER=learne/listening-audio",
            ]
        ),
        encoding="utf-8",
    )

    env = load_env_file(env_file)

    assert env["CLOUDINARY_CLOUD_NAME"] == "realistic-cloud"
    assert env["CLOUDINARY_API_KEY"] == "realistic-key"
    assert env["CLOUDINARY_API_SECRET"] == "realistic-secret"
    assert env["CLOUDINARY_LISTENING_FOLDER"] == "learne/listening-audio"


def test_cloudinary_public_id_uses_folder_and_file_stem():
    assert (
        cloudinary_public_id(
            "learne/listening-audio", {"fileName": "listen-a1-cafe-order-02.mp3"}
        )
        == "learne/listening-audio/listen-a1-cafe-order-02"
    )


def test_cloudinary_public_id_reuses_existing_manifest_public_id():
    item = {
        "fileName": "listen-a1-cafe-order-02.mp3",
        "audioPublicId": "learne/listening-audio/existing-public-id",
    }

    assert (
        cloudinary_public_id("learne/listening-audio", item)
        == "learne/listening-audio/existing-public-id"
    )


def test_load_manifest_rejects_item_missing_transcript(tmp_path):
    manifest_file = tmp_path / "listening-manifest.json"
    manifest_file.write_text(
        json.dumps(
            {
                "version": 1,
                "cloudinaryFolder": "learne/listening-audio",
                "items": [
                    {
                        "id": "listen-a1-cafe-order-02",
                        "voice": "en-GB-SoniaNeural",
                        "fileName": "listen-a1-cafe-order-02.mp3",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="transcript"):
        load_manifest(manifest_file)


def test_write_manifest_outputs_stable_pretty_json(tmp_path):
    manifest_file = tmp_path / "listening-manifest.json"
    data = {
        "version": 1,
        "cloudinaryFolder": "learne/listening-audio",
        "items": [
            {
                "id": "listen-a1-cafe-order-02",
                "transcript": "Cafe order",
            }
        ],
    }

    write_manifest(manifest_file, data)

    assert manifest_file.read_text(encoding="utf-8") == (
        '{\n'
        '  "version": 1,\n'
        '  "cloudinaryFolder": "learne/listening-audio",\n'
        '  "items": [\n'
        '    {\n'
        '      "id": "listen-a1-cafe-order-02",\n'
        '      "transcript": "Cafe order"\n'
        '    }\n'
        '  ]\n'
        '}\n'
    )


def test_process_manifest_reuses_existing_public_id_for_upload(monkeypatch, tmp_path):
    manifest_file = tmp_path / "listening-manifest.json"
    output_dir = tmp_path / "audio"
    manifest_file.write_text(
        json.dumps(
            {
                "version": 1,
                "cloudinaryFolder": "learne/listening-audio",
                "items": [
                    {
                        "id": "listen-a1-cafe-order-02",
                        "voice": "en-GB-SoniaNeural",
                        "fileName": "listen-a1-cafe-order-02.mp3",
                        "audioPublicId": "learne/listening-audio/existing-public-id",
                        "audioUrl": "",
                        "transcript": "A customer orders coffee.",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    uploaded_public_ids = []

    async def fake_synthesize_item(item, output_path):
        audio_file = output_path / item["fileName"]
        audio_file.parent.mkdir(parents=True, exist_ok=True)
        audio_file.write_bytes(b"mp3")
        return audio_file

    def fake_upload_audio(path, public_id):
        uploaded_public_ids.append(public_id)
        return {"public_id": public_id, "secure_url": "https://cdn.example/audio.mp3"}

    monkeypatch.setattr(generate_listening_audio, "synthesize_item", fake_synthesize_item)
    monkeypatch.setattr(generate_listening_audio, "upload_audio", fake_upload_audio)
    monkeypatch.setattr(generate_listening_audio, "configure_cloudinary", lambda env: None)
    monkeypatch.setattr(
        generate_listening_audio,
        "merged_env",
        lambda env_file: {
            "CLOUDINARY_CLOUD_NAME": "cloud",
            "CLOUDINARY_API_KEY": "key",
            "CLOUDINARY_API_SECRET": "secret",
        },
    )

    asyncio.run(
        generate_listening_audio.process_manifest(
            SimpleNamespace(
                manifest=manifest_file,
                output_dir=output_dir,
                env_file=None,
                folder=None,
                dry_run=False,
            )
        )
    )

    assert uploaded_public_ids == ["learne/listening-audio/existing-public-id"]
    updated = json.loads(manifest_file.read_text(encoding="utf-8"))
    assert updated["items"][0]["audioPublicId"] == "learne/listening-audio/existing-public-id"


def test_process_manifest_explicit_folder_replaces_existing_public_id(monkeypatch, tmp_path):
    manifest_file = tmp_path / "listening-manifest.json"
    output_dir = tmp_path / "audio"
    manifest_file.write_text(
        json.dumps(
            {
                "version": 1,
                "cloudinaryFolder": "learne/listening-audio",
                "items": [
                    {
                        "id": "listen-a1-cafe-order-02",
                        "voice": "en-GB-SoniaNeural",
                        "fileName": "listen-a1-cafe-order-02.mp3",
                        "audioPublicId": "learne/listening-audio/existing-public-id",
                        "audioUrl": "https://cdn.example/listening-audio/existing-public-id.mp3",
                        "transcript": "A customer orders coffee.",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    uploaded_public_ids = []

    async def fake_synthesize_item(item, output_path):
        audio_file = output_path / item["fileName"]
        audio_file.parent.mkdir(parents=True, exist_ok=True)
        audio_file.write_bytes(b"mp3")
        return audio_file

    def fake_upload_audio(path, public_id):
        uploaded_public_ids.append(public_id)
        return {"public_id": public_id, "secure_url": f"https://cdn.example/{public_id}.mp3"}

    monkeypatch.setattr(generate_listening_audio, "synthesize_item", fake_synthesize_item)
    monkeypatch.setattr(generate_listening_audio, "upload_audio", fake_upload_audio)
    monkeypatch.setattr(generate_listening_audio, "configure_cloudinary", lambda env: None)
    monkeypatch.setattr(
        generate_listening_audio,
        "merged_env",
        lambda env_file: {
            "CLOUDINARY_CLOUD_NAME": "cloud",
            "CLOUDINARY_API_KEY": "key",
            "CLOUDINARY_API_SECRET": "secret",
        },
    )

    asyncio.run(
        generate_listening_audio.process_manifest(
            SimpleNamespace(
                manifest=manifest_file,
                output_dir=output_dir,
                env_file=None,
                folder="learne/listening-audio",
                dry_run=False,
            )
        )
    )

    assert uploaded_public_ids == ["learne/listening-audio/listen-a1-cafe-order-02"]
    updated = json.loads(manifest_file.read_text(encoding="utf-8"))
    assert updated["cloudinaryFolder"] == "learne/listening-audio"
    assert updated["items"][0]["audioPublicId"] == "learne/listening-audio/listen-a1-cafe-order-02"


def test_process_manifest_dry_run_validates_without_audio_or_cloudinary(
    monkeypatch, tmp_path, capsys
):
    manifest_file = tmp_path / "listening-manifest.json"
    manifest_file.write_text(
        json.dumps(
            {
                "version": 1,
                "cloudinaryFolder": "learne/listening-audio",
                "items": [
                    {
                        "id": "listen-a1-cafe-order-02",
                        "voice": "en-GB-SoniaNeural",
                        "fileName": "listen-a1-cafe-order-02.mp3",
                        "transcript": "A customer orders coffee.",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr(
        generate_listening_audio,
        "configure_cloudinary",
        lambda env: pytest.fail("dry-run must not configure Cloudinary"),
    )
    monkeypatch.setattr(
        generate_listening_audio,
        "synthesize_item",
        lambda item, output_dir: pytest.fail("dry-run must not synthesize audio"),
    )

    asyncio.run(
        generate_listening_audio.process_manifest(
            SimpleNamespace(
                manifest=manifest_file,
                output_dir=tmp_path / "audio",
                env_file=None,
                folder=None,
                dry_run=True,
            )
        )
    )

    assert capsys.readouterr().out == "Validated 1 listening manifest items\n"
