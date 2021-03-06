import com.softwaremill.Publish.Release.updateVersionInDocs
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._

val scala2_11 = "2.11.12"
val scala2_12 = "2.12.11"
val scala2_13 = "2.13.3"
val scala3 = "0.26.0-RC1"

val scalaTestVersion = "3.2.2"
val scalaNativeTestInterfaceVersion = "0.4.0-M2"

def dependenciesFor(version: String)(deps: (Option[(Long, Long)] => ModuleID)*): Seq[ModuleID] =
  deps.map(_.apply(CrossVersion.partialVersion(version)))

val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.sttp.model",
  scmInfo := Some(
    ScmInfo(url("https://github.com/softwaremill/sttp-model"), "scm:git@github.com:softwaremill/sttp-model.git")
  ),
  // cross-release doesn't work when subprojects have different cross versions
  // work-around from https://github.com/sbt/sbt-release/issues/214
  releaseCrossBuild := false,
  releaseProcess := Seq(
    checkSnapshotDependencies,
    inquireVersions,
    // publishing locally so that the pgp password prompt is displayed early
    // in the process
    releaseStepCommandAndRemaining("publishLocalSigned"),
    releaseStepCommandAndRemaining("clean"),
    releaseStepCommandAndRemaining("test"),
    setReleaseVersion,
    updateVersionInDocs(organization.value),
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    pushChanges
  ),
  // doc generation is broken in dotty
  sources in (Compile, doc) := {
    val scalaV = scalaVersion.value
    val current = (sources in (Compile, doc)).value
    if (scalaV == scala3) Seq() else current
  }
)

val commonJvmSettings = commonSettings ++ Seq(
  scalacOptions ++= Seq("-target:jvm-1.8"),
  scalacOptions := {
    val current = scalacOptions.value
    // https://github.com/lampepfl/dotty/pull/7775
    if (isDotty.value) current ++ List("-language:implicitConversions", "-Ykind-projector") else current
  },
  ideSkipProject := (scalaVersion.value != scala2_13),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )
)

val commonJsSettings = commonSettings ++ Seq(
  // https://github.com/scalaz/scalaz/pull/1734#issuecomment-385627061
  scalaJSLinkerConfig ~= {
    _.withBatchMode(System.getenv("CONTINUOUS_INTEGRATION") == "true")
  },
  scalacOptions in Compile ++= {
    if (isSnapshot.value) Seq.empty
    else
      Seq {
        val dir = project.base.toURI.toString.replaceFirst("[^/]+/?$", "")
        val url = "https://raw.githubusercontent.com/softwaremill/sttp-model"
        s"-P:scalajs:mapSourceURI:$dir->$url/v${version.value}/"
      }
  },
  ideSkipProject := true,
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "1.1.0",
    "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
  )
)

val commonNativeSettings = commonSettings ++ Seq(
  nativeLinkStubs := true,
  ideSkipProject := true,
  libraryDependencies ++= Seq(
    "org.scala-native" %%% "test-interface" % scalaNativeTestInterfaceVersion,
    "org.scalatest" %%% "scalatest" % scalaTestVersion % Test
  )
)

lazy val downloadChromeDriver = taskKey[Unit]("Download chrome driver corresponding to installed google-chrome version")
Global / downloadChromeDriver := {
  if (java.nio.file.Files.notExists(new File("target", "chromedriver").toPath)) {
    println("ChromeDriver binary file not found. Detecting google-chrome version...")
    import sys.process._
    val osName = sys.props("os.name")
    val isMac = osName.toLowerCase.contains("mac")
    val isWin = osName.toLowerCase.contains("win")
    val chromeVersionExecutable =
      if (isMac)
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
      else "google-chrome"
    val chromeVersion = Seq(chromeVersionExecutable, "--version").!!.split(' ')(2)
    println(s"Detected google-chrome version: $chromeVersion")
    val withoutLastPart = chromeVersion.split('.').dropRight(1).mkString(".")
    println(s"Selected release: $withoutLastPart")
    val latestVersion =
      IO.readLinesURL(new URL(s"https://chromedriver.storage.googleapis.com/LATEST_RELEASE_$withoutLastPart")).mkString
    val platformDependentName = if (isMac) {
      "chromedriver_mac64.zip"
    } else if (isWin) {
      "chromedriver_win32.zip"
    } else {
      "chromedriver_linux64.zip"
    }
    println(s"Downloading chrome driver version $latestVersion for $osName")
    IO.unzipURL(
      new URL(s"https://chromedriver.storage.googleapis.com/$latestVersion/$platformDependentName"),
      new File("target")
    )
    IO.chmod("rwxrwxr-x", new File("target", "chromedriver"))
  } else {
    println("Detected chromedriver binary file, skipping downloading.")
  }
}

// run JS tests inside Chrome, due to jsdom not supporting fetch
lazy val browserTestSettings = Seq(
  jsEnv in Test := {
    val debugging = false // set to true to help debugging
    System.setProperty("webdriver.chrome.driver", "target/chromedriver")
    new org.scalajs.jsenv.selenium.SeleniumJSEnv(
      {
        val options = new org.openqa.selenium.chrome.ChromeOptions()
        val args = Seq(
          "auto-open-devtools-for-tabs", // devtools needs to be open to capture network requests
          "no-sandbox",
          "allow-file-access-from-files" // change the origin header from 'null' to 'file'
        ) ++ (if (debugging) Seq.empty else Seq("headless"))
        options.addArguments(args: _*)
        val capabilities = org.openqa.selenium.remote.DesiredCapabilities.chrome()
        capabilities.setCapability(org.openqa.selenium.chrome.ChromeOptions.CAPABILITY, options)
        capabilities
      },
      org.scalajs.jsenv.selenium.SeleniumJSEnv.Config().withKeepAlive(debugging)
    )
  },
  test in Test := (test in Test)
    .dependsOn(downloadChromeDriver in Global)
    .value
)

lazy val projectAggregates: Seq[ProjectReference] = if (sys.env.isDefinedAt("STTP_NATIVE")) {
  println("[info] STTP_NATIVE defined, including sttp-native in the aggregate projects")
  core.projectRefs
} else {
  println("[info] STTP_NATIVE *not* defined, *not* including sttp-native in the aggregate projects")
  List(
    core.jvm(scala2_11),
    core.jvm(scala2_12),
    core.jvm(scala2_13),
    core.jvm(scala3),
    core.js(scala2_11),
    core.js(scala2_12),
    core.js(scala2_13)
  )
}

val compileAndTest = "compile->compile;test->test"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(skip in publish := true, name := "sttp-model")
  .aggregate(projectAggregates: _*)

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "core"
  )
  .jvmPlatform(
    scalaVersions = List(scala2_11, scala2_12, scala2_13, scala3),
    settings = commonJvmSettings
  )
  .jsPlatform(
    scalaVersions = List(scala2_11, scala2_12, scala2_13),
    settings = commonJsSettings ++ browserTestSettings
  )
  .nativePlatform(
    scalaVersions = List(scala2_11),
    settings = commonNativeSettings
  )
