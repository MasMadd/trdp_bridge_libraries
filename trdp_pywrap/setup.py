from setuptools import setup

setup(
    cffi_modules=["trdp_py/_cffi_build.py:ffi"],
)
