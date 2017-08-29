/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.sparkcore.fs.local

import slamdata.Predef._
import quasar.{Data, DataCodec}
import quasar.contrib.pathy._
import quasar.contrib.scalaz.readerT._
import quasar.connector.EnvironmentError
import quasar.effect._
import quasar.fp, fp.TaskRef,  fp.free._
import quasar.fs._,
  mount._,
  FileSystemError._, PathError._, WriteFile._,
  BackendDef.{DefinitionError, DefErrT},
  QueryFile.ResultHandle, ReadFile.ReadHandle, WriteFile.WriteHandle
import quasar.physical.sparkcore.fs.{queryfile => corequeryfile, _}
import quasar.physical.sparkcore.fs.SparkCoreBackendModule
import quasar.qscript.{QScriptTotal, Injectable, QScriptCore, EquiJoin, ShiftedRead, ::/::, ::\::}

import java.io.{File, PrintWriter}
import java.io.FileNotFoundException
import java.lang.Exception

import org.apache.spark._
import pathy.Path._
import scalaz.{Failure => _, _}, Scalaz._
import scalaz.concurrent.Task

final case class LocalConfig(sparkConf: SparkConf, prefix: ADir)

object SparkLocalBackendModule extends SparkCoreBackendModule {

  import corequeryfile.RddState

  val Type = FileSystemType("spark-local")

  type Eff1[A]  = Coproduct[KeyValueStore[ResultHandle, RddState, ?], Read[SparkContext, ?], A]
  type Eff2[A]  = Coproduct[KeyValueStore[ReadHandle, SparkCursor, ?], Eff1, A]
  type Eff3[A]  = Coproduct[KeyValueStore[WriteHandle, PrintWriter, ?], Eff2, A]
  type Eff4[A]  = Coproduct[Task, Eff3, A]
  type Eff5[A]  = Coproduct[PhysErr, Eff4, A]
  type Eff6[A]  = Coproduct[MonotonicSeq, Eff5, A]
  type Eff[A]   = Coproduct[SparkConnectorDetails, Eff6, A]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[QSM[T, ?], QScriptTotal[T, ?]] =
        ::\::[QScriptCore[T, ?]](::/::[T, EquiJoin[T, ?], Const[ShiftedRead[AFile], ?]])

  def ReadSparkContextInj = Inject[Read[SparkContext, ?], Eff]
  def RFKeyValueStoreInj = Inject[KeyValueStore[ReadFile.ReadHandle, SparkCursor, ?], Eff]
  def MonotonicSeqInj = Inject[MonotonicSeq, Eff]
  def TaskInj = Inject[Task, Eff]
  def SparkConnectorDetailsInj = Inject[SparkConnectorDetails, Eff]
  def QFKeyValueStoreInj = Inject[KeyValueStore[QueryFile.ResultHandle, corequeryfile.RddState, ?], Eff]
  def wrKvsOps = KeyValueStore.Ops[WriteHandle, PrintWriter, Eff]


  def toLowerLevel[S[_]](sc: SparkContext)(implicit S0: Task :<: S, S1: PhysErr :<: S): Task[Free[Eff, ?] ~> Free[S, ?]] = 
    (TaskRef(0L) |@|
      TaskRef(Map.empty[ResultHandle, RddState]) |@|
      TaskRef(Map.empty[ReadHandle, SparkCursor]) |@|
      TaskRef(Map.empty[WriteHandle, PrintWriter])
    ) {
      (genState, rddStates, sparkCursors, printWriters) =>

      val interpreter: Eff ~> S =
        (queryfile.detailsInterpreter[ReaderT[Task, SparkContext, ?]] andThen runReaderNT(sc) andThen injectNT[Task, S]) :+:
      (MonotonicSeq.fromTaskRef(genState) andThen injectNT[Task, S]) :+:
      injectNT[PhysErr, S] :+:
      injectNT[Task, S]  :+:
      (KeyValueStore.impl.fromTaskRef[WriteHandle, PrintWriter](printWriters) andThen injectNT[Task, S])  :+:
      (KeyValueStore.impl.fromTaskRef[ReadHandle, SparkCursor](sparkCursors) andThen injectNT[Task, S]) :+:
      (KeyValueStore.impl.fromTaskRef[ResultHandle, RddState](rddStates) andThen injectNT[Task, S]) :+:
      (Read.constant[Task, SparkContext](sc) andThen injectNT[Task, S])
      mapSNT(interpreter)
    }

  type Config = LocalConfig
  def parseConfig(uri: ConnectionUri): DefErrT[Task, LocalConfig] = {

    def error(msg: String): DefErrT[Task, LocalConfig] =
      EitherT(NonEmptyList(msg).left[EnvironmentError].left[LocalConfig].point[Task])

    def forge(master: String, rootPath: String): DefErrT[Task, LocalConfig] =
      posixCodec.parseAbsDir(rootPath).map(unsafeSandboxAbs _).cata(
        (prefix: ADir) => {
          val sc = new SparkConf().setMaster(master).setAppName("quasar")
          LocalConfig(sc, prefix).point[DefErrT[Task, ?]]
        }, error(s"Could not extract a path from $rootPath"))

    Task.delay(new File(uri.value).exists()).liftM[DefErrT] >>= (exists => {
      if(exists)
        forge("local[*]", uri.value)
      else
        error(s"Path ${uri.value} does not exist on local file system")
    })
  }

  def generateSC: LocalConfig => DefErrT[Task, SparkContext] = (config: LocalConfig) => EitherT(Task.delay {
    new SparkContext(config.sparkConf).right[DefinitionError]
  }.handleWith {
    case ex : SparkException if ex.getMessage.contains("SPARK-2243") =>
      NonEmptyList("You can not mount second Spark based connector... " +
        "Please unmount existing one first.").left[EnvironmentError].left[SparkContext].point[Task]
  })

  def rebaseAFile(f: AFile): Configured[AFile] =
    Kleisli((config: LocalConfig) => rebaseA(config.prefix).apply(f).point[Free[Eff, ?]])

  def stripPrefixAFile(f: AFile): Configured[AFile] =
    Kleisli((config: LocalConfig) => stripPrefixA(config.prefix).apply(f).point[Free[Eff, ?]])

  def rebaseADir(d: ADir): Configured[ADir] =
    Kleisli((config: LocalConfig) => rebaseA(config.prefix).apply(d).point[Free[Eff, ?]])

  def stripPrefixADir(d: ADir): Configured[ADir] =
    Kleisli((config: LocalConfig) => stripPrefixA(config.prefix).apply(d).point[Free[Eff, ?]])

  object LocalWriteFileModule extends SparkCoreWriteFileModule {
    import WriteFile._

    private def mkParents(f: AFile): Backend[Unit] = includeError(lift(Task.delay {
      val parent = fileParent(f)
        val dir = new File(posixCodec.unsafePrintPath(parent))
        if(!dir.exists()) {
          \/.fromTryCatchNonFatal(dir.mkdirs())
            .leftMap { e =>
            pathErr(invalidPath(parent, "Could not create directories"))
          }.void
        }
        else ().right[FileSystemError]
    }).into[Eff].liftB)

    private def printWriter(f: AFile): Backend[PrintWriter] = includeError(lift(Task.delay {
        val file = new File(posixCodec.unsafePrintPath(f))
          \/.fromTryCatchNonFatal(new PrintWriter(new FileOutputStream(file, true)))
            .leftMap(e => pathErr(pathNotFound(f)))
      }).into[Eff].liftB)

    private def openPrintWriter(f: AFile): Backend[PrintWriter] = mkParents(f) *> printWriter(f)

    def rebasedOpen(file: AFile): Backend[WriteHandle] = for {
      pw <- openPrintWriter(file)
      id <- msOps.next.liftB
      h  = WriteHandle(file, id)
      _  <- wrKvsOps.put(h, pw).liftB
    } yield h

    def rebasedWrite(h: WriteHandle, chunk: Vector[Data]): Configured[Vector[FileSystemError]] = {

      implicit val codec: DataCodec = DataCodec.Precise

      val lines: Vector[(String, Data)] =
        chunk.map(data => DataCodec.render(data) strengthR data).unite

      val _write = for {
        pw <- wrKvsOps.get(h)
        errors <- lift(Task.delay {
          lines flatMap {
            case (line, data) => \/.fromTryCatchNonFatal(pw.append(s"$line\n")).fold(
                ex => Vector(writeFailed(data, ex.getMessage)),
                u => Vector.empty[FileSystemError]
              )
          }
        }).into[Eff].liftM[OptionT]
      } yield errors

      _write.fold(errs => errs, Vector[FileSystemError](unknownWriteHandle(h))).liftM[ConfiguredT]
    }

    def rebasedClose(h: WriteHandle): Configured[Unit] =
      (((wrKvsOps.get(h) <* wrKvsOps.delete(h).liftM[OptionT]) ∘ (_.close)).run.void).liftM[ConfiguredT]
  }

  def WriteFileModule = LocalWriteFileModule

  object LocalManageFileModule extends SparkCoreManageFileModule {
    import org.apache.commons.io.FileUtils
    import java.nio.{file => nio}
    import scala.util.control.NonFatal

    def moveFile(src: AFile, dst: AFile): Free[Eff, Unit] = {
      val move: Task[PhysicalError \/ Unit] = Task.delay {
        val deleted = FileUtils.deleteQuietly(toNioPath(dst).toFile())
        FileUtils.moveFile(toNioPath(src).toFile(), toNioPath(dst).toFile)
      }.as(().right[PhysicalError]).handle {
        case NonFatal(ex : Exception) => UnhandledFSError(ex).left[Unit]
      }
      Failure.Ops[PhysicalError, Eff].unattempt(lift(move).into[Eff])
    }

    def moveDir(src: ADir, dst: ADir): Free[Eff, Unit] = {
      val move: Task[PhysicalError \/ Unit] = Task.delay {
        val deleted = FileUtils.deleteDirectory(toNioPath(dst).toFile())
        FileUtils.moveDirectory(toNioPath(src).toFile(), toNioPath(dst).toFile())
      }.as(().right[PhysicalError]).handle {
        case NonFatal(ex: Exception) => UnhandledFSError(ex).left[Unit]
      }
      Failure.Ops[PhysicalError, Eff].unattempt(lift(move).into[Eff])
    }

    private def toNioPath(path: APath): nio.Path =
      nio.Paths.get(posixCodec.unsafePrintPath(path))

    def doesPathExist: APath => Free[Eff, Boolean] = path => lift(Task.delay {
      nio.Files.exists(toNioPath(path))
    }).into[Eff]


    private def deletePath(path: APath): Backend[Unit] = {
      val del: Task[PhysicalError \/ (FileSystemError \/ Unit)] = Task.delay {
        FileUtils.forceDelete(toNioPath(path).toFile())
      }.as(().right[FileSystemError].right[PhysicalError]).handle {
        case e: FileNotFoundException => pathErr(pathNotFound(path)).left[Unit].right[PhysicalError]
        case NonFatal(e : Exception) => UnhandledFSError(e).left[FileSystemError \/ Unit]
      }
      includeError(Failure.Ops[PhysicalError, Eff].unattempt(lift(del).into[Eff]).liftB)
    }

    def deleteFile(f: AFile): Backend[Unit] = deletePath(f)
    def deleteDir(d: ADir): Backend[Unit]   = deletePath(d)

    private def temp(near: ADir): Backend[AFile] = lift(Task.delay {
      val random = scala.util.Random.nextInt().toString
      near </> file(s"quasar-$random.tmp")
    }).into[Eff].liftB

    def tempFileNearFile(f: AFile) = temp(fileParent(f))
    def tempFileNearDir(d: ADir)   = temp(d)
  }

  def ManageFileModule: ManageFileModule = LocalManageFileModule

}
