package info.kwarc.mmt.mizar.newxml.translator

import info.kwarc.mmt._
import api._
import info.kwarc.mmt.mizar.newxml.mmtwrapper.MizarPrimitiveConcepts._
import info.kwarc.mmt.mizar.newxml.translator.claimTranslator._
import info.kwarc.mmt.mizar.newxml.translator.contextTranslator.{translate_new_Variable}
import info.kwarc.mmt.mizar.newxml.translator.definitionTranslator.translate_Definition
import info.kwarc.mmt.mizar.newxml.translator.statementTranslator.translate_Choice_Statement
import info.kwarc.mmt.mizar.newxml.translator.termTranslator.translate_Term
import objects._
import mizar.newxml.syntax._

object justificationTranslator {
  def translate_Justification(just:Justification, claim: Term)(implicit defContext: DefinitionContext): Option[objects.Term] = just match {
    case Straightforward_Justification(pos, _refs) => None
    case _: Block =>
      defContext.pushThesis(claim)
      val usedFacts: List[Term] = usedInJustification(just)
      defContext.popThesis
      //TODO: actually translate the proofs, may need additional arguments from the context, for instance the claim to be proven
      Some(uses(claim, usedFacts))
    case Scheme_Justification(_, _, _, _, _, _refs) => Some(uses(claim, globalReferences(_refs)))
  }
  def globalReferences(refs: List[Reference]): List[Term] = refs flatMap {
    case ref: Theorem_Reference => Some(ref.referencedLabel())
    case ref: Definition_Reference => Some(ref.referencedLabel())
    case _ => None
  }
  def translate_Iterative_Equality_Proof(it: Iterative_Equality)(implicit defContext: DefinitionContext): objects.Term = {
    val claim = translate_Claim(it)
    defContext.pushThesis(claim)
    val usedFacts: List[Term] = it._just::it._iterSteps.map(_._just) flatMap(usedInJustification)
    defContext.popThesis
    //TODO: actually translate the proofs, may need additional arguments from the context, for instance the claim to be proven
    uses(claim, usedFacts)
  }
  def translate_Exemplification(exemplification: Exemplification)(implicit defContext: => DefinitionContext): List[objects.Term] = {
    var exams = List[Term]()
    exemplification._exams foreach {translate_Exemplifications(_)(defContext) foreach {exams :+= _ }}
    exams
  }
  def translate_Exemplifications(exam: Exemplifications)(implicit defContext: =>DefinitionContext): Option[objects.Term] = {
    val existQuant = defContext.popExistQuant(exam.varO map translate_new_Variable)
    existQuant map(ex => ProofByExample(ex.tp.get, translate_Term(exam._tm)(defContext)))
  }
  def translate_Proved_Claim(provedClaim: ProvedClaim)(implicit defContext: => DefinitionContext): (Term, Option[Term]) = {
    val claim = provedClaim._claim match {
      case Diffuse_Statement(_) => provedClaim._just.get match {
        case Block(_, _, _items) =>
          val claims = _items.map(_._subitem match { case c: Claim => (true, Some(c)) case _ => (false, None) }).filter(_._1).map(_._2.get)
          And(claims.map(translate_Claim(_)(defContext)))
        case _ => trueCon
      }
      case _ => translate_Claim(provedClaim._claim)(defContext)
    }
    val prf = (provedClaim._claim, provedClaim._just) match {
      case (_, Some(just)) => translate_Justification(just, claim)(defContext)
      case (it: Iterative_Equality, None) => Some(translate_Iterative_Equality_Proof(it)(defContext))
      case (_, None) => throw ProvedClaimTranslationError("No proof given for claim, which is not an iterative-equality (proving itself). ", provedClaim)
    }
    (proof(claim), prf)
  }
  def translate_Diffuse_Statement(ds: Diffuse_Statement, _just: Option[Justification])(implicit defContext: DefinitionContext) = _just match {
    case Some(Block(_, _, _items)) =>
      val claims = _items.map(_._subitem match { case c: Claim => (true, Some(c)) case _ => (false, None) }).filter(_._1).map(_._2.get)
      And(claims.map(translate_Claim(_)))
    case _ => trueCon
  }
  def usedInJustification(just: Justification)(implicit defContext: DefinitionContext): List[Term] = just match {
    case Straightforward_Justification(_, _refs) => globalReferences(_refs)
    case Block(_, _, _items) =>
      def translateSubitems(subs: List[Subitem]): List[Term] = subs match {
        case Nil => Nil
        case it :: tail => it match {
          case st: Statement =>
            val ProvedClaim(clm, j) = st.prfClaim
            val claim = clm match {
              case ds: Diffuse_Statement => translate_Diffuse_Statement(ds, j)
              case _ => translate_Claim(clm)(defContext)
            }
            st match {
              case cs: Choice_Statement =>
                val (addArgs, _) = translate_Choice_Statement(cs)
                defContext.addArguments(addArgs)
              case _ =>
            }
            defContext.pushThesis(claim)
            val trIt = st.prfClaim._claim match {
              case _: Diffuse_Statement => j map usedInJustification getOrElse Nil
              case Proposition(_, _, Thesis(_)) => j map usedInJustification getOrElse Nil
              case it: Iterative_Equality if (j == None) =>
                val And(clms) = translate_Claim(it)
                val justs = it._just :: it._iterSteps.map(_._just)
                clms ::: justs.flatMap(usedInJustification)
              case claim =>
                translate_Claim(claim) :: (j map usedInJustification getOrElse Nil)
            }
            defContext.popThesis
            defContext.killConjunct(claim)
            trIt ::: translateSubitems(tail)
          case ex: Exemplification => translate_Exemplification(ex) ::: translateSubitems(tail)
          case Per_Cases(_just) =>
            val disjuncts = defContext.getDisjuncts
            val n = disjuncts.length
            val (caseBlocks, remainingTail) = tail.splitAt(n)
            val cases = caseBlocks map {case cb:Case_Block => cb case other => throw DeclarationTranslationError("Expected exactly two case blocks after per cases item. ", other)}
            val usedInCases = disjuncts zip cases flatMap {case (conj, just) =>
              val res = translateSubitems(List(just))
              defContext.killDisjunct(conj)
              res
            }
            usedInJustification(_just):::usedInCases:::translateSubitems(remainingTail)
          case Assumption(ass) =>
            defContext.killAssumption(translate_Assumption_Claim(ass))
            translateSubitems(tail) //Since they already need to be known
          case _: Reduction => translateSubitems(tail) //TODO: translate this to something
          case _: Identify => translateSubitems(tail)
          case _@ Default_Generalization(_qual, _conds) =>
            defContext.killArguments(_qual._children.flatMap(contextTranslator.translate_Context(_)))
            translateSubitems(tail)
          case _@ Generalization(_qual, _conds) =>
            _qual._children.flatMap(contextTranslator.translate_Context(_)) foreach (defContext.killUnivQuant)
            translateSubitems(tail)
          case prDef: PrivateDefinition =>
            // This will add the definition as to the list of local definitions inside the definition context
            translate_Definition(prDef)
            translateSubitems(tail)
          case _ => Nil
        }
      }
      translateSubitems(_items map (_._subitem))
    case Scheme_Justification(_, _, _, _, _, _refs) => globalReferences(_refs)
  }
}