val bitcoinsV = "0.4.0+196-2be2df12-SNAPSHOT"

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "op-return-bot",
    version := "0.1.0",
    scalaVersion := "2.13.4",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % bitcoinsV, //withSources () withJavadoc ()
      "org.bitcoin-s" %% "bitcoin-s-eclair-rpc" % bitcoinsV, //withSources () withJavadoc ()
      "org.bitcoin-s" %% "bitcoin-s-cli" % bitcoinsV //withSources () withJavadoc ()
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
