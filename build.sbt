val bitcoinsV = "1.9.1-99-24a9e6a5-SNAPSHOT"
val translndV = "0.1.0-41-cdc05eea-SNAPSHOT"
val akkaV = "2.6.19"

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers +=
  "Sonatype OSS Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots"

lazy val root = project
  .in(file("."))
  .enablePlugins(PlayScala, DebianPlugin)
  .settings(
    name := "op-return-bot",
    version := "0.1.0",
    scalaVersion := "2.13.8",
    maintainer := "benthecarman",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test,
      "com.translnd" %% "pubkey-rotator" % translndV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-fee-provider" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % bitcoinsV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-stream" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-actor-typed" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-slf4j" % akkaV withSources () withJavadoc (),
      "com.danielasfregola" %% "twitter4s" % "8.0",
      "com.softwaremill.sttp.client3" %% "okhttp-backend" % "3.5.2",
      "com.bot4s" %% "telegram-akka" % "5.4.2"
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
