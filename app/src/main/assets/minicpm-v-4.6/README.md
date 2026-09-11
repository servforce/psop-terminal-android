# MiniCPM-V 4.6 bundled model assets

Place these two files in this directory before building the full local-demo APK:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `MiniCPM-V-4_6-Q4_K_M.gguf` | 529101504 | `6b0c74962c44bc6bf4b655b9b02c13eda9d5a0491543ae976d1ac18e4b7892e2` |
| `mmproj-model-f16.gguf` | 1108746944 | `ca931d861d0801d9003e50697cd764721a334107c0e0415a51168ee1938462de` |

Source: <https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/tree/afe9accb78d2995d214cd912920c9c92f4015faa>

The `.gguf` files are intentionally ignored by Git. A build without them remains
usable for the rest of the app; this demo page reports the missing file names.
