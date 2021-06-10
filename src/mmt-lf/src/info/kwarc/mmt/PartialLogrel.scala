package info.kwarc.mmt

import info.kwarc.mmt.api.libraries.Lookup
import info.kwarc.mmt.api.objects._
import info.kwarc.mmt.api.utils.UnicodeStrings
import info.kwarc.mmt.api.{GlobalName, ImplementationError, LocalName}
import info.kwarc.mmt.lf._

/*

But even if `partOfRel(t) == true`, the result of applying the logical relation to `t` differs depending on what other things are `partOfRel` or not.

- consider `pair: t` with `t := {A: tp, B: tp} tm A -> tm B -> tm AxB`
- naively, applying the logrel on `t` yields `{A: tp, A*: Unit, B: tp, B*: Unit, a: tm A, a*: a :: A, b: tm B, b*: b :: B}  (pair A A a b) :: (AxB)`
- we have `partOfRel(t)`, but still would like to get rid of `A*`

So what new contexts we synthesize must depend on `partOfRel(-)` applied to the types of the vardecls in the contexts.
 */

/**
  * Logical Relations
  *
  * NOTE: partial not as in partial morphism (i.e. dependency-closed), but in general
  *
  * TODO: comment more
  *
  * {{{basic lemma: e:A in input theory implies lr(e):^r(A) m_1(e) ... m_n(e)
           given c: type in input theory, we obtain expected type of lr(c):E where E = lr(type) m_1(c) ... m_n(c)
           lr(type) = [a_1:type,...,a_n:type] a_1 --> ... --> a_n --> type
           so E = m_1(c) --> ... --> m_n(c) --> type
       }}}
  *
  *
  * Consider logical relations over the theory `T = {prop: type, ⊦: prop ⟶ type}`
  * Then
  * {{{
  * apply(Context.empty, Πx: prop. Πy: ⊦ x)
  *   = Πx₁: m₁(prop) … Πxₙ: mₙ(prop) Πx: lr(prop) x₁ … xₙ. Πy₁: m₁(⊦ x₁) … Πyₙ: mₙ(⊦ xₙ). Πy: lr(???
  * }}}
  *
  * Notes on style for coding and comments:
  *
  * - n stands for mors.size
  * - meta variable i stands for some concrete index of a single morphism (in 0 .. n - 1)
  * - indices in comments are 1-based since argument indices are usually presented as 1-based to humans
  * - the variable names actually produced by this code may differ slightly from those mentioned in
  *   comments.
  *   The overall goal is to make comments human-readable and useful to give an intuition on what the
  *   code does to a human reader.
  */
class PartialLogrel(override val mors: Array[Term], lr: GlobalName => Option[Term], override val lookup: Lookup) extends Logrel {

  /**
    * For a term `t: A`, computes the expected type of `lr(t)`.
    *
    * Namely, the expected type is `lr(A) m₁(t) … mₙ(t)`.
    *
    * TODO: This only works for LF, right?
    */
  def getExpected(ctx: Context, t: Term, A: Term): Option[Term] = apply(ctx, A).map(relationAtA => {
    ApplySpine(relationAtA, applyMors(ctx, t): _*)
  })

  // TODO: in the future we might implement this more efficiently: we only need to recurse on the return types as FR once sketched in PM to NR.
  def isDefined(ctx: Context, t: Term): Boolean = apply(ctx, t).isDefined

  /**
    * Transforms a [[Term]] matching a [[FunType]] to a term matching a [[Pi]].
    *
    * Used to reduce the case of simple function types to the more general case of
    * dependent function types below.
    */
  private def funToPiType(t: Term): Term = t match {
    case FunType(args, retType) =>
      val namedArgsCtx = args.zipWithIndex.map {
        case ((Some(name), tp), _) => VarDecl(name, tp)
        case ((None, tp), index) => VarDecl(LocalName("x" + UnicodeStrings.superscriptInteger(index)), tp)
      }

      Pi(namedArgsCtx, retType)

    case _ => throw ImplementationError("called funToPiType on term not matching a FunType")
  }

  def apply(ctx: Context, t: Term): Option[Term] = t match {
    case OMV(x) =>
      if (ctx.get(x).tp.exists(isDefined(ctx, _))) Some(OMV(star(x)))
      else None

    // Cases for inhabitable t
    // =========================================================================
    // The return value in all these cases must have the form:
    //
    //   λa₁: m₁(t). … λaₙ: mₙ(t). <some MMT term here depending on case>
    //
    // This form corresponds to `getExpected`, see its docs.
    // Use `bindTerm(ctx, some name you may choose, t)` to generate the context bound by the
    // lambdas shown above.
    //
    case Univ(1) => // @DM: try to match Univ(i) in this style
      // create context `{x₁: m₁'(t), …, xₙ: mₙ'(t)}`
      val targetBinder = bindTerm(ctx, Context.pickFresh(ctx, LocalName("x"))._1, t)
      // TODO: ^^^ Is Context.pickFresh(ctx, _) sufficient to pick a fresh name? Shouldn't we pick a name that is *also* fresh in the output context?

      // return `λx₁: m₁'(t). … λxₙ: mₙ'(t). x₁ ⟶ … ⟶ xₙ ⟶ type
      Some(Lambda(
        targetBinder,
        Arrow(targetBinder.map(_.toTerm), Univ(1))
      ))

    case OMBIND(OMS(Pi.path), boundCtx, retType) =>
      apply(ctx ++ boundCtx, retType).map(newRetType => {
        // For reading along in comments, suppose `boundCtx = {a: tp_a, …, z: tp_z}`.
        // create context `{f₁: m₁'(t), …, fₙ: mₙ'(t)}`
        val targetBinder = bindTerm(ctx, Context.pickFresh(apply(ctx, boundCtx), LocalName("f"))._1, t)
        // TODO: ^^^ Is the Context.pickFresh(-, _) call here sufficient to pick a fresh name?

        // create `List(f₁ a₁ … zₙ, …, fₙ aₙ … zₙ )`
        val targetApplications = targetBinder.zipWithIndex.map {
          case (vd, i) =>
            ApplySpine(
              vd.toTerm,
              boundCtx.map(vd => OMV(suffix(vd.name, i))): _*
            )
        }

        Lambda(
          targetBinder,
          Pi(
            apply(ctx, boundCtx),
            ApplySpine(
              newRetType,
              targetApplications: _*
            )
          )
        )
      })

    // case for LFX' Sigma similar to Pi's case?

    case t @ FunType(args, _) if args.nonEmpty => apply(ctx, funToPiType(t)) // reduce to case Pi type

    // end: cases for inhabitable t.
    // =========================================================================

    case ApplySpine(f, args) =>
      apply(ctx, f).map(newFun => {
        ApplySpine(
          newFun,
          args.flatMap(applyTerm(ctx, _)): _*
        )
      })
    case OMBIND(OMS(Lambda.path), boundCtx, t) =>
      apply(ctx ++ boundCtx, t).map(newBody => {
        Lambda(apply(ctx, boundCtx), newBody)
      })

    // this case is last as it definitely needs to come after Univ(1)
    case OMS(p) => lr(p)

    case _ => ???
  }

  /**
    * Maps a [[Context]] `g` (in context of its context `ctx`).
    *
    * @return The context effectively emerging from `g` by applying [[applyVarDecl]] iteratively
    *         to every [[VarDecl]].
    */
  override def apply(ctx: Context, g: Context): Context = {
    g.mapVarDecls((partialCtx, vd) => applyVarDecl(ctx ++ partialCtx, vd)).flatten
  }

  /**
    * Maps [[VarDecl]] `v [: tp [=df] ]` to
    *
    *  - `List(v₁: [ m₁'(tp) [= m₁'(df)] ], …, vₙ: [ mₙ'(tp) [= mₙ'(df)] ], v: lr(tp) x₁ … xₙ)` if tp is given and
    *    part of the relation
    *  - `List(v₁: [ m₁'(tp) [= m₁'(df)] ], …, vₙ: [ mₙ'(tp) [= mₙ'(df)] ])` otherwise
    */
  override def applyVarDecl(ctx: Context, vd: VarDecl): List[VarDecl] = {
    applyMors(ctx, vd) ::: (vd.tp.flatMap(apply(ctx, _)) match {
      case Some(tpRelation) =>
        val vars = suffixAll(vd.name).map(OMV(_))
        val newTp = ApplySpine(tpRelation, vars: _*)
        List(VarDecl(star(vd.name), newTp))

      case None => Nil
    })
  }

  /**
    * Maps `t` to
    *
    *  - `List(m₁(t), …, mₙ(t), lr(t))` if t's type is part of the relation
    *  - `List(m₁(t), …, mₙ(t))` otherwise
    */
  override def applyTerm(c: Context, t: Term): List[Term] =
    applyMors(c, t) ::: apply(c, t).toList
}
