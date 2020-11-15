Android UI Checker
=====

![CI](https://github.com/izgzhen/ui-checker/workflows/CI/badge.svg?branch=master)

This is a tool that implements static analysis for extract GUI information and check them against
Datalog-flavor spec.

## Getting Started

Install dependencies:

- Set up Java 8 environment
  - Find instructions online depending on your OS
  - Recommend use https://www.jenv.be/ to make sure you are running JDK8
- Set up SBT (https://www.scala-sbt.org/1.x/docs/Setup.html)
- Install Android SDK and make sure that `ANDROID_SDK_ROOT` and `ANDROID_SDK`
  environment variables are pointed to the SDK directory (which should contains
  `platforms` directory and many others)
- If you are using macOS, you might need to `brew install coreutils gnu-time` as well
- Set up python environment
  - `poetry install`
  - `poetry shell`
  - `make link-venv`
- Install [souffle](https://souffle-lang.github.io/download.html)
- Install submodule dependency: `git submodule update --init`

Run an end-to-end check:

    ./uicheck path/to/apk path/to/spec.dl

Run a simple end-to-end test:

    make test

If the build instructions is not working for you but current CI badge is green, please create an issue and also
check out [the CI build script](.github/workflows/main.yml).
