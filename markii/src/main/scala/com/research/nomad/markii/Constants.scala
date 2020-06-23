package com.research.nomad.markii

import java.util.regex.Pattern

import com.research.nomad.markii.dataflow.DialogButtonType
import presto.android.MethodNames
import soot.{Scene, SootClass, SootMethod}

/* Created at 3/25/20 by zhen */
object Constants {
  val UNKNOWN = "UNKNOWN"

  // FIXME: This isn't complete
  val activityHandlerSubsigs = Map(
    MethodNames.activityOnBackPressedSubSig -> "backPressed",
    "boolean onKeyDown(int,android.view.KeyEvent)" -> "press_key",
    "boolean onKeyUp(int,android.view.KeyEvent)" -> "press_key",
    MethodNames.onOptionsItemSelectedSubSig -> "itemSelected",
  )

  val layoutParamsClass: SootClass = Scene.v.getSootClass("android.view.ViewGroup$LayoutParams")

  def isWhitelistedMethod(method: SootMethod): Boolean =
    activityHandlerSubsigs.contains(method.getSubSignature) ||
      method.getSubSignature == MethodNames.onActivityCreateSubSig

  val androidViewClassName = "android.view.View"

  val androidActivityClassNames = List(
    "android.app.Activity",
    "android.support.v7.app.AppCompatActivity",
    "androidx.appcompat.app.AppCompatActivity"
  )
  val androidDialogClassName = "android.app.Dialog"
  val androidDialogOnClickListenerClassName = "android.content.DialogInterface$OnClickListener"
  val androidViewOnClickListenerClassName = "android.view.View$OnClickListener"
  val androidViewOnTouchListenerClassName = "android.view.View$OnTouchListener"

  private val viewEventListenerClasses: List[SootClass] = List(
    Scene.v().getSootClass(androidViewOnClickListenerClassName),
    Scene.v().getSootClass(androidViewOnTouchListenerClassName)
  )

  private val viewClass = Scene.v().getSootClass(Constants.androidViewClassName)
  private val activityClasses = androidActivityClassNames.map(Scene.v().getSootClass(_))
  private val serviceClass = Scene.v().getSootClass("android.app.Service")
  private val receiverClass = Scene.v().getSootClass("android.content.BroadcastReceiver")
  private val androidDialogOnClickListenerClass = Scene.v().getSootClass(androidDialogOnClickListenerClassName)

  val guiClasses: List[SootClass] = List(
    viewClass,
    androidDialogOnClickListenerClass
  ) ++ viewEventListenerClasses ++ activityClasses

  def isActivity(c: SootClass): Boolean = activityClasses.exists(activityClass => GUIAnalysis.hier.isSubclassOf(c, activityClass))
  def isService(c: SootClass): Boolean = GUIAnalysis.hier.isSubclassOf(c, serviceClass)
  def isReceiver(c: SootClass): Boolean = GUIAnalysis.hier.isSubclassOf(c, receiverClass)
  def isDialogOnClickListener(c: SootClass): Boolean = GUIAnalysis.hier.isSubclassOf(c, androidDialogOnClickListenerClass)

  def isViewEventListenerClass(sootClass: SootClass): Boolean =
    viewEventListenerClasses.exists(cls => GUIAnalysis.hier.isSubclassOf(sootClass, cls))

  private val dialogClassNames = List(
    "android.support.v7.app.AlertDialog",
    "android.app.AlertDialog",
    "androidx.appcompat.app.AlertDialog",
    "android.app.Dialog"
  )

  def isDialogBuilderClass(sootClass: SootClass): Boolean =
    dialogClassNames.exists(x => GUIAnalysis.hier.isSubclassOf(sootClass, Scene.v().getSootClass(x + "$Builder")))

  def isDialogClass(sootClass: SootClass): Boolean =
    dialogClassNames.exists(x => GUIAnalysis.hier.isSubclassOf(sootClass, Scene.v().getSootClass(x)))

  def isDialogBuilderCreate(sig: String): Boolean =
    dialogClassNames.exists(x => sig == "<" + x + "$Builder: " + x + " create()>")

  def fromDialogBuilderSetButton(subSig: String): Option[DialogButtonType.Value] =
    if (dialogClassNames.exists(x => subSig.startsWith(x + "$Builder setPositiveButton("))) {
      Some(DialogButtonType.POSITIVE)
    } else if (dialogClassNames.exists(x => subSig.startsWith(x + "$Builder setNegativeButton("))) {
      Some(DialogButtonType.NEGATIVE)
    } else {
      None
    }

  def isDialogBuilderSetAny(subSig: String): Boolean =
    dialogClassNames.exists(x => subSig.startsWith(x + "$Builder set"))

  def isDialogSetButton(sig: String): Boolean =
    dialogClassNames.exists(x => sig.startsWith("<" + x + ": void setButton("))

  def isDialogShow(sig: String): Boolean =
    dialogClassNames.exists(x => sig == "<" + x + ": void show()>")

  def isDialogBuilderShow(sig: String): Boolean =
    dialogClassNames.exists(x => sig == "<" + x + "$Builder: " + x + " show()>")

  def isDialogDismiss(sig: String): Boolean =
    dialogClassNames.exists(x => sig == "<" + x + ": void dismiss()>")

  private val adMethodSigs =
    Set("<com.applovin.impl.sdk.NativeAdImpl: void launchClickTarget(android.content.Context)>",
      "<com.startapp.android.publish.StartAppAd: boolean showAd()>",
      "<com.startapp.android.publish.StartAppAd: void showSlider(android.app.Activity)>",
      "<com.startapp.android.publish.StartAppAd: void onBackPressed()>")

  private var adMethods = null

  private val adClassPrefixMethodName = List(
    ("com.ltad.unions.ads", "showAd"),
    ("com.screen.main.CoverAdComponent", "showAd"),
    ("com.Leadbolt.AdController", "loadNotification"))

  def isActivitySetContentViewWithInt(m: SootMethod): Boolean =
    m.getSubSignature == "void setContentView(int)" && isActivity(m.getDeclaringClass)

  def isActivitySetContentViewWithView(m: SootMethod): Boolean =
    m.getSubSignature == "void setContentView(android.view.View)" && isActivity(m.getDeclaringClass)

  def isActivityFindViewById(m: SootMethod): Boolean =
    m.getSubSignature == "android.view.View findViewById(int)" && isActivity(m.getDeclaringClass)

  def isActivityRunOnUiThread(m: SootMethod): Boolean =
    m.getSubSignature == "void runOnUiThread(java.lang.Runnable)" && isActivity(m.getDeclaringClass)

  def isDialogSetContentViewWithInt(m: SootMethod): Boolean =
    m.getSubSignature == "void setContentView(int)" && isDialogClass(m.getDeclaringClass)

  def isDialogSetContentViewWithView(m: SootMethod): Boolean =
    m.getSubSignature == "void setContentView(android.view.View)" && isDialogClass(m.getDeclaringClass)

  def isDialogFindViewById(m: SootMethod): Boolean =
    m.getSubSignature == "android.view.View findViewById(int)" && isDialogClass(m.getDeclaringClass)

  def isViewFindViewById(m: SootMethod): Boolean =
    m.getSignature == "<android.view.View: android.view.View findViewById(int)>"

  private val adClassSuffixMethodName = List(("LGUDMPAdView", "execute"))

  private val suspiciousAdClassPrefixMethodName = List(("com.google.android.gms.ads.InterstitialAd", "loadAd"))

  private val adNamePattern = Pattern.compile("(show|start|init).*(Ad|Offers)")
  private val classNamePattern = Pattern.compile(".+\\.(AirPlay|AppConnect|QuMiConnect)")
  private val adViewClassNamePattern = Pattern.compile(".+\\.(AdView)")

  // TODO: might use some from [[adMethodSigs]] and [[adClassPrefixMethodNames]]
  private val adViewClassNames = Set("com.google.android.gms.ads.formats.MediaView")

  def isAdViewClass(clz: SootClass): Boolean = {
    if (adViewClassNames.contains(clz.getName)) return true
    adViewClassNamePattern.matcher(clz.getName).matches
  }

  def isAdMethod(m: SootMethod): Boolean = {
    if (adMethodSigs.contains(m.getSignature)) return true
    for ((prefix, methodName) <- adClassPrefixMethodName) {
      if (m.getName == methodName && m.getDeclaringClass.getName.startsWith(prefix)) return true
    }
    for ((suffix, methodName) <- adClassSuffixMethodName) {
      if (m.getName == methodName && m.getDeclaringClass.getName.endsWith(suffix)) return true
    }
    if (adNamePattern.matcher(m.getName).matches && classNamePattern.matcher(m.getDeclaringClass.getName).matches) return true
    false
  }

  def isSuspiciousAdMethod(m: SootMethod): Boolean = {
    for ((classPrefix, methodName) <- suspiciousAdClassPrefixMethodName) {
      if (m.getName == methodName && m.getDeclaringClass.getName.startsWith(classPrefix)) return true
    }
    false
  }

  def isInterstitialAdMethod(m: SootMethod) = {
    val methodName = m.getName.toLowerCase
    val className = m.getDeclaringClass.getName.toLowerCase
    val hasVerb = methodName.contains("show")
    val hasNoun = className.contains("interstitial") || methodName.contains("interstitial")
    (!methodName.startsWith("on")) && (!methodName.startsWith("maybe")) && hasNoun && hasVerb
  }

  def isSuspiciousInterstitialAdMethod(m: SootMethod) = {
    val methodName = m.getName.toLowerCase
    val hasVerb = methodName.contains("load")
    val hasNoun = methodName.contains("interstitial")
    hasNoun && hasVerb
  }

  def layoutParamIntToString(i: Int): String = {
    if (i == -1) {
      "fill_parent"
    } else if (i == -2) {
      "wrap_content"
    } else {
      i.toString + "dip"
    }
  }

  val runnerMethodName = "run_markii_generated"
}
