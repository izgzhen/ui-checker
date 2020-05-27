all:
	bash ./build-run-markii.sh ../../examples/airquality-badui.apk output/facts output/info.json

test01:
	bash ./build-run-markii.sh ../tests/test01/app/build/outputs/apk/debug/app-debug.apk output/facts output/info.json

JAVA_FILES := $(shell find src -name "*.java")
SCALA_FILES := $(shell find src -name "*.scala")

JARFILE := target/scala-2.13/markii-assembly-0.1.jar

$(JARFILE): $(JAVA_FILES) $(SCALA_FILES)
	sbt assembly

jar: $(JARFILE)
