# On-device recognition and math artifacts

viveNotes includes or downloads the following machine-learning artifacts for offline recognition.
The desktop bundles PP-OCRv6 Small for text and downloads formula models. Legacy PP-OCRv5 assets
are retained only in Android compatibility test resources and are not shipped in the app.

## PP-OCRv6 Small

- Upstream project: <https://github.com/PaddlePaddle/PaddleOCR>
- Upstream models: <https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec> and
  <https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det>
- ONNX artifacts and dictionary: <https://github.com/GreatV/oar-ocr/releases/tag/v0.7.0>
- License: Apache License 2.0, Copyright PaddlePaddle Authors
- Bundled recognizer `pp-ocrv6_small_rec.onnx`, 21,159,378 bytes, SHA-256:
  `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`
- Bundled detector `pp-ocrv6_small_det.onnx`, 9,880,512 bytes, SHA-256:
  `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`
- Bundled dictionary `ppocrv6_dict.txt`, 74,947 bytes, SHA-256:
  `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d`

## en_PP-OCRv5_mobile_rec

- Android compatibility test fixtures only.
- Upstream model: <https://huggingface.co/PaddlePaddle/en_PP-OCRv5_mobile_rec>
- ONNX conversion: <https://github.com/GreatV/oar-ocr>
- License: Apache License 2.0
- Test graph SHA-256:
  `8307465d3c9ef2ba4055c3bd0be55aafe11f518630212b7598b70ccb376028ac`
- Test dictionary SHA-256:
  `e025a66d31f327ba0c232e03f407ae8d105e1e709e7ccb3f408aa778c24e70d6`

## PP-OCRv5_mobile_det

- Android compatibility test fixture only.
- Upstream model: <https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det>
- ONNX conversion: <https://github.com/GreatV/oar-ocr>
- ONNX download: <https://github.com/GreatV/oar-ocr/releases/tag/v0.3.0>
- License: Apache License 2.0
- Test graph SHA-256:
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

## UniMERNet Small

- Upstream model: <https://huggingface.co/wanderkid/unimernet_small>
- Source revision: `82cb85440138c2038680fca410c8f70bb7343d6d`
- Upstream project: <https://github.com/opendatalab/UniMERNet>
- License: Apache License 2.0
- ONNX conversion and INT8 MatMul/Gather weight quantization: `tools/unimernet/` in this repository
- ONNX and tokenizer download:
  <https://github.com/AquilaIgnis/viveNotes/releases/tag/models-unimernet-small-v1>
- Downloaded encoder SHA-256:
  `4982c38cbed338e5bdc24420322128c63bef7199415eb8662c74a32fbdf665d8`
- Downloaded decoder SHA-256:
  `6fb715a5e9b840b0e4b8f9cb98dbe815f5563c18c49ed8938d630e145820140e`
- Downloaded tokenizer SHA-256:
  `02c318d9cfa95bf323371762b8f838a82709530274d36dba6eca880f0add6cc4`

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
