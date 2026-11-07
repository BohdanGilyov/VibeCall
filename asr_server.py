import json
import os
import tempfile
import site

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

_dll_handles = []

for root in site.getsitepackages():
    for library in ("cublas", "cudnn"):
        folder = Path(root) / "nvidia" / library / "bin"

        if folder.is_dir():
            _dll_handles.append(
                os.add_dll_directory(str(folder))
            )

            os.environ["PATH"] = (
                    str(folder)
                    + os.pathsep
                    + os.environ.get("PATH", "")
            )

            print("NVIDIA DLL:", folder, flush=True)

from faster_whisper import WhisperModel
from huggingface_hub import snapshot_download

HOST = "127.0.0.1"
PORT = 47630

REPO = "coriollon/whisper-large-v3-turbo-russian-codeswitch"

print("Загружаю модель Whisper RU+EN...", flush=True)

root = snapshot_download(
    repo_id=REPO,
    allow_patterns="ct2_int8_float16/*"
)

model_path = Path(root) / "ct2_int8_float16"

model = WhisperModel(
    str(model_path),
    device="cuda",
    compute_type="int8_float16"
)

lock = Lock()

print(
    "Whisper готов: http://127.0.0.1:47630",
    flush=True
)


class Handler(BaseHTTPRequestHandler):

    def do_POST(self):
        if self.path != "/transcribe":
            self.answer(404, {"error": "Not found"})
            return

        try:
            size = int(
                self.headers.get("Content-Length", "0")
            )

            if size < 44 or size > 100 * 1024 * 1024:
                self.answer(
                    400,
                    {"error": "Некорректный размер WAV"}
                )
                return

            audio = self.rfile.read(size)

            with tempfile.NamedTemporaryFile(
                suffix=".wav",
                delete=False
            ) as temp:
                temp.write(audio)
                path = temp.name

            try:
                with lock:
                    segments, info = model.transcribe(
                        path,
                        language="ru",
                        task="transcribe",
                        beam_size=5,
                        vad_filter=True,
                        condition_on_previous_text=False,
                        initial_prompt=(
                            "Python, Java, GitHub, Git, commit, "
                            "push, Docker, HashMap, IntelliJ IDEA, "
                            "ChatGPT, OpenAI, Rust, SQL, Windows"
                        )
                    )

                    text = " ".join(
                        segment.text.strip()
                        for segment in segments
                    ).strip()

                self.answer(
                    200,
                    {
                        "text": text,
                        "language": info.language
                    }
                )

            finally:
                os.unlink(path)

        except Exception as exc:
            print(
                "Ошибка распознавания:",
                repr(exc),
                flush=True
            )

            self.answer(
                500,
                {"error": str(exc)}
            )

    def answer(self, code, obj):
        body = json.dumps(
            obj,
            ensure_ascii=False
        ).encode("utf-8")

        self.send_response(code)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8"
        )

        self.send_header(
            "Content-Length",
            str(len(body))
        )

        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(
        (HOST, PORT),
        Handler
    )

    server.serve_forever()
