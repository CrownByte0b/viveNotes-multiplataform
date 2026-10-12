# On-device recognition and math artifacts

viveNotes includes or downloads the following machine-learning artifacts for offline recognition.
They are the Android app's artifacts, pinned to the same byte counts and SHA-256 digests.

## en_PP-OCRv5_mobile_rec

- Upstream model: <https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec>
- ONNX conversion: <https://github.com/GreatV/oar-ocr>
- License: Apache License 2.0
- Bundled graph SHA-256:
  `8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac`
- Bundled dictionary SHA-256:
  `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`

## PP-OCRv5_mobile_det

- Upstream model: <https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det>
- ONNX conversion: <https://github.com/GreatV/oar-ocr>
- ONNX download: <https://github.com/GreatV/oar-ocr/releases/tag/v0.3.0>
- License: Apache License 2.0
- Bundled graph SHA-256:
  `1eb7b4f7ab657ebd1c66d5f79bca7497f29768a2e3c15e52daecbba1a8e4a039`

## UniMERNet-T

- Upstream model: <https://huggingface.co/wanderkid/unimernet_tiny>
- Upstream project: <https://github.com/opendatalab/UniMERNet>
- License: Apache License 2.0
- ONNX conversion and int8 weight quantization: `simulations/formula-models` in the viveNotes
  Android repository
- ONNX download: <https://github.com/AquilaIgnis/viveNotes/releases/tag/models-unimernet-tiny-v1>
- Tokenizer: shared with PP-FormulaNet-S, byte-identical apart from a trailing newline
- Downloaded encoder SHA-256:
  `cf6cc98a54c97254adee4e251f56af2da045062503663b001a254303dab2aa97`
- Downloaded decoder SHA-256:
  `b765a9c522f43cff7dcc6379e9c39dfc441d6ee189822cb71be8f5e7cd703a69`

## PP-FormulaNet-S

- Upstream model: <https://huggingface.co/PaddlePaddle/PP-FormulaNet-S>
- ONNX conversion and tokenizer: <https://github.com/GreatV/oar-ocr>
- ONNX download: <https://github.com/GreatV/oar-ocr/releases/tag/v0.3.0>
- Byte-identical tokenizer mirror:
  <https://huggingface.co/PaddlePaddle/PP-FormulaNet-L_safetensors>
- License: Apache License 2.0
- Downloaded graph SHA-256:
  `0ee32c7bfbd9e586364f89f71860476ccb5334e35674a61f3df5e0553d6a6dcc`
- Downloaded tokenizer SHA-256:
  `2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2`

## ONNX Runtime

- Project: <https://github.com/microsoft/onnxruntime>
- Desktop package: `com.microsoft.onnxruntime:onnxruntime:1.27.0`
- License: MIT

## Math engine

Installed on request from Settings → Models by [uv](https://github.com/astral-sh/uv) (MIT or
Apache-2.0), which the app runs but does not include. The versions are pinned by
`math-engine/uv.lock` and `math-engine/.python-version`:

- CPython 3.13.15 — Python Software Foundation License; uv installs the
  [python-build-standalone](https://github.com/astral-sh/python-build-standalone) build
- SymPy 1.14.0 — BSD 3-Clause License
- mpmath 1.3.0 — BSD 3-Clause License
- antlr4-python3-runtime 4.11.1 — BSD 3-Clause License

The files are used locally. viveNotes does not upload ink, pictures, formulas or results.
