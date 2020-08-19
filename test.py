from path import Path
import os
from uichecker.common import get_successful_results

with Path("markii/tests/test01"):
    os.system("./gradlew assembleDebug")

apk = "markii/tests/test01/app/build/outputs/apk/debug/app-debug.apk"

apk_name = "test01"
os.system("./uicheck " + apk + " tests/test_queries.dl " + apk_name)

results = get_successful_results("output_markii/" + apk_name)
print(results)

assert "hasHelloWorld" in results and results["hasHelloWorld"] != []
assert "hasContinue" in results and results["hasContinue"] != []
assert "hasBackPressed" in results and results["hasBackPressed"] != []
