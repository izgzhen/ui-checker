import shutil
import tempfile

temp_dirs = []


def make_temp_dir(prefix=''):
    global temp_dirs
    directory = tempfile.mkdtemp(prefix=prefix)
    temp_dirs.append(directory)
    return directory


def remove_temp_dirs():
    global temp_dirs
    for directory in temp_dirs:
        shutil.rmtree(directory, ignore_errors=True)


def extract_number(cur_line):
    firstCom = cur_line.find("'")
    secondCom = cur_line.find("'", firstCom + 1)
    levelStr = cur_line[firstCom + 1: secondCom]
    return int(levelStr)


def extract_target_api(yml_path):
    with open(yml_path, 'r') as fd:
        for line in fd.readlines():
            if 'targetSdkVersion' in line:
                return extract_number(line)
    return -1
