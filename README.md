Android UI Checker
=====

This is a tool that implements static analysis for extract GUI information and check them against
Datalog-flavour spec.

## Getting Started

Install dependencies:

- Set up Java 8 environemnt
  - Find instructions online depending on your OS
  - Recommmend use https://www.jenv.be/ to make sure you are running JDK8
- Set up python environment
  - `poetry install`
  - `poetry shell`
  - `make link-venv`
- Install [souffle](https://souffle-lang.github.io/download.html)

Run an end-to-end check:

    ./uicheck path/to/apk path/to/spec.dl

Run a simple end-to-end test:

    make test
