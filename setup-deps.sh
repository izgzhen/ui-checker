wget --quiet https://github.com/souffle-lang/souffle/releases/download/1.7.1/souffle_1.7.1-1_amd64.deb
sudo apt-get install ./souffle_1.7.1-1_amd64.deb
wget --quiet https://raw.githubusercontent.com/python-poetry/poetry/master/get-poetry.py
pip3 install --upgrade pip
python3 get-poetry.py --version 1.0.2
~/.poetry/bin/poetry install
ln -s $(~/.poetry/bin/poetry env info --path) .venv
