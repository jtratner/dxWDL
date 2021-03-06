package dxWDL.dx

import java.nio.file.{Path, Paths}
import org.scalatest.{FlatSpec, Matchers}
import spray.json._

import dxWDL.base.Utils

class DxdaManifestTest extends FlatSpec with Matchers {

  val TEST_PROJECT = "dxWDL_playground"
  lazy val dxTestProject: DxProject =
    try {
      DxPath.resolveProject(TEST_PROJECT)
    } catch {
      case e: Exception =>
        throw new Exception(
            s"""|Could not find project ${TEST_PROJECT}, you probably need to be logged into
                |the platform on staging.""".stripMargin
        )
    }

  // convert :
  //  { "1": {"md5":"71565d7f4dc0760457eb252a31d45964","size":42,"state":"complete"}}
  // to
  //  { "1": {"md5":"71565d7f4dc0760457eb252a31d45964","size":42}}
  //
  private def cleanPart(jsv: JsValue): JsValue = {
    val fields = jsv.asJsObject.fields.filter {
      case (k, _) =>
        k != "state"
    }.toMap
    JsObject(fields)
  }

  // describe a platform file, including its parts, with the dx-toolkit. We can then compare
  // it to what we get from our DxdaManifest code.
  //
  private def describeWithParts(dxFile: DxFile): JsValue = {
    val cmd = s"""dx api ${dxFile.getId} describe '{"fields": { "parts" : true } }'"""
    val (stdout, stderr) = Utils.execCommand(cmd)
    val jsv = stdout.parseJson

    // remove the "state" field from all the parts
    val fields = jsv.asJsObject.fields.map {
      case ("parts", parts) =>
        "parts" -> JsObject(parts.asJsObject.fields.map {
          case (k, v) => k -> cleanPart(v)
        }.toMap)
      case (k, v) =>
        k -> v
    }.toMap
    JsObject(fields)
  }

  it should "create manifests for dxda" in {

    val fileDir: Map[String, Path] = Map(
        s"dx://${TEST_PROJECT}:/test_data/fileA" -> Paths.get("inputs/A"),
        s"dx://${TEST_PROJECT}:/test_data/fileB" -> Paths.get("inputs/B"),
        s"dx://${TEST_PROJECT}:/test_data/fileC" -> Paths.get("inputs/C")
    )

    // resolve the paths
    val resolvedObjects: Map[String, DxDataObject] =
      DxPath.resolveBulk(fileDir.keys.toVector, dxTestProject)
    val filesInManifest: Map[String, (DxFile, Path)] = resolvedObjects.map {
      case (dxPath, dataObj) =>
        val dxFile = dataObj.asInstanceOf[DxFile]
        val local: Path = fileDir(dxPath)
        dxFile.id -> (dxFile, local)
    }.toMap

    // create a manifest
    val manifest: DxdaManifest = DxdaManifest.apply(filesInManifest)

    // compare to data obtained with dx-toolkit
    val expected: Vector[JsValue] = resolvedObjects.map {
      case (dxPath, dataObj) =>
        val dxFile = dataObj.asInstanceOf[DxFile]
        val local: Path = fileDir(dxPath)
        val jsv = describeWithParts(dxFile)

        // add the target folder and name
        val fields = jsv.asJsObject.fields ++ Map(
            "name" -> JsString(local.toFile().getName()),
            "folder" -> JsString(local.toFile().getParent())
        )
        JsObject(fields)
    }.toVector

    manifest shouldBe (DxdaManifest(
        JsObject(dxTestProject.getId -> JsArray(expected))
    ))
  }

  it should "detect and provide legible error for archived files" in {
    val ARCHIVED_PROJ = "ArchivedStuff"
    val dxArchivedProj: DxProject = DxPath.resolveProject(ARCHIVED_PROJ)

    val fileDir: Map[String, Path] = Map(
        s"dx://${ARCHIVED_PROJ}:/Catch22.txt" -> Paths.get("inputs/A"),
        s"dx://${ARCHIVED_PROJ}:/LICENSE" -> Paths.get("inputs/B"),
        s"dx://${ARCHIVED_PROJ}:/README" -> Paths.get("inputs/C")
    )

    // resolve the paths
    val resolvedObjects: Map[String, DxDataObject] =
      DxPath.resolveBulk(fileDir.keys.toVector, dxArchivedProj)
    val filesInManifest: Map[String, (DxFile, Path)] = resolvedObjects.map {
      case (dxPath, dataObj) =>
        val dxFile = dataObj.asInstanceOf[DxFile]
        val local: Path = fileDir(dxPath)
        dxFile.id -> (dxFile, local)
    }.toMap

    // Creating a manifest should fail, because some of the files are archived
    assertThrows[Exception] {
      DxdaManifest.apply(filesInManifest)
    }
  }
}
