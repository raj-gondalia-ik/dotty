package dotty.tools.dotc
package transform

import core._
import Types._
import Contexts._
import Symbols._
import SymDenotations._
import Names._
import NameOps._
import StdNames._
import NameKinds._
import Flags._
import ValueClasses.isDerivedValueClass
import Decorators._
import Constants.Constant
import Annotations.Annotation
import Phases._
import ast.tpd.Literal

import scala.annotation.tailrec

object SymUtils:

  extension (self: Symbol)

    /** All traits implemented by a class or trait except for those inherited
    *  through the superclass. Traits are given in the order they appear in the
    *  parents clause (which is the reverse of their order in baseClasses)
    */
    def directlyInheritedTraits(using Context): List[ClassSymbol] = {
      val superCls = self.asClass.superClass
      val baseClasses = self.asClass.baseClasses
      if (baseClasses.isEmpty) Nil
      else
        def recur(bcs: List[ClassSymbol], acc: List[ClassSymbol]): List[ClassSymbol] = bcs match
          case bc :: bcs1 => if bc eq superCls then acc else recur(bcs1, bc :: acc)
          case nil => acc
        recur(baseClasses.tail, Nil)
    }

    /** All traits implemented by a class, except for those inherited through the superclass.
    *  The empty list if `self` is a trait.
    */
    def mixins(using Context): List[ClassSymbol] =
      if (self.is(Trait)) Nil
      else directlyInheritedTraits

    def isTypeTest(using Context): Boolean =
      self == defn.Any_isInstanceOf || self == defn.Any_typeTest

    def isTypeCast(using Context): Boolean =
      self == defn.Any_asInstanceOf || self == defn.Any_typeCast

    def isTypeTestOrCast(using Context): Boolean =
      isTypeCast || isTypeTest

    def isThreadUnsafe(using Context): Boolean = self.hasAnnotation(defn.ThreadUnsafeAnnot)

    def isVolatile(using Context): Boolean = self.hasAnnotation(defn.VolatileAnnot)

    def isAnyOverride(using Context): Boolean = self.is(Override) || self.is(AbsOverride)
      // careful: AbsOverride is a term only flag. combining with Override would catch only terms.

    def isSuperAccessor(using Context): Boolean = self.name.is(SuperAccessorName)

    def isNoValue(using Context): Boolean = self.is(Package) || self.isAllOf(JavaModule)

    /** Is this a type or term parameter or a term parameter accessor? */
    def isParamOrAccessor(using Context): Boolean =
      self.is(Param) || self.is(ParamAccessor)

    def derivesFromJavaEnum(using Context) =
      self.is(Enum, butNot = Case) &&
      self.info.parents.exists(p => p.typeSymbol == defn.JavaEnumClass)

    /** Is this a case class for which a product mirror is generated?
    *  Excluded are value classes, abstract classes and case classes with more than one
    *  parameter section.
    */
    def whyNotGenericProduct(using Context): String =
      if (!self.is(CaseClass)) "it is not a case class"
      else if (self.is(Abstract)) "it is an abstract class"
      else if (self.primaryConstructor.info.paramInfoss.length != 1) "it takes more than one parameter list"
      else if (isDerivedValueClass(self)) "it is a value class"
      else ""

    def isGenericProduct(using Context): Boolean = whyNotGenericProduct.isEmpty

    /** Is this an old style implicit conversion?
     *  @param directOnly            only consider explicitly written methods
     *  @param forImplicitClassOnly  only consider methods generated from implicit classes
     */
    def isOldStyleImplicitConversion(directOnly: Boolean = false, forImplicitClassOnly: Boolean = false)(using Context): Boolean =
      self.is(Implicit) && self.info.stripPoly.match
        case mt @ MethodType(_ :: Nil) if !mt.isImplicitMethod =>
          if self.isCoDefinedGiven(mt.finalResultType.typeSymbol)
          then !directOnly
          else !forImplicitClassOnly
        case _ =>
          false

    /** Is this the method that summons a structural given instance? */
    def isGivenInstanceSummoner(using Context): Boolean =
      def isCodefined(info: Type): Boolean = info.stripPoly match
        case mt: MethodType =>
          // given summoner can only have contextual params
          mt.isImplicitMethod && isCodefined(mt.resultType)
        case mt: ExprType =>
          isCodefined(mt.resultType)
        case res =>
          self.isCoDefinedGiven(res.typeSymbol)
      self.isAllOf(Given | Method) && isCodefined(self.info)

    // TODO Scala 3.x: only check for inline vals (no final ones)
    def isInlineVal(using Context) =
      self.isOneOf(FinalOrInline, butNot = Mutable)
      && (!self.is(Method) || self.is(Accessor))

    def useCompanionAsSumMirror(using Context): Boolean =
      def companionExtendsSum(using Context): Boolean =
        self.linkedClass.isSubClass(defn.Mirror_SumClass)
      self.linkedClass.exists
        && !self.is(Scala2x)
        && (
          // If the sum type is compiled from source, and `self` is a "generic sum"
          // then its companion object will become a sum mirror in `posttyper`. (This method
          // can be called from `typer` when summoning a Mirror.)
          // However if `self` is from a binary file, then we should check that its companion
          // subclasses `Mirror.Sum`. e.g. before Scala 3.1, hierarchical sum types were not
          // considered "generic sums", so their companion would not cache the mirror.
          // Companions from TASTy will already be typed as `Mirror.Sum`.
          self.isDefinedInSource || companionExtendsSum
        )

    /** Is this a sealed class or trait for which a sum mirror is generated?
    *  It must satisfy the following conditions:
    *   - it has at least one child class or object
    *   - none of its children are anonymous classes
    *   - all of its children are addressable through a path from the parent class
    *     and also the location of the generated mirror.
    *   - all of its children are generic products, singletons, or generic sums themselves.
    */
    def whyNotGenericSum(declScope: Symbol)(using Context): String =
      if (!self.is(Sealed))
        s"it is not a sealed ${self.kindString}"
      else if (!self.isOneOf(AbstractOrTrait))
        s"it is not an abstract class"
      else {
        val children = self.children
        val companionMirror = self.useCompanionAsSumMirror
        assert(!(companionMirror && (declScope ne self.linkedClass)))
        def problem(child: Symbol) = {

          def isAccessible(sym: Symbol): Boolean =
            (self.isContainedIn(sym) && (companionMirror || declScope.isContainedIn(sym)))
            || sym.is(Module) && isAccessible(sym.owner)

          if (child == self) "it has anonymous or inaccessible subclasses"
          else if (!isAccessible(child.owner)) i"its child $child is not accessible"
          else if (!child.isClass) ""
          else {
            val s = child.whyNotGenericProduct
            if (s.isEmpty) s
            else if (child.is(Sealed)) {
              val s = child.whyNotGenericSum(if child.useCompanionAsSumMirror then child.linkedClass else ctx.owner)
              if (s.isEmpty) s
              else i"its child $child is not a generic sum because $s"
            } else i"its child $child is not a generic product because $s"
          }
        }
        if (children.isEmpty) "it does not have subclasses"
        else children.map(problem).find(!_.isEmpty).getOrElse("")
      }

    def isGenericSum(declScope: Symbol)(using Context): Boolean = whyNotGenericSum(declScope).isEmpty

    /** If this is a constructor, its owner: otherwise this. */
    final def skipConstructor(using Context): Symbol =
      if (self.isConstructor) self.owner else self

    /** The closest properly enclosing method or class of this symbol. */
    final def enclosure(using Context): Symbol =
      self.owner.enclosingMethodOrClass

    /** The closest enclosing method or class of this symbol */
    @tailrec final def enclosingMethodOrClass(using Context): Symbol =
      if (self.is(Method) || self.isClass) self
      else if (self.exists) self.owner.enclosingMethodOrClass
      else NoSymbol

    /** Apply symbol/symbol substitution to this symbol */
    def subst(from: List[Symbol], to: List[Symbol]): Symbol = {
      @tailrec def loop(from: List[Symbol], to: List[Symbol]): Symbol =
        if (from.isEmpty) self
        else if (self eq from.head) to.head
        else loop(from.tail, to.tail)
      loop(from, to)
    }

    def accessorNamed(name: TermName)(using Context): Symbol =
      self.owner.info.decl(name).suchThat(_.is(Accessor)).symbol

    def caseAccessors(using Context): List[Symbol] =
      self.info.decls.filter(_.is(CaseAccessor))

    def getter(using Context): Symbol =
      if (self.isGetter) self else accessorNamed(self.asTerm.name.getterName)

    def setter(using Context): Symbol =
      if (self.isSetter) self
      else accessorNamed(self.asTerm.name.setterName)

    def traitSetter(using Context): Symbol =
      if (self.name.is(TraitSetterName)) self
      else accessorNamed(Mixin.traitSetterName(self.asTerm))

    def field(using Context): Symbol = {
      val thisName = self.name.asTermName
      val fieldName =
        if (self.hasAnnotation(defn.ScalaStaticAnnot)) thisName.getterName
        else thisName.fieldName
      self.owner.info.decl(fieldName).suchThat(!_.is(Method)).symbol
    }

    def isConstExprFinalVal(using Context): Boolean =
      atPhaseNoLater(erasurePhase) {
        self.is(Final) && self.info.resultType.isInstanceOf[ConstantType]
      }

    def isField(using Context): Boolean =
      self.isTerm && !self.is(Method)

    def isEnumCase(using Context): Boolean =
      self.isAllOf(EnumCase, butNot = JavaDefined)

    def annotationsCarrying(meta: ClassSymbol)(using Context): List[Annotation] =
      self.annotations.filter(_.symbol.hasAnnotation(meta))

    def withAnnotationsCarrying(from: Symbol, meta: ClassSymbol)(using Context): self.type = {
      self.addAnnotations(from.annotationsCarrying(meta))
      self
    }

    def isEnum(using Context): Boolean = self.is(Enum, butNot = JavaDefined)
    def isEnumClass(using Context): Boolean = isEnum && !self.is(Case)

    /** Does this symbol refer to anonymous classes synthesized by enum desugaring? */
    def isEnumAnonymClass(using Context): Boolean =
      self.isAnonymousClass && (self.owner.name.eq(nme.DOLLAR_NEW) || self.owner.is(CaseVal))

    /** Is this symbol defined locally (i.e. at some level owned by a term) so that
    *  it cannot be seen from parent class `cls`?
    */
    def isInaccessibleChildOf(cls: Symbol)(using Context): Boolean =
      def isAccessible(sym: Symbol, cls: Symbol): Boolean =
        if cls.isType && !cls.is(Package) then
          isAccessible(sym, cls.owner)
        else
          sym == cls
          || sym.is(Package)
          || sym.isType && isAccessible(sym.owner, cls)
      !isAccessible(self.owner, cls)

    def hasAnonymousChild(using Context): Boolean =
      self.children.exists(_ `eq` self)

    /** Is this symbol directly owner by a term symbol, i.e., is it local to a block? */
    def isLocalToBlock(using Context): Boolean =
      self.owner.isTerm

    /** Is symbol directly or indirectly owned by a term symbol? */
    @tailrec final def isLocal(using Context): Boolean = {
      val owner = self.maybeOwner
      if (!owner.exists) false
      else if (isLocalToBlock) true
      else if (owner.is(Package)) false
      else owner.isLocal
    }

    /** The reachable typeRef with wildcard arguments for each type parameter */
    def reachableRawTypeRef(using Context) =
      self.reachableTypeRef.appliedTo(self.typeParams.map(_ => TypeBounds.emptyPolyKind))

    /** Is symbol a quote operation? */
    def isQuote(using Context): Boolean =
      self == defn.QuotedRuntime_exprQuote || self == defn.QuotedTypeModule_of

    /** Is symbol a term splice operation? */
    def isExprSplice(using Context): Boolean =
      self == defn.QuotedRuntime_exprSplice || self == defn.QuotedRuntime_exprNestedSplice

    /** Is symbol a type splice operation? */
    def isTypeSplice(using Context): Boolean =
      self == defn.QuotedType_splice

    def isScalaStatic(using Context): Boolean =
      self.hasAnnotation(defn.ScalaStaticAnnot)

    def isDeprecated(using Context): Boolean =
      self.hasAnnotation(defn.DeprecatedAnnot)

    /** Is symbol assumed or declared as an infix symbol? */
    def isDeclaredInfix(using Context): Boolean =
      self.is(Infix)
      || defn.isInfix(self)
      || self.name.isUnapplyName
        && self.owner.is(Module)
        && self.owner.linkedClass.is(Case)
        && self.owner.linkedClass.isDeclaredInfix

    /** Is symbol declared or inherits @experimental? */
    def isExperimental(using Context): Boolean =
      self.hasAnnotation(defn.ExperimentalAnnot)
      || (self.maybeOwner.isClass && self.owner.hasAnnotation(defn.ExperimentalAnnot))

    def isInExperimentalScope(using Context): Boolean =
      def isDefaultArgumentOfExperimentalMethod =
        self.name.is(DefaultGetterName)
        && self.owner.isClass
        && {
          val overloads = self.owner.asClass.membersNamed(self.name.firstPart)
          overloads.filterWithFlags(HasDefaultParams, EmptyFlags) match
            case denot: SymDenotation => denot.symbol.isExperimental
            case _ => false
        }
      self.hasAnnotation(defn.ExperimentalAnnot)
      || isDefaultArgumentOfExperimentalMethod
      || (!self.is(Package) && self.owner.isInExperimentalScope)

    /** The declared self type of this class, as seen from `site`, stripping
    *  all refinements for opaque types.
    */
    def declaredSelfTypeAsSeenFrom(site: Type)(using Context): Type =
      extension (tp: Type) def stripOpaques: Type = tp match
        case RefinedType(parent, name, _) if self.info.decl(name).symbol.isOpaqueAlias =>
          parent.stripOpaques
        case _ =>
          tp
      self.asClass.givenSelfType.stripOpaques.asSeenFrom(site, self)

    /** If `original` has a target name annotation, add one to this symbol as well
    *  such that the new target name is `original`'s target name transformed by `nameFn`.
    */
    def deriveTargetNameAnnotation(original: Symbol, nameFn: Name => Name)(using Context): Unit =
      if original.hasAnnotation(defn.TargetNameAnnot) then
        self.addAnnotation(
          Annotation(defn.TargetNameAnnot,
            Literal(Constant(nameFn(original.targetName).toString)).withSpan(original.span)))

    /** The return type as seen from the body of this definition. It is
     *  computed from the symbol's type by replacing param refs by param symbols.
     */
    def localReturnType(using Context): Type =
      if self.isConstructor then defn.UnitType
      else
        def instantiateRT(info: Type, psymss: List[List[Symbol]]): Type = info match
          case info: PolyType =>
            instantiateRT(info.instantiate(psymss.head.map(_.typeRef)), psymss.tail)
          case info: MethodType =>
            instantiateRT(info.instantiate(psymss.head.map(_.termRef)), psymss.tail)
          case info =>
            info.widenExpr
        instantiateRT(self.info, self.paramSymss)

    /** The expected type of a return to `self` at the place indicated by the context.
     *  This is the local return type instantiated by the symbols of any context function
     *  closures that enclose the site of the return
     */
    def returnProto(using Context): Type =

      /** If `pt` is a context function type, its return type. If the CFT
       * is dependent, instantiate with the parameters of the associated
       * anonymous function.
       * @param  paramss  the parameters of the anonymous functions
       *                  enclosing the return expression
       */
      def instantiateCFT(pt: Type, paramss: => List[List[Symbol]]): Type =
        val ift = defn.asContextFunctionType(pt)
        if ift.exists then
          ift.nonPrivateMember(nme.apply).info match
            case appType: MethodType =>
              instantiateCFT(appType.instantiate(paramss.head.map(_.termRef)), paramss.tail)
        else pt

      def iftParamss = ctx.owner.ownersIterator
          .filter(_.is(Method, butNot = Accessor))
          .takeWhile(_.isAnonymousFunction)
          .toList
          .reverse
          .map(_.paramSymss.head)

      instantiateCFT(self.localReturnType, iftParamss)
    end returnProto
  end extension
end SymUtils
