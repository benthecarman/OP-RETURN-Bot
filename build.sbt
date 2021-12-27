val bitcoinsV = "1.8.0-87-3cd57d37-SNAPSHOT"
val akkaV = "2.6.18"

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

lazy val root = project
  .in(file("."))
  .enablePlugins(PlayScala, DebianPlugin, FlywayPlugin)
  .settings(
    name := "op-return-bot",
    version := "0.1.0",
    scalaVersion := "2.13.6",
    maintainer := "benthecarman",
    libraryDependencies ++= Seq(
      guice,
      "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % Test,
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-fee-provider" % bitcoinsV withSources () withJavadoc (),
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % bitcoinsV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-stream" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-actor-typed" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaV withSources () withJavadoc (),
      "com.typesafe.akka" %% "akka-slf4j" % akkaV withSources () withJavadoc (),
      "com.danielasfregola" %% "twitter4s" % "7.0",
      "com.bot4s" %% "telegram-akka" % "5.2.0"
    ),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-Xfatal-warnings"
    )
  )
