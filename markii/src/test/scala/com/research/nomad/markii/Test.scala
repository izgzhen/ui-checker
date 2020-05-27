package com.research.nomad.markii

import junit.framework.TestCase
import org.junit.Assert._
import org.junit.Test
import presto.android.Configs

object TestAnalysis1 extends IAnalysis {
  override def run(): Unit = {
    assert(Constants.isDialogBuilderShow("<android.app.AlertDialog$Builder: android.app.AlertDialog show()>"))
  }
}

/* Created at 5/29/20 by zhen */
class TestAnalysis extends TestCase {
  @Test def testBasic(): Unit = {
    val androidSdk = sys.env("ANDROID_SDK")
    val androidPlatforms =androidSdk + "/platforms"
    val androidJar = androidPlatforms + "/android-29/android.jar"
    Configs.customAnalysis = TestAnalysis1
    Configs.runCustomAnalysis = true
    val args = List(
      "-configDir", "config",
      "-sdkDir", androidSdk,
      "-listenerSpecFile", "config/listeners.xml",
      "-wtgSpecFile", "config/wtg.xml",
      "-resourcePath", "src/test/resources/res",
      "-manifestFile", "src/test/resources/AndroidManifest.xml",
      "-project", "src/test/resources/app-debug.apk",
      "-apiLevel", "android-29",
      "-benchmarkName", "app-debug.apk",
      "-libraryPackageListFile", "config/libPackages.txt",
      "-android", androidJar,
      "-enableStringPropertyAnalysis",
    )
    presto.android.Main.main(args.toArray)
  }

//  private def withHarness(apkPath: Option[String] = None): Unit = {
//
//  }
}
