Android UI Checker
=====

Dependencies:

- `poetry install; poetry shell`
  + Symbolic link the virtual env (by `which python`, take its grand-parent dir)
    created by poetry to `.venv`
- Install [souffle](https://souffle-lang.github.io/download.html)

Usage:

    ./uicheck path/to/apk path/to/spec.dl
