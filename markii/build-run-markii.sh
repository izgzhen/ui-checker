# This script will mount the current source directory to docker and build the
# artifact incrementally before running on the target APK

set -e
set -x

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

APK_PATH=$(realpath $1)
OUTPUT_PATH=$(realpath $2)

cd $DIR
if [ -z "$BATCH_RUN" ]; then
    ./markii b
fi
./markii a -p $APK_PATH -clientParam output:$OUTPUT_PATH
