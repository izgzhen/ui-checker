test:
	python test.py

link-venv:
	ln -s $(shell poetry env info --path) .venv
