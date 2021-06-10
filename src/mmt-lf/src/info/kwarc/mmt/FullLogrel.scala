package info.kwarc.mmt

import info.kwarc.mmt.api.GlobalName
import info.kwarc.mmt.api.libraries.Lookup
import info.kwarc.mmt.api.objects._

/**
  * Full Logical Relations, delegates to [[PartialLogrel]]
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
class FullLogrel(override val mors: Array[Term], lr: GlobalName => Term, override val lookup: Lookup) extends Logrel {

  private val partialLogrel = new PartialLogrel(mors, p => Some(lr(p)), lookup)

  /**
    * For a term `t: A`, computes the expected type of `lr(t)`.
    *
    * Namely, the expected type is `lr(A) m₁(t) … mₙ(t)`.
    *
    * TODO: This only works for LF, right?
    */
  def getExpected(ctx: Context, t: Term, A: Term): Term = partialLogrel.getExpected(ctx, t, A).get

  /**
    * For a term `t: tp`, computes the expected judgement `lr(t) : getExpected(t)`…
    *
    * Namely, `lr(t) : lr(tp) m₁(t) … mₙ(t)`.
    *
    * This only works for LF, right?
    *
    * @see [[getExpected()]]
    */
  def applyPair(c: Context, t: Term, A: Term): (Term, Term) = (apply(c, t), getExpected(c, t, A))

  def apply(ctx: Context, t: Term): Term = partialLogrel.apply(ctx, t).get

  /**
    * Maps a [[Context]].
    *
    * d_1, ..., d_r ---> lr(d_1) ... lr(d_n)
    * todo: improve docs here
    */
  override def apply(ctx: Context, g: Context): Context = partialLogrel.apply(ctx, g)

  /**
    * Maps `t` to `List(m₁(t), …, mₙ(t), lr(t))`.
    */
  override def applyTerm(c: Context, t: Term): List[Term] = partialLogrel.applyTerm(c, t)

  /**
    * Maps [[VarDecl]] `v: tp [=df]` to `List(v₁: m₁'(tp) [= m₁'(df)], …, vₙ: mₙ'(tp) [= mₙ'(df)], v: lr(tp) x₁ … xₙ)`.
    */
  override def applyVarDecl(ctx: Context, vd: VarDecl): List[VarDecl] = partialLogrel.applyVarDecl(ctx, vd)
}