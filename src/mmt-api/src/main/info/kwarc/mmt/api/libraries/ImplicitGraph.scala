package info.kwarc.mmt.api.libraries

import info.kwarc.mmt.api._
import objects._
import objects.Conversions._
import modules._
import utils.HashMapToSet
import utils.MyList._

import scala.collection.mutable.HashSet

class ImplicitGraph {
  case class FromTo(from: MPath, to: MPath)
  case class ImplicitMorph(path: List[MPath], morph: Term)
  type Cause = MPath
  val underlying = new utils.MoCHashMapToSet[FromTo,ImplicitMorph,Cause] {
    private def mentions(mor: Term): Iterator[Cause] = mor match {
      case OMS(q) => Iterator(q.module)
      case OMPMOD(q,_) => Iterator(q)
      case OMIDENT(OMPMOD(q,_)) => Iterator(q)
      case OMStructuralInclude(q,r) => Iterator(q, r) 
      case OMCOMP(ms) => ms.iterator flatMap mentions
      case _ => Iterator.empty
    }
      
    def getCauses(ft: FromTo, im: ImplicitMorph) = {
      Iterator(ft.from, ft.to) ++ im.path ++ mentions(im.morph)
    }
  }
}



/** maintains a binary relation on N where pairs in the relation are labeled with values from E
 * hashes in both directions are used to make all lookups fast
 */
class LabeledHashRelation[N,E] {
   /** maps every FROM to the set of pairs (TO, VALUE) */
   private val edgesFrom = new HashMapToSet[N,(N,E)]
   /** maps every TO to the set of pairs (FROM, VALUE) */
   private val edgesTo   = new HashMapToSet[N,(N,E)]

   /** adds (from, to) into the relation with label edge
    * if the pair is already in the relation, the function clash is called before the value is set
    */
   def update(from: N, to: N, edge: E) {
      edgesFrom += (from, (to,   edge))
      edgesTo   += (to,   (from, edge))
   }
   /** retrieves the label of the pair (from,to), if any */
   def apply(from: N, to: N) : Option[E] = {
      edgesFrom(from) find {
         case (n, e) => n == to
      } map {
         case (n, e) => e
      }
   }
   /** retrieves all pairs (TO,VALUE) for from */
   def outOf(from: N) : HashSet[(N,E)] = edgesFrom(from)
   /** retrieves all pairs (FROM,VALUE) for to */
   def into (to: N) : HashSet[(N,E)] = edgesTo(to)

   def clear {
      edgesFrom.clear
      edgesTo.clear
   }

   override def toString = edgesFrom.map {
      case (n, set) => "from " + n.toString + "\n" + (set.map {case (n,e) => "  " + n.toString + "\n  " + e.toString + "\n"}.mkString("\n"))
   }.mkString("\n")
}

/**
 * thrown if the uniqueness condition of UniqueGraph is violated
 *  @param value the existing value
 */
case class AlreadyDefined[E](from: Term, to: Term, old: E, nw: E) extends java.lang.Throwable {
  override def toString = s"implicit morphism $nw: $from -> $to already defined as $old"
}

/** A diagram of theories and morphisms with at most one edge between any two nodes.
 *  Morph.simplify is used to normalize paths, and equality of paths is checked by comparing normal forms;
 *  this criterion is sound and efficient but not complete.
 */
// note that Library may instantiate lib with itself, i.e., local lookup
class UniqueGraph(lib: Lookup) extends LabeledHashRelation[Term,Term] {
   /**
    * overrides update to check for existing morphisms
    *
    * throws [[AlreadyDefined]] if an implicit morphism between the same theories already exists
    */
   override def update(from: Term, to: Term, morph: Term) {
      val fromN  = TheoryExp.simplify(from)
      val toN    = TheoryExp.simplify(to)
      val morphN = Morph.simplify(morph)(lib)
      // compare to the current edge
      val current = apply(fromN,toN)
      current foreach {c =>
        // append a final implicit morphism (which Morph.equal would not know about otherwise)
        if (Morph.equal(c, morphN, from, to)(lib)) {
          return
        } else {
          throw AlreadyDefined(from, to, c, morphN)
        }
      }
      super.update(fromN, toN, morphN)
   }
}

/** maintains a thin diagram of theories
 * This is the category generated by some edges that is guaranteed to be thin (i.e., at most one morphism between any two objects)
 * i.e., all paths between two nodes must be equal.
 * UniqueGraph is used to maintain the generated category, see its description for the treatment of equality.
 * The generated category is precomputed so that retrieval of morphisms takes constant and insertion up to quadratic time.
 */
// TODO implicit morphisms into union is a huge problem
// they come up in particular as the inverse of conservative extensions such as Neg/Classical <--cons-- Neg+Classical
// The library stores includes of parametric theories as [[OMINST]].
class ThinGeneratedCategory(lib: Lookup) {
   /** generating edges of the diagram */
   private val direct = new UniqueGraph(lib)
   /** all morphisms of the diagram, i.e., including compositions (also includes direct edges) */
   private val impl   = new UniqueGraph(lib)

   /** adds an implicit morphism
    * @param from domain
    * @param to codomain
    * @param morph the morphism
    * throws [[AlreadyDefined]] if an implicit morphism m between the same theories already exists
    */
   def update(from: Term, to: Term, morph: Term) {
      // TODO: decompose links into complex theories
      from match {
         case OMPMOD(_, _) =>
             //TODO handle args
             direct(from, to) = morph
             impl(from, to) = morph // if a morphism already exists, this will check equality and throw exception if inequal
             (impl into from) foreach {
                case (f,m) =>
                  impl(f,to) = OMCOMP(m, morph)
                  (impl outOf to) foreach {
                      case (t,m2) => impl(f, t) = OMCOMP(m, morph, m2)
                  }
             }
             (impl outOf to) foreach {
                case (t,m) => impl(from, t) = OMCOMP(morph, m)
             }
         case ComplexTheory(cont) =>
            cont.getIncludes.foreach {i => update(OMMOD(i), to, morph)}
         case TUnion(ts) => ts.foreach {t => update(t, to, morph)}
         case _ =>
      }
   }

   def applyAtomic(from: MPath, to: MPath) = if (from == to) Some(OMCOMP()) else impl(OMMOD(from), OMMOD(to))

   private def checkUnique(mors: List[Term], from: Term, to: Term) = {
      if (mors.isEmpty)
        None
      else {
        val h = mors.head
        if (mors.tail.forall(m => Morph.equal(h,m, from, to)(lib)))
         Some(mors.head)
        else
         None
      }
   }


   /** retrieves the implicit morphism between two theories (if any)
    * @param from domain
    * @param to codomain
    * @return the implicit morphism if one exists
    */
   def apply(from: Term, to: Term) : Option[Term] = {
      if (from == to) Some(OMCOMP()) else (from, to) match {
         // atomic domain: case split on codomain
         case (OMMOD(f), OMMOD(t)) => applyAtomic(f,t)
         case (OMMOD(f), OMPMOD(t,_)) => applyAtomic(f,t)
         case (OMMOD(f), TUnion(ts)) =>
            val tsMors = ts.flatMap {t => apply(from,t).toList}
            checkUnique(tsMors, from, to)
         case (OMMOD(f), ComplexTheory(toCont)) =>
            val toMors = toCont.getIncludes.flatMap {t => applyAtomic(f,t).toList}
            checkUnique(toMors, from, to)
         // otherwise: case split on domain for arbitrary codomain
         case (OMPMOD(p, args), _) =>
            // TODO check agreement with args
            apply(OMMOD(p), to)
         case (TUnion(ts), _) =>
            if (ts.isEmpty) return Some(OMCOMP())
            val tsMors = ts.map {t => (t, apply(t, from).getOrElse(return None))}
            //TODO check agreement and return amalgamation of morphisms
            None
         // TODO remove unions or handle their interaction with ComplexTheory
         case (ComplexTheory(fromC), _) =>
            val fromCMors = fromC.map {
               case IncludeVarDecl(_, tp,_) =>
                  apply(tp, to)
               case vd => to match {
                  case ComplexTheory(toC) => Some(OMCOMP())
                  case _ => None
               }
            }
            //TODO check agreement and return amalgamation of morphisms
            None
         case _ => None // catches semiformal theories, which may be generated by the parser
      }
   }

   /** retrieves all pairs (to,morph) for from */
   def outOf(from: Term) : HashSet[(Term,Term)] = from match {
      case OMMOD(p) => impl.outOf(from)
      case OMPMOD(p, args) => impl.outOf(from) //TODO check agreement with args
      case TUnion(ts) => impl.outOf(TheoryExp.simplify(from)) //TODO does not yield all morphisms
      case ComplexTheory(cont) => impl.outOf(from)  //TODO does not yield all morphisms
      case _ => HashSet.empty
   }
   /** retrieves all pairs (from,morph) for to */
   def into (to: Term) : HashSet[(Term,Term)] = to match {
      case OMMOD(p) => impl.into(to)
      case OMPMOD(p, _) => impl.into(to)
      case TUnion(ts) => HashSet(ts:_*).flatMap(t => into(t))
      case ComplexTheory(cont) =>
         HashSet(cont.getIncludes:_*).flatMap(t => into(OMMOD(t)))
      case _ => HashSet.empty
   }

   def clear {
      direct.clear
      impl.clear
   }
}
