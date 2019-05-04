package info.kwarc.mmt.contextgraphs
import info.kwarc.mmt.api.symbols
import info.kwarc.mmt.api.modules
import info.kwarc.mmt.api._
import objects._
import symbols._
import utils.xml.addAttrOrChild

import info.kwarc.mmt.api._
import modules._
import frontend._
import checking._
import uom.ElaboratedElement
import objects._
import notations._

import scala.xml.Elem
import Theory._
import info.kwarc.mmt.api.utils.MMT_TODO

/*class BinaryDefinedRelation(feature: String, p : DPath, n : LocalName, meta: Option[MPath], tpC: TermContainer, dfC : TermContainer, notC: NotationContainer, val fromC : TermContainer, val toC : TermContainer, val isImplicit : Boolean)
  extends DerivedModule(feature, p, n, meta, tpC, dfC, notC) with Link {

  def namePrefix = LocalName(path)
} */

class BinaryDefinedRelation extends ModuleLevelFeature("attack"){
  def getHeaderNotation = List(SimpArg(1),Delim("->"), SimpArg(2), Delim("with"), SimpArg(3))
  def check(dd: DerivedModule)(implicit env: ExtendedCheckingEnvironment) {}
}
