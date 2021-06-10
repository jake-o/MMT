package info.kwarc.mmt

import info.kwarc.mmt.api.LocalName
import info.kwarc.mmt.api.libraries.Lookup
import info.kwarc.mmt.api.objects.{Context, OMV, Sub, Term, VarDecl}
import info.kwarc.mmt.api.utils.UnicodeStrings

/**
  * Captures the logrel helper methods that work the same for both partial and total logical relations.
  *
  * Call these functions only on "input that you know is in the relation".
  *
  * @todo better name for trait
  */
trait Logrel {
  def mors: Array[Term]
  def lookup: Lookup

  def apply(ctx: Context, g: Context): Context
  def applyVarDecl(ctx: Context, vd: VarDecl): List[VarDecl]
  def applyTerm(c: Context, t: Term): List[Term]

  /**
    * Maps a variable name (from the logrel's domain) to the name of the variable standing
    * for the proof of relatedness of `suffix(name, 0) … suffix(name, n - 1)`.
    *
    * The case of just a single morphism is special-cased to overall produce more human-readable
    * variable names.
    *
    * Only change in conjunction with [[suffix()]]!
    */
  def star(name: LocalName): LocalName = {
    if (mors.length == 1) name.suffixLastSimple("ᕁ") else name
  }

  /**
    * Maps a variable name (from the logrel's domain) to the name of the i-th variable translated
    * through mᵢ.
    *
    * The case of just a single morphism is special-cased to overall produce more human-readable
    * variable names.
    *
    * Only change in conjunction with [[star()]]!
    */
  def suffix(name: LocalName, i: Int): LocalName = {
    // i + 1 to since human-readable argument indices are usually 1-based
    if (mors.length == 1) name else name.suffixLastSimple(UnicodeStrings.subscriptInteger(i + 1))
  }

  /**
    * Maps `x` to `List(x₁, …, xₙ)`.
    */
  def suffixAll(name: LocalName): List[LocalName] = mors.indices.map(suffix(name, _)).toList

  /**
    * Maps `t` to `mᵢ'(t)` where `mᵢ'` is `mᵢ` with subsequent substitution replacing
    * variables `x` in ctx by `xᵢ`.
    */
  def applyMor(ctx: Context, t: Term, i: Int): Term = {
    val sub = ctx.map(vd => Sub(vd.name, OMV(suffix(vd.name, i))))
    lookup.ApplyMorphs(t, mors(i)) ^ sub
  }

  /**
    * Maps `t` to `List(m₁(t), … ,mₙ(t))`.
    */
  def applyMors(ctx: Context, t: Term): List[Term] = mors.indices.map(applyMor(ctx, t, _)).toList

  /**
    * Maps [[VarDecl]] `v: tp [= df]` to `vᵢ: mᵢ'(tp) [= mᵢ'(df)]`.
    */
  def applyMor(ctx: Context, vd: VarDecl, i: Int): VarDecl = {
    // should refactor VarDecl apply to avoid orNull antipattern?
    VarDecl(suffix(vd.name, i), vd.tp.map(applyMor(ctx, _, i)).orNull, vd.df.map(applyMor(ctx, _, i)).orNull)
  }

  /**
    * Maps [[VarDecl]] `v: tp [= df]` to `List(v₁: m₁'(tp) [= m₁'(df)], …, vₙ: mₙ'(tp) [= mₙ'(df)])`.
    *
    * The resulting [[VarDecl]] should be treated in [[applyMo]]
    */
  def applyMors(ctx: Context, vd: VarDecl): List[VarDecl] =
    mors.indices.map(applyMor(ctx, vd, _)).toList

  /**
    * Creates the [[Context]] `name₁: m₁'(tp), …, nameₙ: mₙ'(tp)`.
    *
    * Should be used dually to [[applyTerm]].
    */
  def bindTerm(ctx: Context, name: LocalName, tp: Term): Context = {
    applyMors(ctx, tp).zipWithIndex.map {
      case (mTp, i) => VarDecl(suffix(name, i), mTp)
    }
  }
}
