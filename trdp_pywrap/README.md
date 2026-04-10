# trdp-py (skeleton)

This is a minimal Python wrapper skeleton for **TRDP Light** (TCNOpen TRDP, v3.0.0.0).

## 1) Build the native library (Linux)
From the TRDP sources root (the directory that contains `Makefile`):

```bash
make LINUX_X86_64_config
make -j
```

The build produces a static library under `bld/output/*/libtrdp.a`.

### Build a shared library (`libtrdp.so`)
Run:

```bash
bash build_scripts/build_shared_linux.sh /path/to/trdp-sources
```

It creates `libtrdp.so` inside the same output folder.

## 2) Build the Python extension
In this folder:

```bash
python -m pip install -U pip
python -m pip install -e .
```

You can also build a wheel with:

```bash
python -m pip wheel .
```

## 3) Usage
```python
from trdp_py import Session

with Session(own_ip="10.0.8.35") as s:
    pub = s.publish(com_id=1000, dest_ip="239.255.0.1", interval_us=100000, data=b"hello")
    s.put(pub, b"hello again")

    sub = s.subscribe(com_id=2000, src_ip1="10.0.8.10", timeout_us=200000)
    # poll once
    s.process_once(timeout_ms=50)
    msg = s.get(sub)
    if msg:
        info, payload = msg
        print(len(payload), payload)
```

## Notes
- This skeleton wraps only a **small** part of the API (init/session/publish/subscribe/get/process).
- TRDP wants you to call `tlc_getInterval()` + `tlc_process()` regularly (or use the split PD/MD functions).
