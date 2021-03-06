/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js tools             **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package scala.scalajs.tools.optimizer

import scala.annotation.{switch, tailrec}

import scala.collection.mutable

import scala.scalajs.ir._
import Infos.OptimizerHints
import Trees._
import Types._

import scala.scalajs.tools.logging._

/** Incremental optimizer.
 *  An incremental optimizer consumes the reachability analysis produced by
 *  an [[Analyzer]], as well as trees for classes, trait impls, etc., and
 *  optimizes them in an incremental way.
 *  It maintains state between runs to do a minimal amount of work on every
 *  run, based on detecting what parts of the program must be re-optimized,
 *  and keeping optimized results from previous runs for the rest.
 */
class IncOptimizer {
  import IncOptimizer._

  private var logger: Logger = _

  /** Are we in batch mode? I.e., are we running from scratch?
   *  Various parts of the algorithm can be skipped entirely when running in
   *  batch mode.
   */
  private var batchMode: Boolean = false

  private var objectClass: Class = _
  private val classes = mutable.Map.empty[String, Class]
  private val traitImpls = mutable.Map.empty[String, TraitImpl]
  private val _interfaces = mutable.Map.empty[String, InterfaceType]

  def findTraitImpl(encodedName: String): TraitImpl = traitImpls(encodedName)
  def findClass(encodedName: String): Class = classes(encodedName)

  private def getInterface(encodedName: String): InterfaceType =
    _interfaces.getOrElseUpdate(encodedName, new InterfaceType(encodedName))

  private var methodsToProcess: mutable.Set[MethodImpl] = mutable.Set.empty

  type GetClassTreeIfChanged =
    (String, Option[String]) => Option[(ClassDef, Option[String])]

  private def withLogger[A](logger: Logger)(body: => A): A = {
    assert(this.logger == null)
    this.logger = logger
    try body
    finally this.logger = null
  }

  /** Update the incremental analyzer with a new run. */
  def update(analyzer: Analyzer,
      getClassTreeIfChanged: GetClassTreeIfChanged,
      logger: Logger): Unit = withLogger(logger) {

    batchMode = objectClass == null
    logger.debug(s"Optimizer batch mode: $batchMode")

    logTime(logger, "Incremental part of inc. optimizer") {
      updateAndTagEverything(analyzer, getClassTreeIfChanged)
    }

    logTime(logger, "Optimizer part of inc. optimizer") {
      processAllTaggedMethods()
    }
  }

  /** Incremental part: update state and detect what needs to be re-optimized.
   */
  private def updateAndTagEverything(analyzer: Analyzer,
      getClassTreeIfChanged: GetClassTreeIfChanged): Unit = {

    val neededClasses = mutable.Map.empty[String, analyzer.ClassInfo]
    val neededTraitImpls = mutable.Map.empty[String, analyzer.ClassInfo]
    for {
      classInfo <- analyzer.classInfos.values
      if classInfo.isNeededAtAll
    } {
      if (classInfo.hasInstantiation && classInfo.isAnySubclassInstantiated)
        neededClasses += classInfo.encodedName -> classInfo
      else if (classInfo.isImplClass)
        neededTraitImpls += classInfo.encodedName -> classInfo
    }

    /* Remove deleted trait impls, and update existing trait impls.
     * We don't even have to notify callers in case of additions or removals
     * because callers have got to be invalidated by themselves.
     * Only changed methods need to trigger notifications.
     *
     * Non-batch mode only.
     */
    assert(!batchMode || traitImpls.isEmpty)
    if (!batchMode) {
      traitImpls retain { (traitImplName, traitImpl) =>
        neededTraitImpls.remove(traitImplName).fold {
          /* Deleted trait impl. Mark all its methods as deleted, and remove it
           * from known trait impls.
           */
          traitImpl.methods.values.foreach(_.delete())
          false
        } { traitImplInfo =>
          /* Existing trait impl. Update it. */
          val (added, changed, removed) =
            traitImpl.updateMethodsWith(traitImplInfo, getClassTreeIfChanged)
          for (method <- changed)
            traitImpl.interface.tagStaticCallersOf(method)
          true
        }
      }
    }

    /* Add new trait impls.
     * Easy, we don't have to notify anyone.
     */
    for (traitImplInfo <- neededTraitImpls.values) {
      val traitImpl = new TraitImpl(traitImplInfo.encodedName)
      traitImpls += traitImpl.encodedName -> traitImpl
      traitImpl.updateMethodsWith(traitImplInfo, getClassTreeIfChanged)
    }

    if (!batchMode) {
      /* Class removals:
       * * If a class is deleted or moved, delete its entire subtree (because
       *   all its descendants must also be deleted or moved).
       * * If an existing class was instantiated but is no more, notify callers
       *   of its methods.
       *
       * Non-batch mode only.
       */
      val objectClassStillExists =
        objectClass.walkClassesForDeletions(neededClasses.get(_))
      assert(objectClassStillExists, "Uh oh, java.lang.Object was deleted!")

      /* Class changes:
       * * Delete removed methods, update existing ones, add new ones
       * * Update the list of ancestors
       * * Class newly instantiated
       *
       * Non-batch mode only.
       */
      objectClass.walkForChanges(
          neededClasses.remove(_).get,
          getClassTreeIfChanged,
          Set.empty)
    }

    /* Class additions:
     * * Add new classes (including those that have moved from elsewhere).
     * In batch mode, we avoid doing notifications.
     */
    for (classInfo <- neededClasses.values.toList.sortBy(_.ancestorCount)) {
      val superClassInfo = classInfo.superClass
      val cls = new Class(
          Option(superClassInfo).map(info => classes(info.encodedName)),
          classInfo.encodedName)
      cls.updateMethodsWith(classInfo, getClassTreeIfChanged)
      cls.interfaces =
        classInfo.ancestors.map(info => getInterface(info.encodedName)).toSet

      cls.isInstantiated = classInfo.isInstantiated

      if (batchMode) {
        if (cls.isInstantiated) {
          /* Only add the class to all its ancestor interfaces */
          for (intf <- cls.interfaces)
            intf.instantiatedSubclasses += cls
        }
      } else {
        val allMethodNames = cls.allMethods().keys

        if (cls.isInstantiated) {
          /* Add the class to all its ancestor interfaces + notify all callers
           * of any of the methods.
           * TODO: be more selective on methods that are notified: it is not
           * necessary to modify callers of methods defined in a parent class
           * that already existed in the previous run.
           */
          for (intf <- cls.interfaces) {
            intf.instantiatedSubclasses += cls
            for (methodName <- allMethodNames)
              intf.tagDynamicCallersOf(methodName)
          }
        }

        /* Tag static callers because the class could have been *moved*,
         * not just added.
         */
        for (methodName <- allMethodNames)
          cls.myInterface.tagStaticCallersOf(methodName)
      }
    }
  }

  /** Optimizer part: process all methods that need reoptimizing. */
  private def processAllTaggedMethods(): Unit = {
    logger.debug(s"Optimizing ${methodsToProcess.size} methods.")
    for (method <- methodsToProcess)
      method.process()
    methodsToProcess.clear()
  }

  /** Base class for [[Class]] and [[TraitImpl]]. */
  abstract class MethodContainer(val encodedName: String) {
    val methods = mutable.Map.empty[String, MethodImpl]

    var lastVersion: Option[String] = None

    private def reachableMethodsOf(info: Analyzer#ClassInfo): Set[String] = {
      (for {
        methodInfo <- info.methodInfos.values
        if methodInfo.isReachable && !methodInfo.isAbstract
      } yield {
        methodInfo.encodedName
      }).toSet
    }

    def updateMethodsWith(info: Analyzer#ClassInfo,
        getClassTreeIfChanged: GetClassTreeIfChanged): (Set[String], Set[String], Set[String]) = {
      val addedMethods = Set.newBuilder[String]
      val changedMethods = Set.newBuilder[String]
      val deletedMethods = Set.newBuilder[String]

      val reachableMethods = reachableMethodsOf(info)
      val methodSetChanged = methods.keySet != reachableMethods
      if (methodSetChanged) {
        // Remove deleted methods
        methods retain { (methodName, method) =>
          if (reachableMethods.contains(methodName)) {
            true
          } else {
            deletedMethods += methodName
            method.delete()
            false
          }
        }
        // Clear lastVersion if there are new methods
        if (reachableMethods.exists(!methods.contains(_)))
          lastVersion = None
      }
      for ((tree, version) <- getClassTreeIfChanged(encodedName, lastVersion)) {
        lastVersion = version
        for {
          (methodDef @ MethodDef(Ident(methodName, _), _, _, _)) <- tree.defs
          if reachableMethods.contains(methodName)
        } {
          val methodInfo = info.methodInfos(methodName)
          methods.get(methodName).fold {
            addedMethods += methodName
            val method = new MethodImpl(this, methodName)
            method.updateWith(methodInfo, methodDef)
            methods(methodName) = method
            method
          } { method =>
            if (method.updateWith(methodInfo, methodDef))
              changedMethods += methodName
            method
          }
        }
      }

      (addedMethods.result(), changedMethods.result(), deletedMethods.result())
    }
  }

  /** Class in the class hierarchy (not an interface).
   *  A class may be a module class.
   *  A class knows its superclass and the interfaces it implements. It also
   *  maintains a list of its direct subclasses, so that the instances of
   *  [[Class]] form a tree of the class hierarchy.
   */
  class Class(val superClass: Option[Class],
      _encodedName: String) extends MethodContainer(_encodedName) {
    if (encodedName == Definitions.ObjectClass) {
      assert(superClass.isEmpty)
      assert(objectClass == null)
      objectClass = this
    } else {
      assert(superClass.isDefined)
      superClass.foreach(_.subclasses ::= this)
    }

    /** Parent chain from this to Object. */
    val parentChain: List[Class] =
      this :: superClass.fold[List[Class]](Nil)(_.parentChain)

    /** Reverse parent chain from Object to this. */
    val reverseParentChain: List[Class] =
      parentChain.reverse

    classes += encodedName -> this

    val myInterface = getInterface(encodedName)

    var interfaces: Set[InterfaceType] = Set.empty
    var subclasses: List[Class] = Nil
    var isInstantiated: Boolean = false

    override def toString(): String =
      encodedName

    /** Walk the class hierarchy tree for deletions.
     *  This includes "deleting" classes that were previously instantiated but
     *  are no more.
     */
    def walkClassesForDeletions(
        getClassInfoIfNeeded: String => Option[Analyzer#ClassInfo]): Boolean = {
      def sameSuperClass(info: Analyzer#ClassInfo): Boolean =
        if (info.superClass == null) superClass.isEmpty
        else superClass.exists(_.encodedName == info.superClass.encodedName)

      getClassInfoIfNeeded(encodedName) match {
        case Some(classInfo) if sameSuperClass(classInfo) =>
          // Class still exists. Recurse.
          subclasses = subclasses.filter(
              _.walkClassesForDeletions(getClassInfoIfNeeded))
          if (isInstantiated && !classInfo.isInstantiated)
            notInstantiatedAnymore()
          true
        case _ =>
          // Class does not exist or has been moved. Delete the entire subtree.
          deleteSubtree()
          false
      }
    }

    /** Delete this class and all its subclasses. */
    def deleteSubtree(): Unit = {
      delete()
      for (subclass <- subclasses)
        subclass.deleteSubtree()
    }

    private def delete(): Unit = {
      if (isInstantiated)
        notInstantiatedAnymore()
      for (method <- methods.values)
        method.delete()
      classes -= encodedName
      /* Note: no need to tag methods that call *statically* one of the methods
       * of the deleted classes, since they've got to be invalidated by
       * themselves.
       */
    }

    def notInstantiatedAnymore(): Unit = {
      assert(isInstantiated)
      isInstantiated = false
      for (intf <- interfaces) {
        intf.instantiatedSubclasses -= this
        for (methodName <- allMethods().keys)
          intf.tagDynamicCallersOf(methodName)
      }
    }

    def walkForChanges(
        getClassInfo: String => Analyzer#ClassInfo,
        getClassTreeIfChanged: GetClassTreeIfChanged,
        parentInlineableMethodChanges: Set[String]): Unit = {

      val classInfo = getClassInfo(encodedName)

      val (addedMethods, changedMethods, deletedMethods) =
        updateMethodsWith(classInfo, getClassTreeIfChanged)

      val oldInterfaces = interfaces
      val newInterfaces =
        classInfo.ancestors.map(info => getInterface(info.encodedName)).toSet
      interfaces = newInterfaces

      // TODO? Be smarter about addedMethods and deletedMethods? Is it possible?
      val inlineableMethodChanges =
        (parentInlineableMethodChanges -- methods.keys ++
            addedMethods ++ changedMethods ++ deletedMethods)

      // Tag callers with dynamic calls
      val wasInstantiated = isInstantiated
      isInstantiated = classInfo.isInstantiated
      assert(!(wasInstantiated && !isInstantiated),
          "(wasInstantiated && !isInstantiated) should have been handled "+
          "during deletion phase")

      if (isInstantiated) {
        if (wasInstantiated) {
          val existingInterfaces = oldInterfaces.intersect(newInterfaces)
          for {
            intf <- existingInterfaces
            methodName <- inlineableMethodChanges
          } {
            intf.tagDynamicCallersOf(methodName)
          }
          if (newInterfaces.size != oldInterfaces.size ||
              newInterfaces.size != existingInterfaces.size) {
            val allMethodNames = allMethods().keys
            for {
              intf <- oldInterfaces ++ newInterfaces -- existingInterfaces
              methodName <- allMethodNames
            } {
              intf.tagDynamicCallersOf(methodName)
            }
          }
        } else {
          val allMethodNames = allMethods().keys
          for (intf <- interfaces) {
            intf.instantiatedSubclasses += this
            for (methodName <- allMethodNames)
              intf.tagDynamicCallersOf(methodName)
          }
        }
      }

      // Tag callers with static calls
      for (methodName <- inlineableMethodChanges)
        myInterface.tagStaticCallersOf(methodName)

      // Recurse in subclasses
      for (cls <- subclasses)
        cls.walkForChanges(getClassInfo, getClassTreeIfChanged,
            inlineableMethodChanges)
    }

    /** All the methods of this class, including inherited ones.
     *  It has () so we remember this is an expensive operation.
     */
    def allMethods(): scala.collection.Map[String, MethodImpl] = {
      val result = mutable.Map.empty[String, MethodImpl]
      for (parent <- reverseParentChain)
        result ++= parent.methods
      result
    }

    @tailrec
    final def lookupMethod(methodName: String): Option[MethodImpl] = {
      methods.get(methodName) match {
        case Some(impl) => Some(impl)
        case none =>
          superClass match {
            case Some(p) => p.lookupMethod(methodName)
            case none    => None
          }
      }
    }
  }

  /** Trait impl. */
  class TraitImpl(_encodedName: String) extends MethodContainer(_encodedName) {
    val interface = getInterface(encodedName)
  }

  /** Type of a class or interface.
   *  Types are created on demand when a method is called on a given
   *  [[ClassType]].
   */
  class InterfaceType(val encodedName: String) {
    private val dynamicCallers = mutable.Map.empty[String, mutable.Set[MethodImpl]]
    private val staticCallers = mutable.Map.empty[String, mutable.Set[MethodImpl]]

    var instantiatedSubclasses: Set[Class] = Set.empty

    override def toString(): String =
      s"intf $encodedName"

    def registerDynamicCaller(methodName: String, caller: MethodImpl): Unit =
      dynamicCallers.getOrElseUpdate(methodName, mutable.Set.empty) += caller

    def registerStaticCaller(methodName: String, caller: MethodImpl): Unit =
      staticCallers.getOrElseUpdate(methodName, mutable.Set.empty) += caller

    def unregisterCaller(caller: MethodImpl): Unit = {
      dynamicCallers.values.foreach(_ -= caller)
      staticCallers.values.foreach(_ -= caller)
    }

    def tagDynamicCallersOf(methodName: String): Unit =
      dynamicCallers.remove(methodName).foreach(_.foreach(_.tag()))

    def tagStaticCallersOf(methodName: String): Unit =
      staticCallers.remove(methodName).foreach(_.foreach(_.tag()))
  }

  /** A method implementation.
   *  It must be concrete, and belong either to a [[Class]] or a [[TraitImpl]].
   */
  class MethodImpl(val owner: MethodContainer, val encodedName: String)
      extends OptimizerCore.MethodImpl {
    private[this] var deleted: Boolean = false // for sanity checks

    var optimizerHints: OptimizerHints = OptimizerHints.empty
    var originalDef: MethodDef = _
    var desugaredDef: Tree = _

    private val registeredTo = mutable.Set.empty[InterfaceType]

    override def toString(): String =
      s"$owner.$encodedName"

    private def registerDynamicCall(intf: InterfaceType,
        methodName: String): Unit = {
      intf.registerDynamicCaller(methodName, this)
      registeredTo += intf
    }

    private def registerStaticCall(intf: InterfaceType,
        methodName: String): Unit = {
      intf.registerStaticCaller(methodName, this)
      registeredTo += intf
    }

    private def unregisterAllCalls(): Unit = {
      registeredTo.foreach(_.unregisterCaller(this))
      registeredTo.clear()
    }

    /** Returns true if the method changed and it was or is inlineable. */
    def updateWith(methodInfo: Analyzer#MethodInfo,
        methodDef: MethodDef): Boolean = {
      assert(!deleted, "updateWith() called on a deleted method")
      val hints = methodInfo.optimizerHints
      val changed = hints != optimizerHints || methodDef != originalDef
      if (changed) {
        optimizerHints = hints
        originalDef = methodDef
        desugaredDef = null
        val wasInlineable = inlineable
        updateInlineable()
        tag()
        wasInlineable || inlineable
      } else {
        false
      }
    }

    def delete(): Unit = {
      assert(!deleted, "delete() called twice")
      deleted = true
      unregisterAllCalls()
      methodsToProcess -= this
    }

    def tag(): Unit = {
      assert(!deleted, "tag() called on a deleted method")
      methodsToProcess += this
      unregisterAllCalls()
    }

    def process(): Unit = {
      assert(!deleted, "process() called on a deleted method")

      val optimizedDef = new Optimizer().optimize(originalDef)
      val emitted =
        if (owner.isInstanceOf[Class])
          ScalaJSClassEmitter.genMethod(owner.encodedName, optimizedDef)
        else
          ScalaJSClassEmitter.genTraitImplMethod(owner.encodedName, optimizedDef)
      desugaredDef = JSDesugaring.desugarJavaScript(emitted)
    }

    private class Optimizer extends OptimizerCore {
      protected def dynamicCall(intfName: String,
          methodName: String): List[MethodImpl] = {
        val intf = getInterface(intfName)
        MethodImpl.this.registerDynamicCall(intf, methodName)
        intf.instantiatedSubclasses.flatMap(_.lookupMethod(methodName)).toList
      }

      protected def staticCall(className: String,
          methodName: String): Option[MethodImpl] = {
        val clazz = classes(className)
        MethodImpl.this.registerStaticCall(clazz.myInterface, methodName)
        clazz.lookupMethod(methodName)
      }

      protected def traitImplCall(traitImplName: String,
          methodName: String): Option[MethodImpl] = {
        val traitImpl = traitImpls(traitImplName)
        registerStaticCall(traitImpl.interface, methodName)
        traitImpl.methods.get(methodName)
      }
    }
  }

}

object IncOptimizer {

  private[optimizer] def logTime[A](logger: Logger,
      title: String)(body: => A): A = {
    val startTime = System.nanoTime()
    val result = body
    val endTime = System.nanoTime()
    val elapsedTime = (endTime - startTime) / 1000
    logger.debug(s"$title: $elapsedTime us")
    result
  }

}
