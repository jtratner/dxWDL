// Intermediate Representation (IR)
//
// Representation the compiler front end generates from a WDL
// workflow. The compiler back end uses it to generate a
// dx:workflow. A more detailed description can be found at
// ToplevelDir/[IntermediateForm.md].
//
// We use YAML as a human readable representation of the IR.
package dxWDL.compiler

import wom.callable.CallableTaskDefinition
import wom.types.WomType
import wom.values.WomValue

import dxWDL.base.Utils
import dxWDL.dx.{DxFile, DxWorkflowStage}

object IR {
  // stages that the compiler uses in generated DNAx workflows
  val COMMON = "common"
  val OUTPUT_SECTION = "outputs"
  val REORG = "reorg"
  val CUSTOM_REORG_CONFIG = "reorg_config"

  // Keywords for string pattern matching
  val PARAM_META_HELP = "help"
  val PARAM_META_PATTERNS = "patterns"

  /** Compile time representation of the dxapp IO spec patterns
    *  Example:
    *  'patterns': { // PatternsReprObj
    *    'name': ['*.sam', '*.bam'],
    *    'class': 'file',
    *    'tag': ['foo', 'bar']
    *  }
    *   OR
    * 'patterns': ['*.sam', '*.bam'] // PatternsReprArray
    *
   **/
  sealed abstract class PatternsRepr
  final case class PatternsReprArray(patterns: Vector[String]) extends PatternsRepr
  final case class PatternsReprObj(name: Option[Vector[String]],
                                   klass: Option[String],
                                   tag: Option[Vector[String]])
      extends PatternsRepr

  // Compile time representaiton of supported parameter_meta section
  // information for the dxapp IO spec
  sealed abstract class IOAttr
  final case class IOAttrHelp(text: String) extends IOAttr
  final case class IOAttrPatterns(patternRepr: PatternsRepr) extends IOAttr

  // Compile time representation of a variable. Used also as
  // an applet argument.
  //
  // The fullyQualifiedName could contains dots. However dx does not allow
  // dots in applet/workflow arugment names, this requires some kind
  // of transform.
  //
  //
  // The attributes are used to encode DNAx applet input/output
  // specification fields, such as {help, suggestions, patterns}.
  //
  case class CVar(
      name: String,
      womType: WomType,
      default: Option[WomValue],
      attrs: Option[Vector[IOAttr]] = None
  ) {
    // dx does not allow dots in variable names, so we
    // convert them to underscores.
    //
    // TODO: check for collisions that are created this way.
    def dxVarName: String = {
      val nameNoDots = Utils.transformVarName(name)
      assert(!(nameNoDots contains "."))
      nameNoDots
    }
  }

  /** Specification of instance type.
    *
    *  An instance could be:
    *  Default: the platform default, useful for auxiliary calculations.
    *  Const:   instance type is known at compile time. We can start the
    *           job directly on the correct instance type.
    *  Runtime: WDL specifies a calculation for the instance type, based
    *           on information known only at runtime. The generated applet
    *           will need to evalulate the expressions at runtime, and then
    *           start another job on the correct instance type.
    */
  sealed trait InstanceType
  case object InstanceTypeDefault extends InstanceType
  case class InstanceTypeConst(
      dxInstanceType: Option[String],
      memoryMB: Option[Int],
      diskGB: Option[Int],
      cpu: Option[Int],
      gpu: Option[Boolean]
  ) extends InstanceType
  case object InstanceTypeRuntime extends InstanceType

  // A task may specify a docker image to run under. There are three
  // supported options:
  //  None:    no image
  //  Network: the image resides on a network site and requires download
  //  DxAsset: the image is a platform asset
  //
  sealed trait DockerImage
  case object DockerImageNone extends DockerImage
  case object DockerImageNetwork extends DockerImage
  case class DockerImageDxFile(url: String, tarball: DxFile) extends DockerImage

  // A unified type representing a WDL workflow or a WDL applet.
  // This is useful when compiling WDL workflows, because they can
  // call other WDL workflows and applets. This is done using the
  // same syntax.
  sealed trait Callable {
    def name: String
    def inputVars: Vector[CVar]
    def outputVars: Vector[CVar]
  }

  // There are several kinds of applets
  //   Native:     a native platform applet
  //   Task:       call a task, execute a shell command (usually)
  //   WfFragment: WDL workflow fragment, can included nested if/scatter blocks
  //   WfInputs:   handle workflow inputs for unlocked workflows
  //   WfOutputs:  evaluate workflow outputs
  //   WorkflowOutputReorg: move intermediate result files to a subdirectory.
  sealed trait AppletKind
  case class AppletKindNative(id: String) extends AppletKind
  case class AppletKindTask(task: CallableTaskDefinition) extends AppletKind
  case class AppletKindWfFragment(calls: Vector[String],
                                  blockPath: Vector[Int],
                                  fqnDictTypes: Map[String, WomType])
      extends AppletKind
  case object AppletKindWfInputs extends AppletKind

  // Output - default and custom reorg
  case object AppletKindWfOutputs extends AppletKind
  case object AppletKindWfCustomReorgOutputs extends AppletKind

  // Reorg - default and custom reorg
  case object AppletKindWorkflowOutputReorg extends AppletKind
  case class AppletKindWorkflowCustomReorg(id: String) extends AppletKind

  /** @param name          Name of applet
    * @param inputs        input arguments
    * @param outputs       output arguments
    * @param instaceType   a platform instance name
    * @param docker        is docker used? if so, what image
    * @param kind          Kind of applet: task, scatter, ...
    * @param task          Task definition
    * @param womSourceCode WDL/CWL source code for task.
    */
  case class Applet(name: String,
                    inputs: Vector[CVar],
                    outputs: Vector[CVar],
                    instanceType: InstanceType,
                    docker: DockerImage,
                    kind: AppletKind,
                    womSourceCode: String)
      extends Callable {
    def inputVars = inputs
    def outputVars = outputs
  }

  /** An input to a stage. Could be empty, a wdl constant,
    *  a link to an output variable from another stage,
    *  or a workflow input.
    */
  sealed trait SArg
  case object SArgEmpty extends SArg
  case class SArgConst(wdlValue: WomValue) extends SArg
  case class SArgLink(stageId: DxWorkflowStage, argName: CVar) extends SArg
  case class SArgWorkflowInput(argName: CVar) extends SArg

  // A stage can call an applet or a workflow.
  //
  // Note: the description may contain dots, parentheses, and other special
  // symbols. It is shown to the user on the UI. The [id] is unique
  // across the workflow.
  case class Stage(description: String,
                   id: DxWorkflowStage,
                   calleeName: String,
                   inputs: Vector[SArg],
                   outputs: Vector[CVar])

  /** A workflow output is linked to the stage that
    * generated it.
    *
    * If [level] is SubWorkflow, then a workflow matches part of a
    * WDL workflow, it is not a first class citizen. It is compiled
    * into a hidden dx:workflow.
    */
  object Level extends Enumeration {
    val Top, Sub = Value
  }

  case class Workflow(name: String,
                      inputs: Vector[(CVar, SArg)],
                      outputs: Vector[(CVar, SArg)],
                      stages: Vector[Stage],
                      womSourceCode: String,
                      locked: Boolean,
                      level: Level.Value)
      extends Callable {
    def inputVars = inputs.map { case (cVar, _)   => cVar }.toVector
    def outputVars = outputs.map { case (cVar, _) => cVar }.toVector
  }

  // dependencies: the order in which to compile the workflows and tasks.
  // The first element in the vector depends on nothing else. Each other
  // element (may) depend on all previous elements.
  case class Bundle(primaryCallable: Option[Callable],
                    allCallables: Map[String, Callable],
                    dependencies: Vector[String],
                    typeAliases: Map[String, WomType])
}
