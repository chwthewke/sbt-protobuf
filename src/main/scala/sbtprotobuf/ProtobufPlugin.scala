package sbtprotobuf

import sbt._
import Process._
import Keys._

import java.io.File


object ProtobufPlugin extends Plugin {
  val protobufConfig = config("protobuf")

  val includePaths = TaskKey[Seq[File]]("protobuf-include-paths", "The paths that contain *.proto dependencies.")
  val protoc = SettingKey[String]("protobuf-protoc", "The path+name of the protoc executable.")
  val externalIncludePath = SettingKey[File]("protobuf-external-include-path", "The path to which protobuf:library-dependencies are extracted and which is used as protobuf:include-path for protoc")

  val dependOnProtobufJava = SettingKey[Boolean]("protobuf-depend-protobuf-java", "Whether to add protobuf-kava as a library dependency.")

  val plugins = SettingKey[Seq[ProtocPlugin]]("protobuf-plugins", "The name, output directory and optional executables of the plugins to use with protoc.")

  val generate = TaskKey[Seq[File]]("protobuf-generate", "Compile the protobuf sources.")
  val unpackDependencies = TaskKey[UnpackedDependencies]("protobuf-unpack-dependencies", "Unpack dependencies.")

  lazy val protobufSettings: Seq[Setting[_]] = inConfig(protobufConfig)(Seq[Setting[_]](
    sourceDirectory <<= (sourceDirectory in Compile) { _ / "protobuf" },
    javaSource <<= (sourceManaged in Compile) { _ / "compiled_protobuf" },
    externalIncludePath <<= target(_ / "protobuf_external"),
    protoc := "protoc",
    version := "2.4.1",
    plugins <<= (javaSource in protobufConfig) { s => Seq(ProtocPlugin("java", s, None, _ ** "*.java")) },
    dependOnProtobufJava <<= (plugins in protobufConfig) (_.exists(plg => plg.name == "java" && plg.executable == None)),


    managedClasspath <<= (classpathTypes, update) map { (ct, report) =>
      Classpaths.managedJars(protobufConfig, ct, report)
    },

    unpackDependencies <<= unpackDependenciesTask,

    includePaths <<= (sourceDirectory in protobufConfig) map (identity(_) :: Nil),
    includePaths <+= unpackDependencies map { _.dir },

    generate <<= sourceGeneratorTask

  )) ++ Seq[Setting[_]](
    sourceGenerators in Compile <+= generate in protobufConfig,
    managedSourceDirectories in Compile <++= (plugins in protobufConfig) {_.map(_.outputDirectory)},
    cleanFiles <++= (plugins in protobufConfig) {_.map(_.outputDirectory)},
    libraryDependencies <++= (plugins in protobufConfig, version in protobufConfig)(protobufJavaDependency),
    ivyConfigurations += protobufConfig
  )

  case class UnpackedDependencies(dir: File, files: Seq[File])

  case class ProtocPlugin(name: String, outputDirectory: File, executable: Option[File], filter: File => PathFinder) {
    def args: Seq[String] = Seq("--%s_out=%s".format(name, outputDirectory.absolutePath)) ++
      executable.map(exe => "--plugin=protoc-gen-%s=%s".format(name, exe.absolutePath))

    def generated: Seq[File] = filter(outputDirectory).get
  }

  private def protobufJavaDependency(plugins: Seq[ProtocPlugin], version: String): Seq[ModuleID] =
    if (plugins.exists(pl => pl.name == "java" && pl.executable == None))
      Seq("com.google.protobuf" % "protobuf-java" % version)
    else Nil

  private def executeProtoc(protocCommand: String, srcDir: File, includePaths: Seq[File], plg: Seq[ProtocPlugin], log: Logger) =
    try {
      val schemas = (srcDir ** "*.proto").get.map(_.absolutePath)
      val incPath = includePaths.map("-I" + _.absolutePath)
      val plugins = plg.flatMap(_.args)

      val protocArgs: Seq[String] = incPath ++ plugins ++ schemas
      val proc = Process(protocCommand, protocArgs)
      proc ! log
    } catch { case e: Exception =>
      throw new RuntimeException("error occured while compiling protobuf files: %s" format(e.getMessage), e)
    }


  private def compile(protocCommand: String, srcDir: File, includePaths: Seq[File], plg: Seq[ProtocPlugin], log: Logger) = {
    val schemas = (srcDir ** "*.proto").get
    plg.foreach(_.outputDirectory.mkdirs())

    log.info("Compiling %d protobuf files to %s".format(schemas.size, plg.map(_.outputDirectory).mkString(", ")))
    schemas.foreach { schema => log.info("Compiling schema %s" format schema) }

    val exitCode = executeProtoc(protocCommand, srcDir, includePaths, plg, log)
    if (exitCode != 0)
      sys.error("protoc returned exit code: %d" format exitCode)

    plg.flatMap(_.generated).toSet
  }

  private def unpack(deps: Seq[File], extractTarget: File, log: Logger): Seq[File] = {
    IO.createDirectory(extractTarget)
    deps.flatMap { dep =>
      val seq = IO.unzip(dep, extractTarget, "*.proto").toSeq
      if (!seq.isEmpty) log.debug("Extracted " + seq.mkString(","))
      seq
    }
  }

  private def sourceGeneratorTask = (streams, sourceDirectory in protobufConfig, includePaths in protobufConfig, cacheDirectory, protoc, plugins) map {
    (out, srcDir, includePaths, cache, protocCommand, plg) =>
      val cachedCompile = FileFunction.cached(cache / "protobuf", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(protocCommand, srcDir, includePaths, plg, out.log)
      }
      cachedCompile((srcDir ** "*.proto").get.toSet).toSeq
  }

  private def unpackDependenciesTask = (streams, managedClasspath in protobufConfig, externalIncludePath in protobufConfig) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }
}
